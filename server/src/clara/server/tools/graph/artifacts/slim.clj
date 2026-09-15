(ns clara.server.tools.graph.artifacts.slim
  "Reduce a `rulebase-analysis` to what is worth writing to disk.

  `slim-rulebase-analysis` drops `dropped-top-level-keys` / `dropped-production-keys` /
  `dropped-fact-type-keys` / `dropped-dep-graph-keys` / `dropped-condition-keys` and collapses
  every cross-reference to the name it points at. **It only ever removes
  information**: nothing is derived, re-indexed, or re-serialized, and there is
  no inverse — a reader who wants what is gone asks the running explorer or
  inverts a key this file keeps, per `recovery`.

  `clara.server.tools.graph.artifacts.parts` then splits the result into the
  files a reader actually opens, and
  `clara.server.tools.graph.artifacts.digest` writes the small orientation file
  that goes beside them."
  (:require
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.conditions :as conditions]
   [clojure.walk :as walk]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; What is kept, what is dropped, and why:
;;
;; `clara.server.tools.graph.core/->rulebase-analysis` is shaped for `GET /v1/rulebase-analysis`: a
;; browser fetches it once and indexes it in memory, so restating every edge, expanding every
;; cross-reference into a record, and carrying the whole Rete graph costs the UI nothing. Written to
;; disk it takes a 3,400-rule ruleset's analysis to 30MB — and makes it
;; *invalid EDN*, since the only `#"…"` literals in the file live in `:nodes`.
;;
;;   keep anything whose value required interpreting a raw fact type, a condition kind, or a
;;          hierarchy — `:lhs-types`, `:insert-types`, `:retract-types`, `:fact-types`,
;;          `:ancestors`, `:dep-graph`.
;;
;;   drop Rete internals, a second serialization of something another artifact already holds,
;;          per-reference restatements of a record the same file carries once, and a direction of
;;          something kept that is read back out of it by pure inversion — `:inserted-by-rules`,
;;          `:retracted-by-rules`, `:used-by-rules`, `:used-by-queries`, `:descendants`, and
;;          `:dep-graph`'s `:downstream`. Each one names where to get it instead, and that is
;;          always the server, a sibling file, or an inversion of something this same file holds.
;;
;; THE INVERSE PAIRS. **This comment is the one place these are explained.** `recovery` states each
;; one tersely because it is written into the artifact for a reader with no classpath; every other
;; mention — `clara.server.tools.graph.artifacts.digest`'s :more,
;; docs/persisted-artifacts.md, the offline report script — points here
;; rather than restating, because five copies of a rule about closure direction is five chances to
;; get one of them backwards.
;;
;; Each dropped key is the exact inverse of one the file keeps, and they come in two families:
;;
;;   :dep-graph :downstream    transpose of :upstream over the same map
;;   :descendants              transpose of :ancestors over :fact-types
;;
;;   :used-by-rules            :lhs-types, closed over DESCENDANTS
;;   :used-by-queries          the same, over :queries
;;   :inserted-by-rules        :insert-types, closed over ANCESTORS
;;   :retracted-by-rules       :retract-types, closed over ANCESTORS
;;
;; THE TWO CLOSURES RUN IN OPPOSITE DIRECTIONS, which is the easy thing to get wrong. A production
;; MATCHING T matches T and every descendant of T, because a descendant fact satisfies that
;; condition. A production INSERTING or RETRACTING T affects T and every ancestor of T, because the
;; fact it makes *is* one of each. Both are computed off `:ancestors`, which is kept — transpose it
;; for the descendant direction. `clara.server.tools.graph.fact-types/production-fact-type-updates`
;; is where this library does this, and insert and retract are one symmetric pass there, which is
;; why they are one rule here.
;;
;; The closure is load-bearing, not a refinement: inverting `:lhs-types` without it reproduces only
;; 2033 of one 3,687-entry artifact's entries. Measured over a repo artifact (3426 rules) and a
;; restored 18-ruleset session, with zero drift in either: `:downstream` 3411/3411 ·
;; 4768/4768, `:used-by-rules` 3687/3687 · 4764/4764, `:used-by-queries` 344/344 · 493/493,
;; `:inserted-by-rules` 4338/4338 on the session.
;; `clara.server.tools.graph.artifacts.slim-test/dropped-directions-invert-back-test`
;; pins all of them, both closure directions included.
;;
;; WHY THE :lhs CONDITION-INTERNAL KEYS GO. `->rulebase-analysis` normalizes every production's LHS
;; into homogeneous maps and leaves two of its own keys on the result: `:raw-condition`, the raw
;; Clara condition a group or accumulator node was built from — retained so a
;; compiler-coupled walk can read the original structure without converting back — and
;; `::conditions/normalized`, the marker saying a node has been through that pass. Both are the
;; analysis talking to itself. The raw subtree is a duplicate of the node carrying it, and the
;; marker is true of every node in this file by construction, so neither distinguishes anything a
;; reader can act on. Left in, they cost a 57-character namespaced key on every condition plus a
;; second copy of every group and accumulator.
;;
;; Dropped here rather than by calling
;; `clara.server.tools.graph.core/get-rulebase-analysis-external-view`, which strips
;; these same two today. That function is the *HTTP API's* boundary, and it is free to start
;; removing things this artifact wants to keep. What goes on disk is this namespace's decision;
;; `dropped-condition-keys` is that decision written down, and it moves only when this file says so.
;;
;; WHY CROSS-REFERENCES COLLAPSE. Under the fact-type hierarchy API a *reference* to a production or
;; fact type is a record, not a name:
;;
;;   {:name "a.ns/r" :id "a-ns-r-nhj0nz92" :ns "a.ns" :type "rule"}
;;   {:name ":c/x"   :id "c-x-ggimk8ns"     :known true}
;;
;; Right for the API, where a UI wants a stable handle on every edge; on disk it is the dominant
;; cost. One restored 12-ruleset session carried ~62K of them across ~11MB, where the names alone
;; are ~4MB — and everything but the name is already known: `:ns` is a prefix of `:name` in all
;; cases, `:type` agrees with which of `:rules`/`:queries` holds the name in all cases, and `:id`
;; keys the very record the analysis stores under that name. `:known false` is the one bit that is
;; not, so the distinct name occurrences it applied to are hoisted into `:slim :unknown-fact-types`
;; rather than repeated across all references. Measured on that session: ~26MB → ~17MB, parsing with
;; `clojure.edn`.
;;
;; `:fact-type-id-index` / `:production-id-index` are the same problem one level up — flat `{id ->
;; name}` maps of the `:id`/`:name` pair already on every record. With `:id` gone they carry nothing
;; (every entry resolves against the corresponding `:name`-keyed map on that session), so they go
;; with `:nodes`.
;;
;; `:merged-annotations` is the analysis's *input*, which the explorer stamps onto its result so a
;; caller can tell whether a cached analysis is still current — see
;; `clara.server.tools.graph.artifacts.flow/rulebase-analysis-of?`, which is the whole reason it
;; exists. On disk it is merged-annotations.edn a second time, minus the layers and provenance that
;; file also carries, so it goes too.
;;
;; `:ns-deps` is the requires/aliases/imports of every rule-owning namespace. Nothing reading these
;; artifacts consumes it yet, and `:refers` is refer-all-expanded to every public var of each
;; required namespace, so on a real ruleset it is not a small key. Dropped *deliberately* rather
;; than by omission: whether to persist it is a live question, and an artifact that answers it by
;; silently lacking the key answers it for everyone. See `recovery`.
;; ===========================================================================

(def dropped-top-level-keys
  "Keys removed from the analysis map itself. See `recovery` for where each one
  is answered instead."
  #{:nodes :fact-type-id-index :production-id-index :merged-annotations :ns-deps})

(def dropped-production-keys
  "Keys removed from every rule and query. See `recovery` for where each one is
  answered instead."
  #{:id :lhs-form :upstream :downstream
    :dynamic-insert-types-detected :dynamic-retract-types-detected})

(def dropped-fact-type-keys
  "Keys removed from every entry of `:fact-types`. See `recovery` for where each
  one is answered instead.

  Five of the six are one idea: a fact type's relationships to productions and
  to other types are stored here in *both* directions, and one direction is the
  exact inverse of the other. `:inserted-by-rules` inverts `:insert-types`,
  `:retracted-by-rules` inverts `:retract-types`, `:used-by-rules` inverts
  `:lhs-types`, `:used-by-queries` inverts the same over `:queries`, and
  `:descendants` transposes `:ancestors`. Each forward direction is kept, so
  holding the reverse doubles what the relationship costs on disk without adding
  a fact. See the header comment for the closure each one needs.

  This is *every* production→type direction the explorer emits. The rule is the
  symmetry, not the byte count: insert and retract are one pass in
  `clara.server.tools.graph.fact-types` and are one rule here, whether or not a
  given rulebase happens to retract much."
  #{:id :inserted-by-rules :retracted-by-rules :used-by-rules :used-by-queries :descendants})

(def dropped-dep-graph-keys
  "Keys removed from every entry of `:dep-graph`. See `recovery`.

  `:downstream` is the transpose of `:upstream` across the same map: a node is
  downstream of exactly the nodes that list it upstream. The graph therefore
  carries one edge set between the two keys, and keeping both doubles what it
  costs on disk. `:upstream` is the kept direction because
  `clara.server.tools.graph.artifacts.digest` already counts edges off it."
  #{:downstream})

(def dropped-condition-keys
  "Keys removed from every node of a production's `:lhs`, **at any depth** —
  unlike the sets above, these sit inside a kept value rather than beside it.
  Both are the analysis's own normalization bookkeeping; see the header
  comment for why this file drops them itself. `recovery` says where they are
  answered instead.

  `::conditions/normalized` is written through the alias rather than as a
  keyword literal so that a rename of that namespace fails at load
  here, loudly, instead of silently leaving the marker in every artifact."
  #{:raw-condition ::conditions/normalized})

(def ^:private cross-reference-keys
  "Every key the explorer puts on a cross-reference. This is a pointer to a production or fact type
  the analysis records in full elsewhere.

  Used as a closed set: a map is a cross-reference only if it carries `:name`, `:id`, and *nothing
  outside this set*. A record always has something else on it (`:lhs`, `:ancestors`,
  `:retracted-by-rules`, …), so the two cannot be confused. If a field is added to cross-references,
  they stop matching and are carried whole. This results in persisted file size growth, which is the
  safe direction to fail in."
  #{:name :id :ns :type :known})

(defn- cross-reference?
  "Is `x` a pointer to a production or fact type, rather than the record for one?
  `slim-rulebase-analysis` checks this for every map it walks: a pointer collapses to the bare
  `:name` it points at, a record is kept.

    {:name \"b/two\" :id \"b-two-22222222\" :known true}
    ;; pointer → \"b/two\"

    {:name \"b/two\" :id \"b-two-22222222\" :ns \"b\" :ancestors […] :abstract? false …}
    ;; record → kept

  The distinguishing keys are shown as ones the artifact still carries. It is the *input*
  being tested here, so any of `dropped-fact-type-keys` would serve as well — but an example
  naming a field no reader will find on disk sends them looking for it.

  `cross-reference-keys` is what tells them apart, and it is also the only test safe to run on an
  arbitrary map, so it goes first. `contains?` looks a key up, and looking a keyword up in a
  *sorted* map keyed by rule-name strings throws out of the comparator, eg. what
  `:merged-annotations` is. Ordered this way such a map is rejected on its keys and never probed;
  anything that reaches `contains?` is keyword-keyed by construction."
  [x]
  (and (map? x)
       (every? cross-reference-keys (keys x))
       (contains? x :name)
       (contains? x :id)))

(defn- collapse-cross-references
  "`x` with every cross-reference in it replaced by the name it points at.

  Expected to only ever be called on a *field* of a record, never on a record itself, so a minimal
  record that happened to match `cross-reference?` cannot be collapsed away."
  [x]
  (walk/postwalk #(if (cross-reference? %) (:name %) %) x))

(defn- slim-record
  "One production or fact-type entry: drop `dropped`, collapse references in what is left."
  [dropped record]
  (reduce-kv (fn [m k v]
               (cond-> m
                 (not (contains? dropped k)) (assoc k (collapse-cross-references v))))
             {}
             record))

(s/defn ^:private get-unknown-fact-type-names :- #{s/Str}
  "The fact-type names every `:known false` reference in `analysis` points at.

  Collected before collapsing, because collapsing is what discards the bit. A name here is one the
  analysis references but has no `:fact-types` entry for, or that the explorer could not resolve —
  worth surfacing as a set, not worth repeating on all 519 references to it."
  [analysis :- schema/RulebaseAnalysis]
  (let [found (volatile! (sorted-set))]
    (walk/postwalk (fn [x]
                     (when (and (cross-reference? x) (false? (:known x)))
                       (vswap! found conj (:name x)))
                     x)
                   analysis)
    @found))

(def ^:private recovery
  "Written into the artifact as `:slim :recover`, so a reader who opens the file
  cold is told what is missing and who answers it — without this namespace
  having to be on their classpath."
  {:nodes
   (str "the Rete node graph. Needs a live session: GET /v1/rulebase-analysis, or "
        "clara.server.tools.graph.core/->rulebase-analysis at a REPL. Only the "
        "explorer's Rete view consumes it.")
   :id
   (str "the explorer's URL-safe handle for a production or fact type. Every "
        "map in this file is keyed by :name, which is kept, and every route "
        "takes a name: GET /v1/rules/:fq-name.")
   :fact-type-id-index
   (str "a flat {id -> name} map the explorer added to rulebase-analysis. Every "
        "entry is exactly the :id this file already drops paired with a :name "
        "that is already a key in :fact-types, so it carries nothing this file "
        "doesn't. GET /v1/fact-types/:fq-name if a caller does have an id to "
        "resolve.")
   :production-id-index
   "as :fact-type-id-index, over :rules / :queries instead of :fact-types"
   :merged-annotations
   (str "the annotations the analysis was computed over, which the explorer "
        "stamps onto its result so a caller can tell whether a cached analysis "
        "is still current. merged-annotations.edn, beside this file, is the "
        "same map under :annotations — with the layers and per-key provenance "
        "that produced it, which this key does not carry. Read it through "
        "clara.server.tools.graph.artifacts.store/read-merged-annotations: on "
        "disk it points at the layer files rather than restating them, and that "
        "reader resolves the references.")
   :ns-deps
   (str "the requires, aliases, imports and :refer-clojure of every rule-owning "
        "namespace. Dropped deliberately rather than by omission: nothing "
        "reading these artifacts consumes it yet, and its :refers is expanded "
        "to every public var of each required namespace, so it is large. "
        "GET /v1/rulebase-analysis serves it, or "
        "clara.server.tools.graph.core/->rulebase-analysis at a REPL.")
   :lhs-form
   (str "a rendering of :lhs, which is kept and carries strictly more. "
        "GET /v1/rules/:fq-name serves the same string.")
   :raw-condition
   (str "the raw Clara condition a normalized :lhs group or accumulator node "
        "was built from, which the explorer retains for its "
        "compiler-coupled binding walk. A duplicate of the node carrying it: "
        ":condition-type + :children describe the same group and :from the same "
        "accumulator source, both kept, and :lhs-form renders the same source. "
        "Nothing serves it — GET /v1/rules/:fq-name strips it too — so "
        "clara.server.tools.graph.core/->rulebase-analysis at a REPL is the only "
        "place it survives.")
   ::conditions/normalized
   (str "the explorer's marker that a :lhs node has been through "
        "clara.server.tools.graph.conditions/normalize-lhs. True of every node "
        "of every :lhs in this file by construction, so it distinguishes nothing "
        "here. Same recovery as :raw-condition.")
   :upstream "GET /v1/rules/:fq-name, or :dep-graph in this file, keyed by production name"
   :downstream
   (str "dropped at two levels, and answered differently at each. On a "
        "production: :dep-graph in this file, keyed by production name, or GET "
        "/v1/rules/:fq-name. On a :dep-graph entry: the transpose of :upstream "
        "across that same map — a node is downstream of exactly the nodes "
        "listing it upstream. Verified exact (3411 of 3411 nodes on a repo, "
        "4768 of 4768 on a restored session, no drift).")
   :inserted-by-rules
   (str "the inverse of each rule's :insert-types, which is kept, closed over "
        "the ANCESTORS in :ancestors, which is kept: a rule inserting T inserts "
        "T and every ancestor of T, since the fact it makes is one of each. "
        "GET /v1/fact-types serves it directly.")
   :retracted-by-rules
   "as :inserted-by-rules, over :retract-types rather than :insert-types"
   :used-by-rules
   (str "the inverse of each rule's :lhs-types, which is kept, closed over the "
        "DESCENDANTS of each type — transpose the kept :ancestors to get them: "
        "a rule matching T uses T and everything deriving from T. Note this "
        "closure runs the OPPOSITE way from :inserted-by-rules'. The closure is "
        "required: inverting :lhs-types alone matches only 2033 of the 3687. "
        "Verified exact (3687 of 3687 fact types on a repo, 4764 of 4764 on a "
        "restored session, no drift). GET /v1/fact-types/:fq-name serves it "
        "directly.")
   :used-by-queries
   (str "as :used-by-rules, inverting :lhs-types over :queries rather than "
        "over :rules. Verified exact (344 of 344 fact types on a repo, 493 of "
        "493 on a restored session, no drift).")
   :descendants
   (str "the transpose of :ancestors, which is kept, over this same :fact-types "
        "map: a type is a descendant of every name in its own :ancestors. Invert "
        "it — the two carry one edge set between them, so holding both doubles "
        "what the hierarchy costs here. GET /v1/fact-types/:fq-name serves it "
        "directly, shallowest-first.")
   :dynamic-insert-types-detected
   (str "the annotation layers hold these callsites as authored (symbols, raw "
        "type tokens) under :clara-rules/dynamic-insert-types-detected, and "
        "merged-annotations.edn resolves to them. "
        "GET /v1/rules/:fq-name serves the resolved/serialized form this file "
        "omits.")
   :dynamic-retract-types-detected
   "as :dynamic-insert-types-detected, under :clara-rules/dynamic-retract-types-detected"})

(def ^:private references-note
  (str "Cross-references to a production or fact type are the bare :name "
       "string, not the explorer's {:name :id :ns :type} / {:name :id :known} "
       "record. Look the name up in :rules, :queries or :fact-types for the "
       "rest; GET /v1/rules/:fq-name and GET /v1/fact-types serve the expanded "
       "form. Names that carried :known false are listed once in "
       ":unknown-fact-types."))

(defn- update-present
  "Like `update`, but only when `m` already has `k`."
  [m k f]
  (cond-> m (contains? m k) (update k f)))

(defn- slim-conditions
  "One production's `:lhs` with `dropped-condition-keys` gone from every node of
  the condition tree, however deep. A prewalk, so a `:raw-condition` subtree is
  removed before its own contents are visited rather than after."
  [lhs]
  (walk/prewalk #(if (map? %) (apply dissoc % dropped-condition-keys) %) lhs))

(defn- slim-production
  "One rule or query. `:lhs` is pruned first: there is no point collapsing the
  cross-references inside a `:raw-condition` subtree that is about to be
  deleted, and every fact type in one is a duplicate of one in the kept node
  beside it, so nothing `:unknown-fact-types` needs can only be found there."
  [record]
  (slim-record dropped-production-keys (update-present record :lhs slim-conditions)))

(s/defn slim-rulebase-analysis :- schema/RulebaseAnalysis
  "`analysis` with `dropped-top-level-keys`, `dropped-production-keys`, `dropped-fact-type-keys`,
  `dropped-dep-graph-keys` and `dropped-condition-keys` removed and every cross-reference collapsed
  to the name it points at, plus a `:slim` marker recording what went and who answers it.

  Idempotent — `dissoc` of an absent key is a no-op, and a collapsed reference is a string, which
  `cross-reference?` does not match. `:unknown-fact-types` is carried forward rather than
  recollected for that reason: a second pass has no `:known false` left to find, and must not erase
  what the first recorded.

  `dropped-top-level-keys` go first, and none of them carries a reference — `:nodes` names its fact
  types as bare strings, the two id indexes are flat `{id -> name}` maps, `:ns-deps` names
  namespaces and vars rather than fact types, and `:merged-annotations` is the input rather than a
  finding — so this narrows the walk without narrowing its answer, over the keys that dominate the
  analysis by size.

  The per-record drops run *after* the sweep, so it does cross `:descendants`, which the file will
  not hold. That is the right direction: every name it reaches is one the analysis references
  without a `:fact-types` entry of its own, whichever key carried it, and `:unknown-fact-types` is
  the one place that bit survives the collapse."
  [analysis :- schema/RulebaseAnalysis]
  (let [kept (apply dissoc analysis dropped-top-level-keys)
        unknown (into (sorted-set)
                      (concat (get-in kept [:slim :unknown-fact-types])
                              (get-unknown-fact-type-names kept)))]
    (-> kept
        (update-present :rules #(update-vals % slim-production))
        (update-present :queries #(update-vals % slim-production))
        (update-present :fact-types #(update-vals % (partial slim-record dropped-fact-type-keys)))
        (update-present :dep-graph #(update-vals % (partial slim-record dropped-dep-graph-keys)))
        (update-present :unresolved collapse-cross-references)
        (assoc :slim {:written-by "clara.server.tools.graph.artifacts.slim"
                      :dropped (into (sorted-set)
                                     (concat dropped-top-level-keys
                                             dropped-production-keys
                                             dropped-fact-type-keys
                                             dropped-dep-graph-keys
                                             dropped-condition-keys))
                      :references references-note
                      :unknown-fact-types unknown
                      :recover recovery}))))
