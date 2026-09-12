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
      mapping, offset) that `prune-and-rename-analysis` consumes.

   When the namespace has no classpath source, a caller can supply the
   definition forms of non-production vars through the `:var-defs-fn` hook
   (see `VarDef`).  Those defs are emitted between the `(declare …)` of the
   reconstructed ns and the rule snippets, restoring the call graph through
   helper vars that the `declare` alone cannot express."
  (:require [clara.server.tools.graph.ns-deps :as ns-deps]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [schema.core :as s]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- non-production-interns
  "Returns the sorted list of symbols interned in the namespace `ns-sym`
   that are not in `production-names`."
  [ns-sym production-names]
  (->> (ns-interns (the-ns ns-sym))
       keys
       (remove production-names)
       sort))

;; ---------------------------------------------------------------------------
;; reconstruct-ns-source helpers
;; ---------------------------------------------------------------------------

(defn ->require-clauses
  "Builds a sorted vector of `:require` clause vectors from the namespace's
   aliases and refers, excluding `clojure.core` refers."
  [nsobj]
  (let [alias-clauses (mapv (fn [{:keys [ns-name-sym alias-sym]}]
                              [ns-name-sym :as alias-sym])
                            (ns-deps/->ns-aliases nsobj))
        refer-clauses (mapv (fn [{:keys [ns-name-sym refers]}]
                              [ns-name-sym :refer (vec refers)])
                            (ns-deps/->ns-required nsobj))]
    (->> (concat alias-clauses refer-clauses)
         (sort-by (comp str first))
         vec)))

(defn- fq-sym-package
  "Returns the package portion of a fully-qualified class-name symbol
   (everything before the last `.`), or the empty string for a
   default-package class."
  [fq-sym]
  (let [n (name fq-sym)
        idx (str/last-index-of n ".")]
    (if idx (subs n 0 idx) "")))

(defn- fq-sym-simple-name
  "Returns the simple-name symbol of a fully-qualified class-name symbol."
  [fq-sym]
  (let [n (name fq-sym)
        idx (str/last-index-of n ".")]
    (symbol (if idx (subs n (inc idx)) n))))

(defn ->import-clauses
  "Builds a sorted vector of `:import` clause vectors from the namespace's
   imports, excluding exactly the `RT/DEFAULT_IMPORTS` set (which are
   automatic in any new `ns`)."
  [nsobj]
  (let [import-groups (group-by fq-sym-package (ns-deps/->ns-imports nsobj))]
    (->> import-groups
         (map (fn [[pkg fq-syms]]
                (->> fq-syms
                     (map fq-sym-simple-name)
                     sort
                     (into [(symbol pkg)]))))
         (sort-by (comp str first))
         vec)))

(defn- renamed-clause-map
  "Inverts a `{local fq-core-sym}` renames map into the `{orig local}` shape
   the `ns` `:rename` spec accepts, with both sides simple symbols."
  [renamed]
  (into {}
        (map (fn [[local fq-sym]] [(symbol (name fq-sym)) local]))
        renamed))

(defn ->unmapped-default-imports
  "Returns the sorted vector of default `java.lang.*` import symbols missing
   from the given namespace (possible only via dynamic `ns-unmap`)."
  [nsobj]
  (ns-deps/->ns-unmapped-default-imports nsobj))

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
        {:keys [excludes renames]} (ns-deps/->ns-refer-clojure nsobj)
        require-clauses   (->require-clauses nsobj)
        import-clauses    (->import-clauses nsobj)
        unmapped-defaults (->unmapped-default-imports nsobj)
        refer-clojure-clause (when (or (seq excludes) (seq renames))
                               (concat (list :refer-clojure)
                                       (when (seq excludes) (list :exclude excludes))
                                       (when (seq renames)
                                         (list :rename (renamed-clause-map renames)))))
        require-clause (when (seq require-clauses)
                         (cons :require require-clauses))
        import-clause (when (seq import-clauses)
                        (cons :import import-clauses))
        ns-form (concat (list 'ns ns-sym)
                        (remove nil? [refer-clojure-clause require-clause import-clause]))]
    (str (pr-str ns-form) "\n"
         (str/join (map #(format "(ns-unmap (the-ns '%s) '%s)\n" ns-sym %)
                        unmapped-defaults)))))

(s/defschema VarDef
  "One top-level definition to include in a synthesized namespace source.
   `:name` is the var's local symbol (unqualified, as interned in `ns-sym`).
   `:form` is the complete top-level form defining it — `s/Any` because it is
   unevaluated source data of arbitrary shape."
  {:name s/Symbol
   :form s/Any})

(def ^:private var-def-read-eof (Object.))

