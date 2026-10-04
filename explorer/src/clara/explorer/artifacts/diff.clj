(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.diff
  "Production-level diff of two unit directories: which rules and queries were
  added or removed, which changed and how, which fact types appeared or
  disappeared, and which producer→consumer edges were gained or lost.

  Every input the diff needs is already on disk in each unit's
  `merged-rulebase-analysis/` and annotation layers, so this runs under
  babashka (via `annotations_report.bb ... diff ...`) and on the JVM alike:

  - `read-unit`: `dir` → the values the diff compares
  - `diff`: a pure function of two `read-unit` values
  - `->text`: the compact rendering of a `diff` value
  - `rule-detail` / `rule-detail-text`: one changed production's before/after

  A diff of a unit against itself is empty in every key, as with
  `clara.explorer.artifacts.federate/diff`. What the diff does not answer:
  renames (one removed + one added), meaning (an `:rhs` tag says the text
  changed, not what it does at runtime), and per-build name stamps (names
  compare verbatim)."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; reading
;; ===========================================================================

(defn- read-edn
  "The EDN value in file `f`, throwing when it cannot be read."
  [f]
  (edn/read-string {:default (fn [_tag v] v)} (slurp (str f))))

(defn- read-edn-or-nil
  "The EDN value in file `f`, or nil when it cannot be read (missing or
   malformed)."
  [f]
  (try (read-edn f) (catch Exception _ nil)))

(defn- read-edn-or-throw
  "`what` from file `f`, throwing when it cannot be read. The throw names
   `what` and the file, so a part that failed to read is reported as that
   part, never as an absent EDN value."
  [f what]
  (try
    (read-edn f)
    (catch Exception e
      (throw (ex-info (format "Cannot diff: missing %s — %s" what (str f))
                      {:file (str f) :what what}
                      e)))))

(defn- read-layer-stack
  "The annotation layer stack of unit dir `dir`, read tolerantly: the fold
   order and extraction are `layout/read-layer-stack`; here `read-edn-or-nil`
   is the reader, so an absent or malformed layer is skipped."
  [dir]
  (layout/read-layer-stack dir read-edn-or-nil))

(defn- resolution-of
  "One production's annotation resolutions as `{dim resolution}` (`:insert`
  and/or `:retract`), for the dimensions the merged annotations hold. `{}` for
  a production with no detections (e.g. a query)."
  [anns name]
  (into (sorted-map)
        (keep (fn [[dim det-key]]
                (let [entry (get anns name)]
                  (when (contains? entry det-key)
                    [dim (get-in entry [det-key :resolution])]))))
        layout/detection-keys-by-dimension))

(defn- productions-of
  "Every production (rules and queries) as one flat map of the compared
  fields, keyed by name. Queries carry no `:insert-types`/`:retract-types` on
  disk, which read as `[]`; composed productions carry `:unit`."
  [index conditions details anns]
  (let [rules (:rules index)
        queries (:queries index)]
    (into (sorted-map)
          (map (fn [[name entry]]
                 [name
                  {:kind (if (contains? queries name) :query :rule)
                   :ns (:ns entry)
                   :lhs-types (vec (:lhs-types entry))
                   :insert-types (vec (:insert-types entry))
                   :retract-types (vec (:retract-types entry))
                   :lhs (get-in conditions [name :lhs])
                   :rhs-form (get-in details [name :rhs-form])
                   :doc (get-in details [name :doc])
                   :props (get-in details [name :props])
                   :resolution (resolution-of anns name)
                   :unit (:unit entry)}]))
          (merge queries rules))))

(defn- edges-of
  "The dep-graph as `#{[upstream downstream]}` pairs. Only `:upstream` is on
  disk; each entry fans out to one pair per upstream name."
  [dep-graph]
  (into #{}
        (mapcat (fn [[down {:keys [upstream]}]]
                  (map (fn [up] [up down]) upstream)))
        (or dep-graph {})))

(defn- read-part
  [analysis-dir k]
  (read-edn-or-throw (str analysis-dir "/" (get layout/part-files k))
                     (format "analysis part %s" (name k))))

