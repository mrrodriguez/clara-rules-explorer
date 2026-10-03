(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.diff
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

(defn- read-edn-or-nil
  "The EDN value in file `f`, or nil when it cannot be read."
  [f]
  (try
    (edn/read-string {:default (fn [_tag v] v)} (slurp (str f)))
    (catch Exception _ nil)))

(defn- read-edn-or-throw
  "`what` from file `f`, throwing when it cannot be read.
   The throwing read; `read-edn-or-nil` returns nil instead."
  [f what]
  (or (read-edn-or-nil f)
      (throw (ex-info (format "Cannot diff: missing %s — %s" what (str f))
                      {:file (str f) :what what}))))

(defn- read-layer-stack
  "Every layer file in `dir` that exists, in fold order, as `[layer-id
  annotations]` — what `merged-annotations.edn`'s references resolve against.
   Fold order is `layout/layer-artifacts`' key order (an `array-map`, lowest
   precedence first), so this must keep reading its keys in map order."
  [dir]
  (into []
        (keep (fn [role]
                (let [m (read-edn-or-nil (str dir "/" (get layout/artifact-files role)))]
                  (when (map? (:annotations m))
                    [(:id m) (:annotations m)]))))
        (keys layout/layer-artifacts)))

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
  "Refuse a diff across slim shapes, as `registry/assert-compatible!`
   refuses a merge: when `:slim :dropped` differs the two units do not hold
   the same keys and every comparison would be suspect. Names both shapes."
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

(defn- production-tags
  "The change tags for a production present on both sides: one tag per
   compared field whose value differs. Type lists compare as sets."
  [b a]
  (into (sorted-set)
        (keep (fn [[tag kf set?]]
                (let [bv (get b kf)
                      av (get a kf)]
                  (when (not= (if set? (set bv) bv)
                              (if set? (set av) av))
                    tag))))
        [[:kind :kind false]
         [:lhs-types :lhs-types true]
         [:lhs :lhs false]
         [:insert-types :insert-types true]
         [:retract-types :retract-types true]
         [:rhs :rhs-form false]
         [:doc :doc false]
         [:props :props false]
         [:resolution :resolution false]
         [:unit :unit false]]))

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

(defn- diff-fact-types
  "`{:added [...] :removed [...] :changed {name {:added [...] :removed
   [...]}}}` — which types appeared or disappeared, and whose `:ancestors`
   changed."
  [before after]
  (let [b-fts (:fact-types before)
        a-fts (:fact-types after)
        names (sort (set/union (set (keys b-fts)) (set (keys a-fts))))]
    {:added (filterv #(and (contains? a-fts %) (not (contains? b-fts %))) names)
     :removed (filterv #(and (contains? b-fts %) (not (contains? a-fts %))) names)
     :changed (into (sorted-map)
                    (keep (fn [n]
                            (when (and (contains? b-fts n) (contains? a-fts n))
                              (let [b-anc (get-in b-fts [n :ancestors])
                                    a-anc (get-in a-fts [n :ancestors])]
                                (when (not= b-anc a-anc)
                                  [n {:added (-> a-anc (set/difference b-anc) sort vec)
                                      :removed (-> b-anc (set/difference a-anc) sort vec)}])))))
                    names)}))

(defn- edge-endpoint-names
  "Both production names of an `[upstream downstream]` pair."
  [[up down]]
  [up down])

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
                   (filter (fn [pair]
                             (let [[up down] (edge-endpoint-names pair)]
                               (or (contains? scope-prods up)
                                   (contains? scope-prods down)))))
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
                      productions and edges
    :productions      `:added`, `:removed`, and `:changed {name #{tag}}`
    :fact-types       added, removed, and `:ancestors` changes
    :edges            dep-graph pairs `:gained` and `:lost`

   Shape skew is refused before any comparison. A diff of a unit against
   itself is empty in every key."
  [before after]
  (assert-same-shape! before after)
  (let [namespaces (scope-only-namespaces before after)
        scope-nses (set/union (set (:only-before namespaces))
                              (set (:only-after namespaces)))
        scope (diff-scope before after namespaces)
        productions (diff-productions before after scope-nses)]
    {:before (provenance-of (:manifest before))
     :after (provenance-of (:manifest after))
     :scope scope
     :productions productions
     :fact-types (diff-fact-types before after)
     :edges (diff-edges before after (set (:productions scope)))}))

;; ===========================================================================
;; text rendering
;; ===========================================================================

(defn- short-sha
  "First 7 chars of `sha`, for one-line provenance."
  [sha]
  (let [s (str sha)]
    (if (> (count s) 7) (subs s 0 7) s)))

(defn- unit-handle
  "`repo`, or `repo@<variant path>` for a variant unit."
  [{:keys [repo variant]}]
  (str repo (when (seq variant) (str "@" (layout/variant->path variant)))))

(defn- provenance-line
  "`before <handle> <sha> (<branch>, <working-tree>)`."
  [side {:keys [sha sha-short branch working-tree] :as prov}]
  (format "%s %s %s (%s, %s)"
          (name side)
          (unit-handle prov)
          (or sha-short (short-sha sha))
          (or branch "no branch")
          (or working-tree "unknown")))

(defn- empty-diff?
  "Whether `d` reports no change in any key."
  [{:keys [productions fact-types edges scope]}]
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
           (get-in scope [:namespaces :only-after])]))

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
   `changed` lines carrying their tags. A diff with no changes prints the
   provenance lines and `no differences`."
  [{:keys [before after productions fact-types edges scope] :as d}]
  (str/join "\n"
            (cond-> (into [(provenance-line :before before)
                           (provenance-line :after after)
                           (format "productions: %d added, %d removed, %d changed"
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
                           (format "scope: %d namespaces, %d productions, %d edges"
                                   (+ (count (get-in scope [:namespaces :only-before]))
                                      (count (get-in scope [:namespaces :only-after])))
                                   (count (:productions scope))
                                   (count (:edges scope)))]
                          (mapcat section-lines)
                          [["added productions:" (:added productions)
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
                            (fn [[up down]] (format "  %s -> %s" up down))]])
              (empty-diff? d) (conj "no differences"))))

