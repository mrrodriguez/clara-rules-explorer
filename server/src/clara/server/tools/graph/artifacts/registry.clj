(ns clara.server.tools.graph.artifacts.registry
  "The registry: discover and read N artifact sets under one root, as a value.

  One artifact set — the thing
  `clara.server.tools.graph.artifacts.store` addresses as `{:root :repo}` — is a
  *unit*: a directory holding the files in `layout/artifact-files`. A registry
  is a root directory and the units discoverable beneath it, named by their path
  relative to the root. Hosts accumulate many: one per source repo of a rulebase
  composed from several, one per branch variant under review, one per captured
  session. The walk knows none of those concepts — a directory is a unit iff it
  holds `rules-inspect-manifest.edn`, the one artifact every complete set has and
  the one that says what the rest of the set is.

  The single reserved segment is `branches`: a directory of that name is read as
  the variant holder `store/branches-subdir` already defines, and its children
  become `:branch` labels on the parent unit rather than units of their own.

  The library discovers and reads. It never decides *which* sets belong together
  or *what a set means* — every entry point takes the selection explicitly, the
  same line `store/get-out-dir` holds for `:root`: mechanism here, policy at the
  caller."
  (:require
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.parts :as parts]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [schema.core :as s])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; Registry value
;; ===========================================================================

(defrecord Registry [root units units-by-key cache])

(defn unit-key
  "The string handle for a unit ref: `<repo>[@<branch>]`. A `UnitRef` map is not
  a comparable map key under the library's own `sorted-map` convention, so maps
  keyed by unit use this."
  [{:keys [repo branch]}]
  (str repo (when (seq branch) (str "@" branch))))

(defn unit-ref
  "The `UnitRef` projection of a unit info map."
  [{:keys [repo branch]}]
  (cond-> {:repo repo}
    (some? branch) (assoc :branch branch)))

(defn units
  "The units of `registry`, as `UnitRef`s — the caller-facing selection shape.
  See `unit-info` for what `discover` recorded about each."
  [^Registry registry]
  (mapv unit-ref (:units registry)))

(defn unit-info
  "The recorded info for `unit` (its artifacts, slim shape, layer ids, manifest
  head), or nil when the registry does not hold it."
  [^Registry registry unit]
  (get (:units-by-key registry) (unit-key unit)))

(defn same-registry?
  "Structural equality of two registries, ignoring the memoization `:cache` atom
  — the one field that keeps the record from being a pure value. Two registries
  over the same `:root` and `:units` answer the same reads, whether or not
  either has cached one yet."
  [^Registry a ^Registry b]
  (and (= (:root a) (:root b))
       (= (:units a) (:units b))))

;; ===========================================================================
;; discovery
;; ===========================================================================

(defn- canonical-path
  ^String [^File f]
  (.getCanonicalPath f))

(defn- under-root?
  [^String root-path ^String dir-path]
  (or (= root-path dir-path)
      (str/starts-with? dir-path (str root-path File/separator))))

(defn- relative-segments
  "Path segments of `dir` under `root`, or nil when `dir` is not under `root`."
  [root ^File dir]
  (let [rp (canonical-path (io/file root))
        dp (canonical-path dir)]
    (when (under-root? rp dp)
      (-> (subs dp (min (count dp) (count rp)))
          (str/split (re-pattern (java.util.regex.Pattern/quote File/separator)))
          (->> (remove str/blank?))
          vec))))

(defn- manifest-file
  ^File [^File dir]
  (io/file dir (:manifest layout/artifact-files)))

(defn- unit-dir?
  "A directory is a unit iff it holds `rules-inspect-manifest.edn`."
  [^File dir]
  (.isFile (manifest-file dir)))

(defn- ->unit-ref
  "Segments → `UnitRef`. The first `branches` segment splits repo from branch;
  without one the whole path is the repo."
  [segments]
  (let [bi (first (keep-indexed (fn [i seg] (when (= store/branches-subdir seg) i))
                                segments))]
    (if bi
      {:repo (str/join "/" (subvec segments 0 bi))
       :branch (str/join "/" (subvec segments (inc bi)))}
      {:repo (str/join "/" segments)})))