(defn read-productions
  "Every production's compared fields from unit dir `dir`: the production
  files (index, conditions, details, merged annotations) without the
  dep-graph, fact-types, manifest, or shape. For `rule-detail`, which needs
  no full `diff` — the report's `--rule` path reads only this."
  [dir]
  (let [dir (str dir)
        analysis-dir (str dir "/" (:rulebase-analysis layout/artifact-files))
        index (read-part analysis-dir :index)
        anns (:annotations
              (layout/expand-merged-annotations
               (read-edn-or-throw (str dir "/" (:merged layout/artifact-files))
                                  "merged annotations")
               (read-layer-stack dir)))]
    (productions-of index (read-part analysis-dir :conditions)
                    (read-part analysis-dir :details) anns)))

(defn read-unit
  "The values `diff` compares, read from unit dir `dir`: the manifest (for
  provenance and scope namespaces), the slim shape, every production's
  compared fields (`read-productions`), the fact-type ancestor sets, and the
  dep-graph edge pairs."
  [dir]
  (let [dir (str dir)
        manifest (read-edn-or-throw (str dir "/" (:manifest layout/artifact-files))
                                    "unit manifest")
        analysis-dir (str dir "/" (:rulebase-analysis layout/artifact-files))]
    {:dir dir
     :manifest manifest
     :shape (get-in (read-part analysis-dir :meta) [:slim :dropped])
     :productions (read-productions dir)
     :fact-types (into (sorted-map)
                       (map (fn [[name entry]]
                              [name {:ancestors (into (sorted-set)
                                                      (map str)
                                                      (:ancestors entry))}]))
                       (read-part analysis-dir :fact-types))
     :edges (edges-of (read-part analysis-dir :dep-graph))}))

;; ===========================================================================
;; diff
;; ===========================================================================

(defn- provenance-of
  "One side's identity for `:before`/`:after`: the source sha, branch, and
   working tree, the `:variant`, and the `:analysis-run` scope fields."
  [manifest]
  {:repo (:repo manifest)
   :variant (:variant manifest)
   :sha (get-in manifest [:source :sha])
   :sha-short (get-in manifest [:source :sha-short])
   :branch (get-in manifest [:source :branch])
   :working-tree (get-in manifest [:source :working-tree])
   :namespaces (vec (get-in manifest [:analysis-run :namespaces]))
   :scope (get-in manifest [:analysis-run :scope])
   :mode (get-in manifest [:analysis-run :mode])
   :units (vec (get-in manifest [:analysis-run :units]))})

(defn- assert-same-shape!
  "Refuse a diff across slim shapes, as
   `clara.explorer.artifacts.registry/assert-compatible!` refuses a merge:
   when `:slim :dropped` differs the two units do not hold the same keys and
   every comparison would be suspect. Names both shapes."
  [before after]
  (let [b-shape (:shape before)
        a-shape (:shape after)]
    (when (not= b-shape a-shape)
      (throw (ex-info (format "Cannot diff units with differing slim shapes: before drops %s, after drops %s"
                              (pr-str b-shape) (pr-str a-shape))
                      {:before-shape b-shape
                       :after-shape a-shape})))))