(defn- var-def-line
  "Prints one `VarDef` as a single readable source line.  Returns the line
   (with trailing newline), or nil — with a `tap>` report — when the form
   prints across lines or does not read back as a single form."
  [ns-sym {:keys [name form]}]
  (let [printed (binding [*print-length* nil
                          *print-level* nil]
                  (pr-str form))
        multiline? (str/includes? printed "\n")
        readable? (when-not multiline?
                    (not= var-def-read-eof
                          (try (read-string {:read-cond :allow
                                             :eof var-def-read-eof}
                                            printed)
                               (catch Exception _
                                 var-def-read-eof))))
        ok? (and (not multiline?) readable?)]
    (when-not ok?
      (tap> {:event :clara-rules/var-def-skipped
             :ns ns-sym
             :var name
             :reason (if multiline? :multiline :unreadable)
             :printed printed}))
    (when ok?
      (str printed "\n"))))

(defn- synth-var-defs
  "Returns the emitted var-def source for `ns-sym` from the caller's
   `:var-defs-fn` (a `(fn [ns-sym] -> nil | [VarDef …])`), or nil when the
   hook supplies nothing.  Production-name collisions are dropped; unreadable
   or multiline forms are skipped (see `var-def-line`).  Exceptions are
   contained the way `:callsite-resolver-fn` exceptions are: logged, treated
   as no var defs for this namespace.  Only invoked on the reconstructed
   (no classpath source) path."
  [ns-sym var-defs-fn prod-names]
  (let [var-defs (try
                   (when var-defs-fn (var-defs-fn ns-sym))
                   (catch Throwable t
                     (log/errorf t "clara.server.tools.graph.analyze: :ns-var-defs-fn threw: %s"
                                 (ex-message t))
                     nil))]
    (when (seq var-defs)
      (let [lines (->> var-defs
                       (remove (fn [{:keys [name]}] (contains? prod-names name)))
                       (keep #(var-def-line ns-sym %))
                       seq)]
        (when lines
          (str/join lines))))))

(s/defschema SynthProduction
  "One rule production to synthesize a snippet for — an open map carrying at
   least :name (symbol or string, normalized by :normalize-key-fn) and :rhs
   (the RHS form).  Productions carry many more keys this namespace does not
   consume, hence the open predicate rather than a closed map schema."
  (s/pred (fn [p]
            (and (map? p)
                 (contains? p :name)
                 (contains? p :rhs)
                 (or (symbol? (:name p)) (string? (:name p)))))
          'synth-production?))

(s/defschema SynthesizeNsSourceOptions
  "Options for `synthesize-ns-source` — validated with `s/validate` at entry.
   The `s/=>` fn schemas document arg/return shapes (and require `ifn?`);
   `:var-defs-fn` is optional and may be nil."
  {:ns-sym s/Symbol
   :productions [SynthProduction]
   :base-source-fn (s/=> (s/maybe s/Str) s/Symbol)
   :normalize-key-fn (s/=> s/Symbol s/Any)
   (s/optional-key :var-defs-fn) (s/maybe (s/=> (s/maybe [VarDef]) s/Symbol))})

(s/defschema SynthesizeNsSourceResult
  "Return of `synthesize-ns-source`.  `:tag->production` maps each snippet
   tag (a deterministic ordinal symbol, never a raw production name) to the
   production's local-name symbol."
  {:source s/Str
   :offset s/Int
   :tag->production {s/Symbol s/Symbol}})

(defn synthesize-ns-source
  "Builds the combined source for a rule-owning namespace: the real source
   (or a reconstructed `ns` form when none is on the classpath) plus one
   synthetic snippet def per rule.

   Takes `SynthesizeNsSourceOptions` (validated at entry) and returns a
   `SynthesizeNsSourceResult`.

   On the no-classpath-source path, `:var-defs-fn` supplies definition forms
   for non-production vars (see `VarDef`); they are emitted between the
   `(declare …)` and the rule snippets, and `:offset` covers them so the
   snippet region still starts at the first rule."
  [{:keys [ns-sym productions base-source-fn normalize-key-fn var-defs-fn] :as options}]
  (s/validate SynthesizeNsSourceOptions options)
  (let [real-source (base-source-fn ns-sym)
        base-source (or real-source
                        (reconstruct-ns-source ns-sym))
        prod-names (into #{} (map (comp symbol name normalize-key-fn :name))
                         productions)
        declare-form (when (nil? real-source)
                       (let [helpers (non-production-interns ns-sym prod-names)]
                         (when (seq helpers)
                           (str "(declare " (str/join " " helpers) ")\n"))))
        var-defs-source (when (nil? real-source)
                          (synth-var-defs ns-sym var-defs-fn prod-names))
        extended-source (str base-source
                             (or declare-form "")
                             (or var-defs-source ""))
        offset (count (str/split-lines extended-source))
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
    {:source (str extended-source "\n" (str/join "\n" (map :form snippets)) "\n")
     :offset offset
     :tag->production (into {} (map (juxt :tag :local-name)) snippets)}))
