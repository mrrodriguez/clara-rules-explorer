(ns clara.server.tools.graph.ns-deps
  "Static namespace dependencies shared by `core` and `analyze.synth`.

   Data-first builders over a live `Namespace` object: each `->ns-…` fn
   returns plain data (vectors/maps of symbols); `analyze.synth` projects
   that data into `(ns …)` clause syntax, and `core/->rulebase-analysis`
   composes it into the top-level `:ns-deps` entry via `->ns-deps`.
   The leaf requires only `clojure.java.io`, `clojure.string` and
   `schema.core` — never `analyze` or `core` — so both can depend on it
   without a cycle (see rb-ana-ns-deps-plan.md §7)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]))

(s/defschema NsRequireEntry
  "One `:require` mapping.  `:refers` is the sorted vector of referred
   symbols.  A refer-all spec (`:refer :all`, bare `:use`) is expanded to
   the required namespace's public vars, so the vector is always homogeneous;
   a spec that refers nothing produces no entry at all."
  {:ns-name-sym s/Symbol
   :refers [s/Symbol]})

(s/defschema NsAliasEntry
  {:ns-name-sym s/Symbol
   :alias-sym s/Symbol})

(s/defschema NsDepEntry
  {:require [NsRequireEntry]
   :aliases [NsAliasEntry]
   :imports [s/Symbol]
   :refer-clojure {:excludes [s/Symbol]
                   :renames {s/Symbol s/Symbol}}
   :unmapped-default-imports [s/Symbol]})

(s/defschema NsDepsOptions
  "Options for `->ns-deps` — checked as the function's argument schema
   (test-time via `schema.test/validate-schemas`, not at runtime).
   `:base-source-fn` is `(fn [ns-sym] -> source-str-or-nil)`; when absent
   the classpath (`io/resource`, `.clj`/`.cljc`) is consulted."
  {:ns-syms [s/Symbol]
   (s/optional-key :base-source-fn) (s/maybe (s/=> (s/maybe s/Str) s/Symbol))})

(s/defschema NsDepsMap
  "`->ns-deps` return: `{ns-name-sym NsDepEntry, ...}` as a sorted-map."
  {s/Symbol NsDepEntry})

;; ---------------------------------------------------------------------------
;; var-meta readers
;; ---------------------------------------------------------------------------

(defn- get-var-ns-name
  "Returns the namespace name of a var's metadata `:ns`."
  [v]
  (ns-name (:ns (meta v))))

(defn- get-var-name
  "Returns the name symbol from a var's metadata."
  [v]
  (:name (meta v)))

;; ---------------------------------------------------------------------------
;; data builders (live Namespace object path)
;; ---------------------------------------------------------------------------

(defn ->ns-required
  "Returns a sorted vector of `[{:ns-name-sym … :refers […] } …]` — one entry
   per namespace with at least one referred var (`clojure.core` excluded, as
   with the `:require` clause projection). `:refers` is sorted; an entry
   exists only when the ns is referred, so it is never empty."
  [nsobj]
  (let [refers (into []
                     (comp (remove #(= 'clojure.core
                                       (get-var-ns-name (val %)))))
                     (ns-refers nsobj))
        refer-groups (group-by (fn [[_ v]] (get-var-ns-name v)) refers)]
    (->> refer-groups
         (mapv (fn [[target kvs]]
                 {:ns-name-sym target
                  :refers (->> kvs (map first) sort vec)}))
         (sort-by (comp str :ns-name-sym))
         vec)))

(defn ->ns-aliases
  "Returns a sorted vector of `[{:ns-name-sym … :alias-sym …} …]` from
   `ns-aliases`, sorted by `(str alias-sym)`."
  [nsobj]
  (->> (ns-aliases nsobj)
       (mapv (fn [[a target]]
               {:ns-name-sym (ns-name target)
                :alias-sym a}))
       (sort-by (comp str :alias-sym))
       vec))