(defn- scope-namespaces
  "The namespaces `unit` claims: `:analysis-run :namespaces`, falling back
   to its productions' `:ns` when the manifest names none (a composed unit
   records `[]`)."
  [unit]
  (let [nses (get-in unit [:manifest :analysis-run :namespaces])]
    (if (seq nses)
      (into #{} (map str) nses)
      (into #{} (map :ns) (vals (:productions unit))))))

(defn- scope-only-namespaces
  "`{:only-before [...] :only-after [...]}` — namespaces claimed on exactly
   one side, sorted."
  [before after]
  (let [b-nses (scope-namespaces before)
        a-nses (scope-namespaces after)]
    {:only-before (-> b-nses (set/difference a-nses) sort vec)
     :only-after (-> a-nses (set/difference b-nses) sort vec)}))

(defn- in-namespaces?
  "Whether production `name` (from `productions`) sits in one of `nses`."
  [productions nses name]
  (contains? nses (str (get-in productions [name :ns]))))

(def ^:private compared-fields
  "Production fields the diff compares, in display order: `[tag key set?]`.
   `tag` names the change in `:changed` and the field in `rule-detail-text`;
   `key` is the field in a production value; `set?` compares
   order-insensitively — the type lists (`:lhs-types`, `:insert-types`,
   `:retract-types`) are sets to the diff. `:rhs` is the tag for the
   `:rhs-form` field. One spec, so the tag set and the `--rule` detail cannot
   disagree on what counts as a change."
  [[:kind :kind false]
   [:lhs-types :lhs-types true]
   [:lhs :lhs false]
   [:insert-types :insert-types true]
   [:retract-types :retract-types true]
   [:rhs :rhs-form false]
   [:doc :doc false]
   [:props :props false]
   [:resolution :resolution false]])

(defn- compared-value
  "`(get production key)`, as a set when `set?`, so both `production-tags`
   and `rule-detail-text` read the same value for the same field."
  [production key set?]
  (let [v (get production key)]
    (if set? (set v) v)))

(defn- production-tags
  "The change tags for a production present on both sides: one tag per
   `compared-fields` entry whose value differs."
  [b a]
  (into (sorted-set)
        (keep (fn [[tag key set?]]
                (when (not= (compared-value b key set?)
                            (compared-value a key set?))
                  tag)))
        compared-fields))

(defn- diff-productions
  "`{:added [...] :removed [...] :changed {name #{tags}}}` over the
   productions outside the scope-only namespaces — those are listed under
   `:scope` instead, never as added or removed."
  [before after scope-nses]
  (let [b-prods (:productions before)
        a-prods (:productions after)
        scoped? (fn [prods name] (in-namespaces? prods scope-nses name))
        names (sort (set/union (set (keys b-prods)) (set (keys a-prods))))]
    {:added (filterv (fn [n] (and (contains? a-prods n)
                                  (not (contains? b-prods n))
                                  (not (scoped? a-prods n))))
                     names)
     :removed (filterv (fn [n] (and (contains? b-prods n)
                                    (not (contains? a-prods n))
                                    (not (scoped? b-prods n))))
                       names)
     :changed (into (sorted-map)
                    (keep (fn [n]
                            (when (and (contains? b-prods n)
                                       (contains? a-prods n)
                                       (not (scoped? b-prods n)))
                              (let [tags (production-tags (get b-prods n)
                                                          (get a-prods n))]
                                (when (seq tags) [n tags])))))
                    names)}))

(defn- ancestors-of
  "Fact type `T`'s ancestors on `side`: the `:ancestors` set of type-name
   strings `fact-types.edn` records for it, empty when `T` is not there."
  [side T]
  (or (get-in side [:fact-types T :ancestors]) #{}))

(defn- descendants-of
  "The type names on `side` that list `T` among their `:ancestors` — the
   inverse of the `:ancestors` relation, so a fact of a descendant is a fact
   of each of its ancestors."
  [side T]
  (into #{}
        (keep (fn [[name {:keys [ancestors]}]]
                (when (contains? ancestors T) name)))
        (:fact-types side)))

(defn- production-touches-type?
  "Whether production `prod` touches fact type `T` through the hierarchy: it
   matches `T` or an ancestor (`:lhs-types`), or inserts/retracts `T` or a
   descendant (`:insert-types` / `:retract-types`). `ancestors` and
   `descendants` are `T`'s closure on the same side, precomputed."
  [prod T ancestors descendants]
  (let [lhs (set (:lhs-types prod))
        out (into #{} cat [(:insert-types prod) (:retract-types prod)])]
    (or (some (conj ancestors T) lhs)
        (some (conj descendants T) out))))

(defn- scope-touched?
  "Whether fact type `T` on `side` is touched by no shared production — one
   not in `scope-prods`. Such a type appears in the diff only because a
   scope-only namespace inserted or matched it, so it belongs under `:scope`
   rather than as an added/removed fact type."
  [side scope-prods T]
  (let [shared-prods (->> (:productions side)
                          (remove (fn [[name _]] (contains? scope-prods name)))
                          (map val))
        ancestors (ancestors-of side T)
        descendants (descendants-of side T)]
    (not-any? #(production-touches-type? % T ancestors descendants) shared-prods)))

(defn- diff-fact-types
  "`{:added [...] :removed [...] :changed {name {:added [...] :removed
   [...]}} :scope [...]}` — which types appeared or disappeared, whose
   `:ancestors` changed, and which added/removed types belong under `:scope`
   because no shared production touches them. `:added` is judged against the
   after side's productions and hierarchy, `:removed` against the before
   side's."
  [before after scope-prods]
  (let [b-fts (:fact-types before)
        a-fts (:fact-types after)
        names (sort (set/union (set (keys b-fts)) (set (keys a-fts))))
        added (filterv #(and (contains? a-fts %) (not (contains? b-fts %))) names)
        removed (filterv #(and (contains? b-fts %) (not (contains? a-fts %))) names)
        scope-added (filterv #(scope-touched? after scope-prods %) added)
        scope-removed (filterv #(scope-touched? before scope-prods %) removed)]
    {:added (filterv (complement (set scope-added)) added)
     :removed (filterv (complement (set scope-removed)) removed)
     :changed (into (sorted-map)
                    (keep (fn [n]
                            (when (and (contains? b-fts n) (contains? a-fts n))
                              (let [b-anc (get-in b-fts [n :ancestors])
                                    a-anc (get-in a-fts [n :ancestors])]
                                (when (not= b-anc a-anc)
                                  [n {:added (-> a-anc (set/difference b-anc) sort vec)
                                      :removed (-> b-anc (set/difference a-anc) sort vec)}])))))
                    names)
     :scope (sort (concat scope-added scope-removed))}))

(defn- composed?
  "Whether `unit` (a `read-unit` value) is a composition, which records the
   source units it was built from."
  [unit]
  (= :compose (get-in unit [:manifest :analysis-run :mode])))

(defn- diff-units
  "`{:added [...] :removed [...] :changed {repo {:before … :after …}}}` over
   each manifest's `:analysis-run :units` (the source units a composed unit
   was built from), matched by `:repo`. `:added`/`:removed` are the repos
   supplying units on one side only; `:changed` repos whose `:variant` or
   `:sha` differs carry each side's entry.

   Nil unless both sides are compositions: any other unit records no source
   units, so against a composition every one of its units would read as added
   or removed."
  [before after]
  (when (and (composed? before) (composed? after))
    (let [by-repo (fn [unit]
                    (into {}
                          (map (juxt :repo identity))
                          (get-in unit [:manifest :analysis-run :units])))
          b-by-repo (by-repo before)
          a-by-repo (by-repo after)
          repos (sort (set/union (set (keys b-by-repo)) (set (keys a-by-repo))))]
      {:added (filterv #(and (contains? a-by-repo %) (not (contains? b-by-repo %))) repos)
       :removed (filterv #(and (contains? b-by-repo %) (not (contains? a-by-repo %))) repos)
       :changed (into (sorted-map)
                      (keep (fn [repo]
                              (when (and (contains? b-by-repo repo)
                                         (contains? a-by-repo repo))
                                (let [b-entry (get b-by-repo repo)
                                      a-entry (get a-by-repo repo)]
                                  (when (not= (select-keys b-entry [:variant :sha])
                                              (select-keys a-entry [:variant :sha]))
                                    [repo {:before b-entry :after a-entry}])))))
                      repos)})))

(defn- diff-edges
  "`{:gained [...] :lost [...]}` — dep-graph pairs present on exactly one
   side, excluding pairs touching a scope-only production (those are listed
   under `:scope`)."
  [before after scope-prods]
  (let [scoped? (fn [[up down]]
                  (or (contains? scope-prods up) (contains? scope-prods down)))
        gained (sort (set/difference (:edges after) (:edges before)))
        lost (sort (set/difference (:edges before) (:edges after)))]
    {:gained (filterv (complement scoped?) gained)
     :lost (filterv (complement scoped?) lost)}))

(defn- diff-scope
  "`{:namespaces {:only-before [...] :only-after [...]} :productions [...]
   :edges [...]}` — namespaces claimed on exactly one side, the productions
   sitting in them (from either side), and the edge pairs touching those
   productions (from either side)."
  [before after namespaces]
  (let [scope-nses (set/union (set (:only-before namespaces))
                              (set (:only-after namespaces)))
        productions (->> [before after]
                         (mapcat (comp keys :productions))
                         (filter (fn [n]
                                   (or (in-namespaces? (:productions before) scope-nses n)
                                       (in-namespaces? (:productions after) scope-nses n))))
                         distinct
                         sort
                         vec)
        scope-prods (set productions)
        edges (->> [before after]
                   (mapcat :edges)
                   (filter (fn [[up down]]
                             (or (contains? scope-prods up)
                                 (contains? scope-prods down))))
                   distinct
                   sort
                   vec)]
    {:namespaces namespaces
     :productions productions
     :edges edges}))

(defn diff
  "Diff two `read-unit` values — `before`, then `after`, matching
   `clara.explorer.artifacts.federate/diff`. A pure function of the two
   values. Reports:

    :before / :after  each side's provenance (sha, branch, working tree,
                      `:variant`, scope fields)
    :scope            namespaces claimed on only one side, with their
                      productions, edges, and fact types
    :productions      `:added`, `:removed`, and `:changed {name #{tag}}`
    :fact-types       added, removed, and `:ancestors` changes
    :units            when both sides are compositions, the source units
                      each was built from, matched by `:repo`; else nil
    :edges            dep-graph pairs `:gained` and `:lost`

   Shape skew is refused before any comparison. A diff of a unit against
   itself is empty in every key."
  [before after]
  (assert-same-shape! before after)
  (let [namespaces (scope-only-namespaces before after)
        scope-nses (set/union (set (:only-before namespaces))
                              (set (:only-after namespaces)))
        scope (diff-scope before after namespaces)
        scope-prods (set (:productions scope))
        productions (diff-productions before after scope-nses)
        fact-types (diff-fact-types before after scope-prods)]
    {:before (provenance-of (:manifest before))
     :after (provenance-of (:manifest after))
     :scope (assoc scope :fact-types (:scope fact-types))
     :productions productions
     :fact-types (select-keys fact-types [:added :removed :changed])
     :units (diff-units before after)
     :edges (diff-edges before after scope-prods)}))

;; ===========================================================================
;; text rendering
;; ===========================================================================

(defn- ->unit-handle
  "`repo`, or `repo@<variant path>` for a variant unit."
  [{:keys [repo variant]}]
  (str repo (when (seq variant) (str "@" (layout/variant->path variant)))))

(defn- ->unit-side-text
  "One side of a `:units :changed` entry as `<variant path> <short-sha>` — the
   variant path absent for a mainline source unit."
  [{:keys [variant sha]}]
  (let [variant-path (when (seq variant) (layout/variant->path variant))]
    (str (if variant-path (str variant-path " ") "")
         (layout/->short-sha sha))))

(defn- provenance-line
  "`before <handle> <sha> (<branch>, <working-tree>)`."
  [side {:keys [sha sha-short branch working-tree] :as prov}]
  (format "%s %s %s (%s, %s)"
          (name side)
          (->unit-handle prov)
          (or sha-short (layout/->short-sha sha))
          (or branch "no branch")
          (or working-tree "unknown")))

(defn- empty-diff?
  "Whether `d` reports no change in any key."
  [{:keys [productions fact-types edges scope units]}]
  (every? empty?
          [(:added productions)
           (:removed productions)
           (:changed productions)
           (:added fact-types)
           (:removed fact-types)
           (:changed fact-types)
           (:gained edges)
           (:lost edges)
           (get-in scope [:namespaces :only-before])
           (get-in scope [:namespaces :only-after])
           (:added units)
           (:removed units)
           (:changed units)]))

(defn- section-lines
  "One report section as lines: `header` followed by one `line-fn` line per
   item in `coll` — or `[]` when the section is empty, so `->text` keeps only
   the non-empty sections."
  [[header coll line-fn]]
  (if (seq coll)
    (into [header] (map line-fn) coll)
    []))

(defn ->text
  "The compact rendering of a `diff` value: two provenance lines, one count
   line per section, then each non-empty section — one line per name,
   `changed` lines carrying their tags. Two compositions add a `units:` count
   line and a `changed units:` section. A diff with no changes prints the
   provenance lines and `no differences`."
  [{:keys [before after productions fact-types edges scope units] :as d}]
  (let [count-lines (cond-> [(format "productions: %d added, %d removed, %d changed"
                                     (count (:added productions))
                                     (count (:removed productions))
                                     (count (:changed productions)))
                             (format "fact-types: %d added, %d removed, %d changed"
                                     (count (:added fact-types))
                                     (count (:removed fact-types))
                                     (count (:changed fact-types)))
                             (format "edges: %d gained, %d lost"
                                     (count (:gained edges))
                                     (count (:lost edges)))
                             (format "scope: %d namespaces, %d productions, %d edges, %d fact-types"
                                     (+ (count (get-in scope [:namespaces :only-before]))
                                        (count (get-in scope [:namespaces :only-after])))
                                     (count (:productions scope))
                                     (count (:edges scope))
                                     (count (:fact-types scope)))]
                      units
                      (conj (format "units: %d added, %d removed, %d changed"
                                    (count (:added units))
                                    (count (:removed units))
                                    (count (:changed units)))))
        sections [["added productions:" (:added productions)
                   (fn [n] (str "  + " n))]
                  ["removed productions:" (:removed productions)
                   (fn [n] (str "  - " n))]
                  ["changed productions:" (:changed productions)
                   (fn [[n tags]]
                     (format "  ~ %s [%s]" n (->> tags sort (map name) (str/join " "))))]
                  ["added fact-types:" (:added fact-types)
                   (fn [n] (str "  + " n))]
                  ["removed fact-types:" (:removed fact-types)
                   (fn [n] (str "  - " n))]
                  ["changed fact-types:" (:changed fact-types)
                   (fn [[n {:keys [added removed]}]]
                     (format "  ~ %s (+%d -%d ancestors)" n (count added) (count removed)))]
                  ["gained edges:" (:gained edges)
                   (fn [[up down]] (format "  + %s -> %s" up down))]
                  ["lost edges:" (:lost edges)
                   (fn [[up down]] (format "  - %s -> %s" up down))]
                  ["scope namespaces (only before):" (get-in scope [:namespaces :only-before])
                   (fn [n] (str "  " n))]
                  ["scope namespaces (only after):" (get-in scope [:namespaces :only-after])
                   (fn [n] (str "  " n))]
                  ["scope productions:" (:productions scope)
                   (fn [n] (str "  " n))]
                  ["scope edges:" (:edges scope)
                   (fn [[up down]] (format "  %s -> %s" up down))]
                  ["scope fact-types:" (:fact-types scope)
                   (fn [n] (str "  " n))]
                  ["changed units:" (:changed units)
                   (fn [[repo {:keys [before after]}]]
                     (format "  ~ %s %s -> %s" repo
                             (->unit-side-text before)
                             (->unit-side-text after)))]]]
    (str/join "\n"
              (cond-> (into (into [(provenance-line :before before)
                                   (provenance-line :after after)]
                                  count-lines)
                            (mapcat section-lines)
                            sections)
                (empty-diff? d) (conj "no differences")))))

;; ===========================================================================
;; one production's before/after
;; ===========================================================================

(defn- find-production-name
  "The production `name*` names: an exact hit, else the unique key containing
   it as a substring. Matching is `layout/resolve-key`; here a non-singleton
   answer throws, naming the candidates, so `rule-detail` stays a pure
   function."
  [productions name*]
  (let [{:keys [exact candidates]} (layout/resolve-key productions name*)]
    (cond
      exact exact
      (= 1 (count candidates)) (first candidates)
      (seq candidates) (throw (ex-info (format "Ambiguous production %s — %d matches: %s"
                                               (pr-str name*) (count candidates)
                                               (str/join ", " candidates))
                                       {:name name* :candidates candidates}))
      :else (throw (ex-info (format "No production matches %s" (pr-str name*))
                            {:name name*})))))

(defn rule-detail
  "One production's before/after across two units — `read-unit` values, or
   `{:productions …}` maps holding `read-productions` output:
   `{:name … :tags […] :before {…} :after {…}}`, either side nil when the
   production exists only on the other. Substring matching, as the report's
   `rule` subcommand does."
  [before after name*]
  (let [productions (into (sorted-map)
                          (merge (:productions before) (:productions after)))
        found (find-production-name productions name*)
        b (get-in before [:productions found])
        a (get-in after [:productions found])]
    {:name found
     :tags (if (and b a) (vec (production-tags b a)) [])
     :before b
     :after a}))

(defn rule-detail-text
  "A `rule-detail` value as text: the name and tags, then each changed
   field's before and after — including both `:rhs-form` texts. A production
   on one side only names that side and stops there."
  [{rule-name :name :keys [tags before after]}]
  (let [header (cond
                 (nil? before) (format "%s (only in after)" rule-name)
                 (nil? after) (format "%s (only in before)" rule-name)
                 (seq tags) (format "%s [%s]" rule-name
                                    (->> tags (map name) (str/join " ")))
                 :else (format "%s (unchanged)" rule-name))
        field-lines (when (and before after)
                      (mapcat (fn [[tag key set?]]
                                (when (not= (compared-value before key set?)
                                            (compared-value after key set?))
                                  [(format "%s:" (name tag))
                                   (format "  before: %s" (pr-str (get before key)))
                                   (format "  after:  %s" (pr-str (get after key)))]))
                              compared-fields))]
    (str/join "\n" (into [header] field-lines))))
