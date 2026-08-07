(ns clara.server.tools.graph.annotations
  "Rule-name normalization and per-production annotation lookup for the
   layered annotation library (see docs/rule-annotations.md).  Handles
   arbitrary fact types (classes, keywords, symbols) as supported by Clara's
   pluggable fact-type-fn.

   The rest of the library lives in sibling namespaces:

     clara.server.tools.graph.annotations.callsite — callsite format, identity
     clara.server.tools.graph.annotations.merge    — layers, merging, derivation
     clara.server.tools.graph.annotations.report   — work list and validation
     clara.server.tools.graph.annotations.rebase   — namespace-rename rebasing

   Rule-name normalization:
     Clara rules use strings for `:name` (schema: `s/Str` or `s/Keyword`),
     but annotations may arrive with symbol keys from EDN or kondo analysis.
     All public functions that touch annotation maps normalize top-level
     rule-name keys to strings: `normalize-rule-name` converts a single key,
     `normalize-annotations` transforms an entire map, and `get-annotation`
     normalizes the lookup key before access.")

(defn normalize-rule-name
  "Normalizes a rule-name key to its canonical string form.
   Symbols and keywords are converted to strings; strings pass through."
  [k]
  (if (or (symbol? k) (keyword? k))
    (str (symbol k))
    k))

(defn normalize-annotations
  "Normalizes an annotations map to canonical form: all top-level rule-name
   keys are converted to strings via `normalize-rule-name`, and the result
   is a deterministically sorted map."
  [annotations]
  (into (sorted-map)
        (map (fn [[k v]] [(normalize-rule-name k) v]))
        annotations))

(defn get-annotation
  "Looks up a rule entry in the annotations map, normalizing the lookup key
   via `normalize-rule-name` before access."
  [annotations rule-name]
  (get annotations (normalize-rule-name rule-name)))

;; ---------------------------------------------------------------------------
;; Per-production lookup
;; ---------------------------------------------------------------------------

(defn- resolve-type-locally
  [production-ns x]
  (if (symbol? x)
    (try
      (let [resolved (and production-ns (ns-resolve production-ns x))]
        (cond
          (class? resolved) resolved
          ;; Not likely to have this happen.
          (var? resolved) (-> resolved deref)
          :else x))
      (catch Exception _ x))
    x))

(defn- get-production-ns
  [production]
  (some-> production :ns-name symbol the-ns))

(defn- resolve-types
  "Resolves type tokens against production-ns, dropping any that resolve to nil.
   Nil entries in annotation files (or symbols that fail to resolve) are silently
   removed — nil is not a meaningful fact type in a Clara session."
  [production-ns unresolved-types]
  (into []
        (keep #(resolve-type-locally production-ns %))
        unresolved-types))

(defn- unqualify-keyword
  [kw]
  (if (and (keyword? kw) (namespace kw))
    (keyword (name kw))
    kw))

(def ^:private production-annotation-keys
  #{:clara-rules/insert-types
    :clara-rules/retract-types
    :clara-rules/no-output-types
    :clara-rules/notes
    :clara-rules/dynamic-insert-types-detected
    :clara-rules/dynamic-retract-types-detected})

(defn- resolve-type-key
  [m k production-ns]
  (if (contains? m k)
    (update m k #(resolve-types production-ns %))
    m))

(defn production-annotation
  "Reads one production's annotation from a merged (or bare) annotations map
   with unqualified keys (:insert-types, :retract-types, :no-output-types,
   :notes, :dynamic-insert-types-detected,
   :dynamic-retract-types-detected).  Symbol type tokens are resolved against
   the production's namespace (classes, vars) as Clara's pluggable
   fact-type-fn allows.  The lookup normalizes the map's rule-name keys when
   they are not already strings."
  [annotations production]
  (let [annotations (if (every? (comp string? key) annotations)
                      annotations
                      (normalize-annotations annotations))
        rule-ann (get-annotation annotations (:name production))
        production-ns (get-production-ns production)]
    (-> (select-keys rule-ann production-annotation-keys)
        (resolve-type-key :clara-rules/insert-types production-ns)
        (resolve-type-key :clara-rules/retract-types production-ns)
        (update-keys unqualify-keyword))))