(defn- default-import?
  "True when `simple-name` resolves to exactly `cls` in
   `RT/DEFAULT_IMPORTS`.  Compares the fully-qualified class (package
   included) rather than the simple name alone, so an explicitly imported
   class whose simple name collides with a default import is preserved."
  [simple-name ^Class cls]
  (when-let [^Class default-cls (get clojure.lang.RT/DEFAULT_IMPORTS simple-name)]
    (= default-cls cls)))

(defn ->ns-imports
  "Returns a sorted vector of fully-qualified class-name symbols imported by
   the namespace, *after* excluding exactly the `RT/DEFAULT_IMPORTS` set
   (full-class comparison, so explicitly imported non-default classes —
   e.g. `java.lang.StackWalker`, or any class whose simple name collides
   with a default — are preserved)."
  [nsobj]
  (->> (ns-imports nsobj)
       (remove (fn [[simple-name ^Class cls]] (default-import? simple-name cls)))
       (mapv (fn [[_ ^Class c]] (symbol (.getName ^Class c))))
       sort
       vec))

(defn ->ns-refer-clojure
  "Returns `{:excludes [...] :renames {...}}` describing how the given
   namespace deviates from the default `clojure.core` mappings. Excludes
   are `clojure.core` publics not present in the ns; renames map the local
   symbol to the fully-qualified core var symbol (e.g. `{my-map
   clojure.core/map}`). Both halves always present (empty vec / empty map
   when no deviation)."
  [nsobj]
  (let [core-publics (->> 'clojure.core ns-publics keys set)
        core-entries (into {}
                           (filter (fn [[_ v]]
                                     (and (var? v)
                                          (= 'clojure.core (get-var-ns-name v)))))
                           (ns-map nsobj))
        present-core-names (into #{} (map (comp get-var-name val)) core-entries)]
    {:excludes (->> core-publics (remove present-core-names) sort vec)
     :renames (into {}
                    (comp (remove (fn [[sym v]] (= sym (get-var-name v))))
                          (map (fn [[sym v]]
                                 [sym (symbol (str (get-var-ns-name v))
                                              (str (get-var-name v)))])))
                    core-entries)}))

(defn ->ns-unmapped-default-imports
  "Returns the sorted vector of default `java.lang.*` import symbols missing
   from the given namespace (possible only via dynamic `ns-unmap`)."
  [nsobj]
  (let [imported (-> nsobj ns-imports keys set)]
    (->> clojure.lang.RT/DEFAULT_IMPORTS
         keys
         (remove imported)
         sort
         vec)))

(s/defn ->ns-deps-entry :- NsDepEntry
  "Composes the five data fns into one `NsDepEntry` for a live `Namespace`
   object (the runtime-object path of `->ns-deps`)."
  [nsobj]
  {:require (->ns-required nsobj)
   :aliases (->ns-aliases nsobj)
   :imports (->ns-imports nsobj)
   :refer-clojure (->ns-refer-clojure nsobj)
   :unmapped-default-imports (->ns-unmapped-default-imports nsobj)})

;; ---------------------------------------------------------------------------
;; ns-header parser (classpath-source path)
;; ---------------------------------------------------------------------------

(defn- ns->resource-base
  "Maps a namespace symbol to a classpath resource path (dots to slashes,
   hyphens to underscores — how Clojure resolves ns to file)."
  [ns-sym]
  (-> (name ns-sym)
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- find-ns-resource
  "Finds the classpath resource for a namespace symbol (`.clj`/`.cljc`)."
  [ns-sym]
  (let [base (ns->resource-base ns-sym)]
    (or (io/resource (str base ".clj"))
        (io/resource (str base ".cljc")))))

(defn- default-base-source-fn
  "Reads the classpath source for `ns-sym`, or nil when absent/unreadable
   (the caller falls back to the runtime path)."
  [ns-sym]
  (try
    (some-> (find-ns-resource ns-sym) slurp)
    (catch Exception _ nil)))

(def ^:private ns-read-eof
  (Object.))

(defn- read-ns-form
  "Reads the first form of `source-str` (`:read-cond :allow`, `:features
   #{:clj}` — the way kondo effectively sees our `:lang :clj` analysis).
   Returns the `(ns …)` form, or nil when the head is not an ns form."
  [source-str]
  (try
    (let [form (read-string {:read-cond :allow
                             :features #{:clj}
                             :eof ns-read-eof}
                            source-str)]
      (when (and (seq? form) (= 'ns (first form)))
        form))
    (catch Exception _ nil)))

(defn- ns-header-clauses
  "Returns the clause seqs (`(:require …)`, …) of an `(ns …)` form, skipping
   an optional docstring and attr-map."
  [ns-form]
  (let [args (rest ns-form)
        args (if (string? (first args)) (rest args) args)
        args (if (map? (first args)) (rest args) args)]
    (filter (fn [c] (and (sequential? c) (keyword? (first c)))) args)))

(defn- clause-specs
  "Concatenates the spec lists of every clause headed `head-kw`."
  [clauses head-kw]
  (mapcat rest (filter #(= head-kw (first %)) clauses)))

(defn- spec-opt-map
  "Parses flat libspec opts (`:as a :refer [...] …`) into a map."
  [opts]
  (into {} (comp (map vec) (filter (fn [[k _]] (keyword? k)))) (partition 2 opts)))

(defn- apply-rename
  "Applies a `:rename {orig new}` map to a refer sym vector. Rename keys
   outside the refer set are ignored — without `:refer` the spec refers
   nothing."
  [rename-map refers]
  (if (map? rename-map)
    (mapv (fn [s] (get rename-map s s)) refers)
    (vec refers)))

(defn- ->ns-public-syms
  "Sorted public var symbols of the loaded namespace named `ns-sym`; empty
   when that namespace is not loaded.  This is how a refer-all spec
   (`:refer :all`, bare `:use`) is expanded: `:all` *means* the required
   namespace's publics, so both the source and runtime paths yield the same
   concrete refer set."
  [ns-sym]
  (if-let [nsobj (find-ns ns-sym)]
    (->> (ns-publics nsobj) keys sort vec)
    []))

(defn- libspec-target-entries
  "Returns `[alias-entries require-entries]` for one resolved target ns and
   its opts map.  `default-refers` covers specs naming no refer source: nil
   for `:require` (bare requires vanish from both vecs, as on the runtime
   path) and `:all` for `:use` (refer-all).

   `:refers` is the sorted vector of referred symbols.  A refer-all spec is
   expanded to the target's live `ns-publics` (minus any `:exclude`, with
   `:rename` applied); a spec that refers nothing (`:refer []`, bare
   `:require`) yields no entry."
  [target opts default-refers]
  (let [raw-refers (cond
                     (sequential? (:refer opts)) (:refer opts)
                     (= :all (:refer opts)) :all
                     (sequential? (:only opts)) (:only opts)
                     :else default-refers)
        refers (cond
                 (= :all raw-refers)
                 (->> (->ns-public-syms target)
                      (remove (set (:exclude opts)))
                      (apply-rename (:rename opts))
                      sort
                      vec)

                 (and (sequential? raw-refers) (seq raw-refers))
                 (->> raw-refers
                      (apply-rename (:rename opts))
                      sort
                      vec)

                 :else nil)]
    [(when (symbol? (:as opts))
       [{:ns-name-sym target :alias-sym (:as opts)}])
     (when (some? refers)
       [{:ns-name-sym target :refers refers}])]))

(defn- expand-prefixed-subspec
  "Resolves one sub-spec of a prefix list `(prefix sub …)` against `prefix`,
   yielding `[target opts-map]` pairs (nil for an unrecognized sub)."
  [prefix sub]
  (cond
    (symbol? sub)
    [[(symbol (str prefix "." (name sub))) nil]]

    (vector? sub)
    (let [[target & opts] sub]
      (when (symbol? target)
        [[(symbol (str prefix "." (name target))) (spec-opt-map opts)]]))

    :else nil))

(defn- expand-libspec
  "Yields `[target opts-map]` pairs for one `:require`/`:use` spec: a bare
   symbol, a `[target & opts]` vector, or a `(prefix & subspecs)` list whose
   symbol/vector members resolve against the prefix."
  [spec]
  (cond
    (symbol? spec)
    [[spec nil]]

    (vector? spec)
    (let [[target & opts] spec]
      (when (symbol? target)
        [[target (spec-opt-map opts)]]))

    (sequential? spec)
    (let [[prefix & subs] spec]
      (when (symbol? prefix)
        (into [] (mapcat (partial expand-prefixed-subspec prefix)) subs)))

    :else nil))

(defn- parse-libspec-clause
  "Parses the specs of `:require` (`default-refers` nil) or `:use`
   (`default-refers` `:all`) into `{:aliases [...] :requires [...]}`."
  [specs default-refers]
  (let [entries (into []
                      (comp (mapcat expand-libspec)
                            (map (fn [[target opts]]
                                   (libspec-target-entries target opts default-refers))))
                      specs)]
    {:aliases (into [] (mapcat first) entries)
     :requires (into [] (mapcat second) entries)}))

(defn- merge-requires
  "Merges require entries by ns (union of refers) and sorts by ns name."
  [entries]
  (->> entries
       (group-by :ns-name-sym)
       (mapv (fn [[target es]]
               {:ns-name-sym target
                :refers (->> es (mapcat :refers) distinct sort vec)}))
       (sort-by (comp str :ns-name-sym))
       vec))

(defn- parse-import-spec
  "Parses one `:import` spec: a lone FQ class sym, or a `(package Class …)`
   group — both yield flat FQ class-name symbols."
  [spec]
  (cond
    (symbol? spec)
    [spec]

    (sequential? spec)
    (let [[pkg & classes] spec]
      (when (symbol? pkg)
        (into []
              (comp (filter symbol?)
                    (map (fn [c] (symbol (str (name pkg) "." (name c))))))
              classes)))

    :else nil))

(defn- parse-refer-clojure-opts
  "Parses one `(:refer-clojure & opts)` body. `:only` becomes the complement
   over live `clojure.core` publics — the same set the runtime path derives
   — so source and runtime entries agree; `:rename {orig local}` inverts to
   `{local clojure.core/orig}`."
  [opts]
  (let [opt-map (spec-opt-map opts)
        only (:only opt-map)
        rename-map (:rename opt-map)
        excludes (if (sequential? only)
                   (let [kept (set only)]
                     (->> (ns-publics 'clojure.core)
                          keys
                          (remove kept)
                          (concat (:exclude opt-map))
                          distinct
                          sort
                          vec))
                   (vec (:exclude opt-map)))
        renames (if (map? rename-map)
                  (into {}
                        (map (fn [[orig local]]
                               [local (symbol "clojure.core" (name orig))]))
                        rename-map)
                  {})]
    {:excludes excludes
     :renames renames}))

(defn- merge-refer-clojure
  "Folds the `(:refer-clojure …)` clauses into one `{:excludes :renames}`."
  [clauses]
  (reduce (fn [acc opts]
            (let [{:keys [excludes renames]} (parse-refer-clojure-opts opts)]
              (-> acc
                  (update :excludes into excludes)
                  (update :renames merge renames))))
          {:excludes [] :renames {}}
          (->> clauses
               (filter #(= :refer-clojure (first %)))
               (map rest))))

(s/defn parse-ns-source :- (s/maybe NsDepEntry)
  "Parses an ns header source string into an `NsDepEntry` — the static
   counterpart of `->ns-deps-entry`. `:as` specs feed `:aliases`, `:refer`
   (and `:use` `:only`) specs feed `:require`, both `:import` shapes feed a
   flat `:imports` vector, and `:refer-clojure` feeds `{:excludes :renames}`.
   A refer-all spec (`:refer :all`, bare `:use`) is expanded to the required
   namespace's public vars (its live `ns-publics`); a spec that refers
   nothing (bare `:require`, `:refer []`) produces no `:require` entry.
   `:unmapped-default-imports` is always empty here (a header cannot express
   dynamic `ns-unmap`); `->ns-deps` fills it from the live ns when present.
   Returns nil when the source does not start with an `(ns …)` form.

   `.cljc` note: headers read with `:features #{:clj}`, matching kondo's
   effective `:lang :clj` view; deps expressed only for other platforms are
   skipped, shared (unconditional) specs are included as-is."
  [source-str :- s/Str]
  (when-let [form (read-ns-form source-str)]
    (let [clauses (ns-header-clauses form)
          req (parse-libspec-clause (clause-specs clauses :require) nil)
          use (parse-libspec-clause (clause-specs clauses :use) :all)
          refer-clojure (merge-refer-clojure clauses)]
      {:require (merge-requires (into (:requires req) (:requires use)))
       :aliases (->> (into (:aliases req) (:aliases use))
                     distinct
                     (sort-by (comp str :alias-sym))
                     vec)
       :imports (->> (clause-specs clauses :import)
                     (mapcat parse-import-spec)
                     distinct
                     sort
                     vec)
       :refer-clojure {:excludes (->> (:excludes refer-clojure) distinct sort vec)
                       :renames (:renames refer-clojure)}
       :unmapped-default-imports []})))

(def ^:private empty-entry
  {:require []
   :aliases []
   :imports []
   :refer-clojure {:excludes [] :renames {}}
   :unmapped-default-imports []})

(s/defn ^:private runtime-or-missing :- NsDepEntry
  "Runtime-path entry for `ns-sym`, or an empty entry plus a `tap>` report
   when the ns is not loaded either."
  [ns-sym :- s/Symbol]
  (if-let [nsobj (find-ns ns-sym)]
    (->ns-deps-entry nsobj)
    (do (tap> {:event :clara-rules/ns-deps-missing
               :ns ns-sym})
        empty-entry)))

(defn- unmapped-default-imports-for
  "`:unmapped-default-imports` for `ns-sym` from the live namespace, or an
   empty vector when it is not loaded."
  [ns-sym]
  (if-let [nsobj (find-ns ns-sym)]
    (->ns-unmapped-default-imports nsobj)
    []))

(s/defn ^:private ns-dep-for-ns :- NsDepEntry
  "One `NsDepEntry` for `ns-sym`: the parsed classpath source when present
   (with `:unmapped-default-imports` from the live ns when loaded), else the
   runtime path. An unparseable source falls back to the runtime path."
  [ns-sym :- s/Symbol
   base-source-fn :- (s/=> (s/maybe s/Str) s/Symbol)]
  (let [source (try (base-source-fn ns-sym)
                    (catch Exception _ nil))]
    (if (nil? source)
      (runtime-or-missing ns-sym)
      (if-let [parsed (parse-ns-source source)]
        (assoc parsed :unmapped-default-imports
               (unmapped-default-imports-for ns-sym))
        (runtime-or-missing ns-sym)))))

(s/defn ->ns-deps :- NsDepsMap
  "Maps `ns-syms` to `NsDepEntry` (a sorted-map by ns name): the parsed
   classpath source when present, else the live `Namespace` object, else an
   empty entry with a `:clara-rules/ns-deps-missing` `tap>` report."
  [{:keys [ns-syms base-source-fn]} :- NsDepsOptions]
  (let [base-source-fn (or base-source-fn default-base-source-fn)]
    (into (sorted-map)
          (map (fn [ns-sym] [ns-sym (ns-dep-for-ns ns-sym base-source-fn)]))
          ns-syms)))