(defn- discover-unit-refs
  "Every unit under `root`, mainline and `branches/` variants alike, as refs.
  Directories are walked arbitrarily deep, so a host groups its units however it
  likes."
  [root]
  (let [root-file (io/file root)]
    (when-not (.isDirectory root-file)
      (throw (ex-info (format "Registry root is not a directory: %s" root) {:root root})))
    (->> (file-seq root-file)
         (filter #(.isDirectory ^File %))
         (filter unit-dir?)
         (keep #(some-> (relative-segments root %) ->unit-ref))
         (sort-by unit-key)
         vec)))

;; ===========================================================================
;; unit info
;; ===========================================================================

(defn- read-manifest-file
  [^File dir]
  (edn-io/read-edn-file (manifest-file dir)))

(defn- read-meta-file
  [^File dir]
  (edn-io/read-edn-file (io/file dir
                                 (:rulebase-analysis layout/artifact-files)
                                 (:meta parts/part-files))))

(defn- ->unit-info
  "What `discover` records per unit: the ref, the resolved dir, present
  artifacts, the slim `:dropped` shape, the manifest's layer ids, and the
  manifest head (`:created`, `:sha`, `:history` head)."
  [root ref]
  (let [dir (store/get-out-dir (assoc ref :root root))
        present (into #{}
                      (keep (fn [[k filename]]
                              (when (.exists (io/file dir filename)) k)))
                      store/unit-artifact-files)
        manifest (read-manifest-file (io/file dir))
        meta (read-meta-file (io/file dir))]
    (cond-> (assoc (unit-ref ref)
                   :dir dir
                   :artifacts present)
      (some? (:slim meta))
      (assoc :slim-dropped (into #{} (get-in meta [:slim :dropped])))

      (some? manifest)
      (assoc :layer-ids (get-in manifest [:analysis-run :layer-ids])
             :manifest-head {:created (:created manifest)
                             :sha (get-in manifest [:source :sha])
                             :history (vec (take 1 (:history manifest)))}))))

(s/defn ->registry :- Registry
  "A registry of the explicit `:units` under `:root`. `units` is a vector of
  `UnitRef`s; the caller names them, the walk does not run."
  [{:keys [root units]} :- {:root s/Str
                            :units [schema/UnitRef]}]
  (when (str/blank? root)
    (throw (ex-info "registry requires :root (an artifact root)" {})))
  (let [infos (mapv #(->unit-info root %) units)]
    (->Registry root infos (into {} (map (fn [i] [(unit-key i) i])) infos) (atom {}))))

(s/defn discover :- Registry
  "Discover every unit under `:root` — a directory holding
  `rules-inspect-manifest.edn`, with `branches/` children read as `:branch`
  variants of their parent."
  [{:keys [root]} :- {:root s/Str}]
  (->registry {:root root :units (discover-unit-refs root)}))

;; ===========================================================================
;; reads (per-unit, lazy)
;; ===========================================================================

(defn- ->opts
  [^Registry registry unit]
  (cond-> {:root (:root registry) :repo (:repo unit)}
    (some? (:branch unit)) (assoc :branch (:branch unit))))

(defn- memo-read
  "Read `unit` once, memoizing the result on the registry. Absent artifacts read
  as nil, and nil is cached like any other value."
  [^Registry registry k f]
  (let [cache (:cache registry)]
    (if (contains? @cache k)
      (get @cache k)
      (let [v (f)]
        (swap! cache assoc k v)
        v))))

(s/defn read-analysis :- (s/maybe schema/RulebaseAnalysis)
  "The slim `RulebaseAnalysis` of `unit`, re-joined from its parts. Nil when the
  unit has no `merged-rulebase-analysis/` directory."
  [registry :- Registry
   unit :- schema/UnitRef]
  (memo-read registry [:analysis (unit-key unit)]
             #(when-let [ps (store/read-analysis-parts (->opts registry unit))]
                (parts/<-parts ps))))

(s/defn read-digest :- (s/maybe schema/RulebaseAnalysisDigest)
  "The `rulebase-analysis-digest.edn` of `unit`, or nil when absent."
  [registry :- Registry
   unit :- schema/UnitRef]
  (memo-read registry [:digest (unit-key unit)]
             #(edn-io/read-edn-file
               (io/file (store/get-out-dir (->opts registry unit))
                        (:rulebase-analysis-digest layout/artifact-files)))))

(s/defn read-manifest :- (s/maybe {s/Keyword s/Any})
  "The `rules-inspect-manifest.edn` of `unit`, or nil when absent."
  [registry :- Registry
   unit :- schema/UnitRef]
  (memo-read registry [:manifest (unit-key unit)]
             #(edn-io/read-edn-file (store/get-artifact-file :manifest (->opts registry unit)))))

(s/defn read-annotations :- (s/maybe schema/MergedAnnotations)
  "The merged annotations of `unit`, layer references expanded. Nil when the
  unit has no `merged-annotations.edn`."
  [registry :- Registry
   unit :- schema/UnitRef]
  (memo-read registry [:annotations (unit-key unit)]
             #(store/read-merged-annotations (->opts registry unit))))

;; ===========================================================================
;; compatibility
;; ===========================================================================

(defn- shape-sort-key
  "Deterministic sort key for a slim `:dropped` shape, so ties in the
  compatibility report's majority selection cannot depend on set iteration."
  [shape]
  (pr-str (some-> shape (->> (into (sorted-set))))))

(defn- all-artifacts [_unit-info]
  (into #{} (keys store/unit-artifact-files)))

(s/defn compatibility-report
  "The one question a merge has to answer first: do the selected units have the
  same slim shape? Shape is the `:slim :dropped` key set — the vocabulary a
  merge would otherwise silently union. Also reports, per unit, which artifacts
  are missing.

  With one arity, the whole registry; with two, the caller's selection. An empty
  selection is trivially compatible (no units disagree)."
  ([registry :- Registry]
   (compatibility-report registry (units registry)))
  ([registry :- Registry
    selection :- [schema/UnitRef]]
   (let [infos (into []
                     (keep #(unit-info registry %))
                     selection)
         by-shape (group-by :slim-dropped infos)
         ;; The majority shape is the most common dropped set; on ties the
         ;; sorted shape wins, which keeps the report deterministic.
         [majority-shape _] (or (first (sort-by (fn [[shape infos]]
                                                  [(- (count infos)) (shape-sort-key shape)])
                                                by-shape))
                                [nil nil])
         shape-mismatch (->> infos
                             (remove #(= (:slim-dropped %) majority-shape))
                             (mapv unit-ref))
         dropped-key-sets (into (sorted-map)
                                (map (fn [info]
                                       [(unit-key info) (:slim-dropped info)]))
                                infos)
         missing-artifacts (into (sorted-map)
                                 (keep (fn [info]
                                         (let [missing (set/difference (all-artifacts info)
                                                                       (:artifacts info))]
                                           (when (seq missing)
                                             [(unit-key info) missing]))))
                                 infos)]
     {:compatible? (empty? shape-mismatch)
      :majority-shape majority-shape
      :shape-mismatch shape-mismatch
      :dropped-key-sets dropped-key-sets
      :missing-artifacts missing-artifacts})))

(s/defn assert-compatible! :- s/Bool
  "Throw when `selection` does not share one slim shape, naming the differing
  units and key sets. Returns true on success."
  [registry :- Registry
   selection :- [schema/UnitRef]]
  (let [report (compatibility-report registry selection)]
    (when-not (:compatible? report)
      (throw (ex-info (format "Cannot merge registry units with differing slim shapes: %s"
                              (pr-str (mapv unit-key (:shape-mismatch report))))
                      report)))
    true))
