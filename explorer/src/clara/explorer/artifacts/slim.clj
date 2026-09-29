(ns clara.explorer.artifacts.slim
  "Reduce a `rulebase-analysis` to what is worth writing to disk.

  `slim-rulebase-analysis` drops `dropped-top-level-keys` / `dropped-production-keys` /
  `dropped-fact-type-keys` / `dropped-dep-graph-keys` / `dropped-condition-keys` and collapses
  every cross-reference to the name it points at. **It only ever removes
  information**: nothing is derived, re-indexed, or re-serialized, and there is
  no inverse — a reader who wants what is gone asks the running explorer or
  inverts a key this file keeps (see the header comment).

  `clara.explorer.artifacts.parts` then splits the result into the
  files a reader actually opens, and
  `clara.explorer.artifacts.digest` writes the small orientation file
  that goes beside them."
  (:require
   [clara.explorer.artifacts.schema :as schema]
   [clojure.walk :as walk]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; What is kept, what is dropped, and why:
;;
;; `clara.explorer.core/->rulebase-analysis` is shaped for `GET /v1/rulebase-analysis`: a
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
;;          `:dep-graph`'s `:downstream`. Where to get each one instead is always the server, a
;;          sibling file, or an inversion of something this same file holds — stated here, in
;;          code, not restated into the artifact.
;;
;; THE INVERSE PAIRS. **This comment is the one place these are explained.** The artifact carries
;; only the `:dropped` key set and `:written-by` (the writing function, resolvable at the manifest's
;; sha); every other mention — docs/persisted-artifacts.md and the offline report script — point here
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
;; for the descendant direction. `clara.explorer.fact-types/production-fact-type-updates`
;; is where this library does this, and insert and retract are one symmetric pass there, which is
;; why they are one rule here.
;;
;; The closure is load-bearing, not a refinement: inverting `:lhs-types` without it reproduces only
;; 2033 of one 3,687-entry artifact's entries. Measured over a repo artifact (3426 rules) and a
;; restored 18-ruleset session, with zero drift in either: `:downstream` 3411/3411 ·
;; 4768/4768, `:used-by-rules` 3687/3687 · 4764/4764, `:used-by-queries` 344/344 · 493/493,
;; `:inserted-by-rules` 4338/4338 on the session.
;; `clara.explorer.artifacts.slim-test/dropped-directions-invert-back-test`
;; pins all of them, both closure directions included.
;;
;; WHY THE :lhs CONDITION-INTERNAL KEYS GO. `->rulebase-analysis` normalizes every production's LHS
;; into homogeneous maps and leaves two of its own keys on the result: `:raw-condition`, the raw
;; Clara condition a group or accumulator node was built from — retained so a
;; compiler-coupled walk can read the original structure without converting back — and
;; `:clara.explorer.internal/normalized`, the marker saying a node has been through that pass. Both are the
;; analysis talking to itself. The raw subtree is a duplicate of the node carrying it, and the
;; marker is true of every node in this file by construction, so neither distinguishes anything a
;; reader can act on. Left in, they cost a namespaced key on every condition plus a
;; second copy of every group and accumulator.
;;
;; Dropped here rather than by calling
;; `clara.explorer.core/get-rulebase-analysis-external-view`, which strips
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
;; `clara.explorer.artifacts.flow/rulebase-analysis-of?`, which is the whole reason it
;; exists. On disk it is merged-annotations.edn a second time, minus the layers and provenance that
;; file also carries, so it goes too.
;;
;; `:ns-deps` is the requires/aliases/imports of every rule-owning namespace. Nothing reading these
;; artifacts consumes it yet, and `:refers` is refer-all-expanded to every public var of each
;; required namespace, so on a real ruleset it is not a small key. Dropped *deliberately* rather
;; than by omission: whether to persist it is a live question, and an artifact that answers it by
;; silently lacking the key answers it for everyone.
;; ===========================================================================

(def dropped-top-level-keys
  "Keys removed from the analysis map itself. See the header comment for where
  each one is answered instead."
  #{:nodes :fact-type-id-index :production-id-index :merged-annotations :ns-deps})

(def dropped-production-keys
  "Keys removed from every rule and query. See the header comment for where
  each one is answered instead."
  #{:id :lhs-form :upstream :downstream
    :dynamic-insert-types-detected :dynamic-retract-types-detected})

(def dropped-fact-type-keys
  "Keys removed from every entry of `:fact-types`. See the header comment for
  where each one is answered instead.

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
  `clara.explorer.fact-types` and are one rule here, whether or not a
  given rulebase happens to retract much."
  #{:id :inserted-by-rules :retracted-by-rules :used-by-rules :used-by-queries :descendants})

(def dropped-dep-graph-keys
  "Keys removed from every entry of `:dep-graph`. See the header comment.

  `:downstream` is the transpose of `:upstream` across the same map: a node is
  downstream of exactly the nodes that list it upstream. The graph therefore
  carries one edge set between the two keys, and keeping both doubles what it
  costs on disk. `:upstream` is the kept direction because
  `clara.explorer.artifacts.digest` already counts edges off it."
  #{:downstream})

(def dropped-condition-keys
  "Keys removed from every node of a production's `:lhs`, **at any depth** —
  unlike the sets above, these sit inside a kept value rather than beside it.
  Both are the analysis's own normalization bookkeeping; see the header
  comment for why this file drops them itself.

  `:clara.explorer.internal/normalized` is an explicitly internal keyword — the
  `internal` segment signals it is implementation detail, not consumer contract
  — and is written as a literal for exactly that reason."
  #{:raw-condition :clara.explorer.internal/normalized})

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
  to the name it points at, plus a `:slim` marker recording what went
  (`:dropped`), the surviving `:unknown-fact-types`, and the writing function
  (`:written-by`).

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
        (assoc :slim {:written-by 'clara.explorer.artifacts.slim/slim-rulebase-analysis
                      :dropped (into (sorted-set)
                                     (concat dropped-top-level-keys
                                             dropped-production-keys
                                             dropped-fact-type-keys
                                             dropped-dep-graph-keys
                                             dropped-condition-keys))
                      :unknown-fact-types unknown}))))
