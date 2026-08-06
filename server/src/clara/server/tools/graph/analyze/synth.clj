(ns clara.server.tools.graph.analyze.synth
  "Source synthesis for namespaces whose source is not on the classpath
   (jars without sources, eval'd code).

   When a rule-owning namespace has no classpath-accessible source file,
   the analysis must reconstruct an `(ns ...)` form from the live Namespace
   object.  That reconstructed form is then combined with synthetic snippet
   defs — one per rule RHS — to produce the source text that `clj-kondo`
   analyzes.  This namespace handles both stages:

   1. `reconstruct-ns-source` — build a valid Clojure `(ns ...)` form from
      a live Namespace object, emitting only deviations from the defaults
      that a fresh `ns` would get.
   2. `synthesize-ns-source` — combine the real or reconstructed source
      with rule-snippet defs and produce the metadata (tag→production
      mapping, offset) that `prune-and-rename-analysis` consumes."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; reconstruct-ns-source helpers
;; ---------------------------------------------------------------------------

(defn- var-ns-name
  "Returns the namespace name of a var's metadata `:ns`."
  [v]
  (ns-name (:ns (meta v))))

(defn- var-name
  "Returns the name symbol from a var's metadata."
  [v]
  (:name (meta v)))

(defn core-deviations
  "Returns `{:excluded [...] :renamed {...}}` describing how the given
   namespace deviates from the default `clojure.core` mappings.  Excluded
   are `clojure.core` publics not present in the ns; renamed are entries
   whose local symbol differs from the var's own name."
  [nsobj]
  (let [core-publics      (->> 'clojure.core ns-publics keys set)
        core-entries      (into {}
                                (filter (fn [[_ v]]
                                          (and (var? v)
                                               (= 'clojure.core (var-ns-name v)))))
                                (ns-map nsobj))
        present-core-names (into #{} (map (comp var-name val)) core-entries)]
    {:excluded (->> core-publics (remove present-core-names) sort vec)
     :renamed  (into {}
                     (filter (fn [[sym v]] (not= sym (var-name v))))
                     core-entries)}))

(defn build-require-clauses
  "Builds a sorted vector of `:require` clause vectors from the namespace's
   aliases and refers, excluding `clojure.core` refers."
  [nsobj]
  (let [alias-clauses (->> (ns-aliases nsobj)
                           (mapv (fn [[a target]] [(ns-name target) :as a])))
        refers        (into []
                            (comp (remove #(= 'clojure.core
                                              (var-ns-name (val %)))))
                            (ns-refers nsobj))
        refer-groups  (group-by (fn [[_ v]] (var-ns-name v)) refers)
        refer-clauses (mapv (fn [[target kvs]]
                              [target :refer (vec (sort (map first kvs)))])
                            refer-groups)]
    (->> (concat alias-clauses refer-clauses)
         (sort-by (comp str first))
         vec)))

(defn build-import-clauses
  "Builds a sorted vector of `:import` clause vectors from the namespace's
   imports, excluding `java.lang.*` (which are automatic in any new `ns`)."
  [nsobj]
  (let [imports       (into []
                            (comp (remove #(.startsWith (.getName ^Class (val %))
                                                        "java.lang.")))
                            (ns-imports nsobj))
        import-groups (group-by (fn [[_ ^Class c]] (.getPackageName c)) imports)]
    (->> import-groups
         (map (fn [[pkg kvs]]
                (->> kvs
                     (map first)
                     sort
                     (into [(symbol pkg)]))))
         (sort-by (comp str first))
         vec)))

(defn unmapped-default-imports
  "Returns the sorted vector of default `java.lang.*` import symbols missing
   from the given namespace (possible only via dynamic `ns-unmap`)."
  [nsobj]
  (let [imported (-> nsobj ns-imports keys set)]
    (->> clojure.lang.RT/DEFAULT_IMPORTS
         keys
         (remove imported)
         sort
         vec)))

(defn reconstruct-ns-source
  "Builds a synthetic source string containing only an `(ns ...)` form
   reconstructed from the live Namespace object — used for namespaces whose
   source is not on the classpath (jars without sources, eval'd code).

   `clojure.core` refers and `java.lang.*` imports are automatic in any new
   `ns`, so only deviations are emitted: a `:refer-clojure` clause with
   `:exclude` / `:rename` when the live ns deviates from `clojure.core`
   defaults, and trailing `(ns-unmap ...)` forms in the rare case a default
   `java.lang` import is missing (only possible via dynamic `ns-unmap` in
   the original ns)."
  [ns-sym]
  (let [nsobj            (the-ns ns-sym)
        {:keys [excluded renamed]} (core-deviations nsobj)
        require-clauses   (build-require-clauses nsobj)
        import-clauses    (build-import-clauses nsobj)
        unmapped-defaults (unmapped-default-imports nsobj)
        refer-clojure-clause (when (or (seq excluded) (seq renamed))
                               (concat (list :refer-clojure)
                                       (when (seq excluded) (list :exclude excluded))
                                       (when (seq renamed)  (list :rename renamed))))
        require-clause (when (seq require-clauses)
                         (cons :require require-clauses))
        import-clause (when (seq import-clauses)
                        (cons :import import-clauses))
        ns-form (concat (list 'ns ns-sym)
                        (remove nil? [refer-clojure-clause require-clause import-clause]))]
    (str (pr-str ns-form) "\n"
         (str/join (map #(format "(ns-unmap (the-ns '%s) '%s)\n" ns-sym %)
                        unmapped-defaults)))))

(defn synthesize-ns-source
  "Builds the combined source for a rule-owning namespace: the real source
   (or a reconstructed `ns` form when none is on the classpath) plus one
   synthetic snippet per rule, `(def <tag> (fn [] <pr-str-of-rhs>))`, each
   on its own line.

   `base-source-fn` is a `(fn [ns-sym] -> source-str-or-nil)` — typically
   `#(some-> (find-ns-resource %) slurp)`.  `normalize-key-fn` normalizes a
   production name (string→symbol identity).

   Snippet tags are deterministic ordinals — never raw production names
   (which may not be legal `def` symbols) — with an explicit
   tag → production-name mapping for attribution.

   Returns `{:source combined-str
             :offset base-source-line-count
             :tag->production {tag-sym production-local-name-sym}}`."
  [ns-sym productions base-source-fn normalize-key-fn]
  (let [base-source (or (base-source-fn ns-sym)
                        (reconstruct-ns-source ns-sym))
        offset (count (str/split-lines base-source))
        snippets (map-indexed
                  (fn [idx production]
                    (let [tag (symbol (str "__clara_explorer_rule_" idx "__"))
                          local-name (-> production :name normalize-key-fn name symbol)
                          rhs-str (binding [*print-length* nil
                                            *print-level* nil]
                                    (pr-str (:rhs production)))]
                      {:tag tag
                       :local-name local-name
                       :form (format "(def %s (fn [] %s))" tag rhs-str)}))
                  productions)]
    {:source (str base-source "\n" (str/join "\n" (map :form snippets)) "\n")
     :offset offset
     :tag->production (into {} (map (juxt :tag :local-name)) snippets)}))