;; ===========================================================================
;; one production's before/after
;; ===========================================================================

(def ^:private detail-fields
  "Compared production fields in display order: `[label key]`."
  [[:kind :kind]
   [:ns :ns]
   [:lhs-types :lhs-types]
   [:lhs :lhs]
   [:insert-types :insert-types]
   [:retract-types :retract-types]
   [:rhs-form :rhs-form]
   [:doc :doc]
   [:props :props]
   [:resolution :resolution]
   [:unit :unit]])

(defn- find-production-name
  "The production `name*` names: an exact hit, else the unique key containing
   it as a substring. Throws naming the candidates when there is no unique
   answer."
  [productions name*]
  (if (contains? productions name*)
    name*
    (let [cands (->> productions
                     keys
                     (filter #(str/includes? % name*))
                     sort
                     vec)]
      (cond
        (= 1 (count cands)) (first cands)
        (seq cands) (throw (ex-info (format "Ambiguous production %s — %d matches: %s"
                                            (pr-str name*) (count cands)
                                            (str/join ", " cands))
                                    {:name name* :candidates cands}))
        :else (throw (ex-info (format "No production matches %s" (pr-str name*))
                              {:name name*}))))))

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
   field's before and after — including both `:rhs-form` texts."
  [{rule-name :name :keys [tags before after]}]
  (let [lines (volatile! [(if (seq tags)
                            (->> tags (map name) (str/join " ") (format "%s [%s]" rule-name))
                            (format "%s (unchanged)" rule-name))])
        emit! (fn [& ls] (vswap! lines into ls))]
    (cond
      (nil? before) (emit! "(only in after)")
      (nil? after) (emit! "(only in before)"))
    (doseq [[label kf] detail-fields
            :let [bv (get before kf)
                  av (get after kf)]
            :when (not= bv av)]
      (emit! (format "%s:" (name label)))
      (emit! (format "  before: %s" (pr-str bv)))
      (emit! (format "  after:  %s" (pr-str av))))
    (str/join "\n" @lines)))
