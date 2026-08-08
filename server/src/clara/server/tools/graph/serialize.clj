(ns clara.server.tools.graph.serialize
  "Helpers for serializing Clara rulebase structures to JSON-friendly formats."
  (:require
   [clojure.pprint :as pp]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as w]
   [clara.rules.schema :as schema])
  (:import [java.math BigInteger]))

(defn resolve-type
  "Resolves a raw fact type (Class, keyword, symbol, string, tuple, map, ...) to its
   kind-explicit string representation for JSON.  The kind is self-describing
   in the output: classes as `.getName`, keywords with their colon, strings,
   tuples and maps via `pr-str` (quotes/elements preserved), unresolved
   symbols wrapped in `symbol[...]`, anything else via `str`.  `prod-ns` is the
   production's namespace, used to resolve symbol types."
  [prod-ns x]
  (cond
    (nil? x) nil
    (class? x) (.getName ^Class x)
    (keyword? x) (str x)
    (symbol? x) (if-let [resolved (and prod-ns
                                       (some-> (find-ns prod-ns)
                                               (ns-resolve x)))]
                  (cond
                    (class? resolved) (.getName ^Class resolved)
                    (var? resolved) (let [{vns :ns vname :name} (meta resolved)
                                          ns-str (name (ns-name vns))
                                          name-str (name vname)]
                                      (str (symbol ns-str name-str)))
                    :else (str resolved))
                  (str "symbol[" x "]"))
    (string? x) (pr-str x)
    (sequential? x) (pr-str x)
    (map? x) (pr-str x)
    :else (str x)))

(defn- slug
  "URL-safe slug of a name: every char outside [A-Za-z0-9.-] replaced by '-',
   runs collapsed, leading/trailing '-' trimmed, capped at 60 chars.
   nil (or any name that slugs to empty) yields \"x\"."
  [s]
  (let [slugged (-> (or s "")
                    (str/replace #"[^A-Za-z0-9.-]" "-")
                    (str/replace #"-+" "-")
                    (str/replace #"^-|-$" ""))
        slugged (subs slugged 0 (min 60 (count slugged)))]
    (if (empty? slugged) "x" slugged)))

(defn- sha1-base36
  "Base36 representation of the SHA-1 digest of `s`; nil treated as \"\"
   (so a nil name gets the same hash as an empty one)."
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-1")
        bytes (.digest digest (.getBytes (or s "") "UTF-8"))]
    (.toString (BigInteger. 1 bytes) 36)))

(defn- route-id*
  "Deterministic URL-safe id for a canonical serialized name: slug of the name
   plus an 8-char base36 SHA-1 suffix.  The id is a pure function of the name,
   so re-running the analysis never changes existing ids.  nil returns nil
   with a WARN — callers should filter nil before reaching this point."
  [s]
  (if (nil? s)
    (do (println "WARN: route-id* called with nil name — skipping")
        nil)
    (str (slug s) "-" (subs (sha1-base36 s) 0 8))))

(def route-id
  "Memoized `route-id*` — the same name recurs in thousands of ProductionDep
   entries across an analysis build."
  (memoize route-id*))

(defn serialize-type-ref
  "Serializes a raw fact type into a TypeReference map for JSON output:
   {:name kind-explicit serialized name, :id deterministic route id,
   :known true iff the serialized name is a member of `known-set` (the
   analysis's fact-type names).  `prod-ns` is the production's namespace,
   used to resolve symbol types."
  [known-set prod-ns x]
  (let [name (resolve-type prod-ns x)]
    (if (nil? name)
      (do (println (str "WARN: serialize-type-ref received a nil-resolving type token: "
                        (pr-str x) " — dropping. prod-ns=" prod-ns))
          nil)
      {:name name
       :id (route-id name)
       :known (contains? known-set name)})))

(defn serialize-match
  "Serializes raw {:producer-type ... :consumer-type ...} pairs (the
   `matching-type-pairs` output) into TypeReference pairs, each end
   serialized in its own production's ns context, sorted by producer then
   consumer :name.  Symmetric shape and meaning on upstream and downstream
   entries: producer-type is what the producing rule inserts (or retracts),
   consumer-type is what the consuming rule's LHS requires.  A raw pair's
   `:via :retract` (producer-type is a retract type) is carried through so
   the UI can distinguish retraction coupling from production."
  [{:keys [raw-pairs known-set producer-ns consumer-ns]}]
  (->> raw-pairs
       (map (fn [{:keys [producer-type consumer-type via]}]
              (cond-> {:producer-type (serialize-type-ref known-set producer-ns producer-type)
                       :consumer-type (serialize-type-ref known-set consumer-ns consumer-type)}
                via (assoc :via via))))
       (sort-by (juxt (comp :name :producer-type)
                      (comp :name :consumer-type)))
       (vec)))

(defn serialize-fact-type
  [production-ns-name x]
  (resolve-type production-ns-name x))

(defn prune-fns
  "Recursively walks a data structure and replaces items that implement IFn
   with a string placeholder or their symbol if available."
  [x]
  (cond
    (record? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns v)))
                           {}
                           x)
    (map? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns v)))
                        (empty x)
                        x)
    ;; seq-like things or list will insert items to the head, which will reverse the order with
    ;; `into`. This avoids that.
    (or (list? x)
        (and (sequential? x)
             (not (vector? x)))) (into (empty x)
                                       (map prune-fns)
                                       (reverse x))
    ;; Do not preserve type here, it could be a lazy seq and we'd get reversed order. If it is not
    ;; covered by the seq-like checks above, use a vector.
    (sequential? x) (into []
                          (map prune-fns)
                          x)
    (coll? x) (into (empty x)
                    (map prune-fns)
                    x)
    (keyword? x) x
    (symbol? x) x
    (class? x) (.getName ^Class x)
    (ifn? x) (str x)
    :else x))

(defn stringify-map-keys
  "Recursively converts keyword keys in a map to their string names.
   Returns nil if input is nil."
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc
                        (if (keyword? k) (name k) k)
                        (if (map? v) (stringify-map-keys v) v)))
               (empty m)
               m)))

(defn stringify-idents-coll
  "Converts a coll of symbols or keywords to a set of their string names.
   Returns nil if input is nil."
  [coll]
  (when coll
    (into (empty coll) (map name) coll)))

(defn serialize-production-dep
  "Serializes a production reference (ProductionDep): {name, ns, type, id}."
  [production-map fq-dep-name]
  (let [{p-ns-name :ns-name :keys [rhs]} (get production-map fq-dep-name)
        base
        (if (and (string? fq-dep-name)
                 (str/includes? fq-dep-name "/"))
          (let [fq-sym (-> fq-dep-name symbol)
                ns-part (namespace fq-sym)]
            {:ns ns-part
             :name (str fq-dep-name)
             :id (route-id (str fq-dep-name))})
          {:ns (str p-ns-name)
           :name fq-dep-name
           :id (route-id (str fq-dep-name))})]
    (cond-> base
      (seq rhs) (assoc :type "rule")
      (nil? rhs) (assoc :type "query"))))

(defn serialize-condition
  "Serializes a single condition, including pretty-printing its constraints and
   args and converting its `:type` (raw fact type) into a TypeReference.
   `prod-ns` is the production's namespace, used to resolve symbol types;
   `known-set` is the analysis's serialized fact-type names."
  [condition prod-ns known-set]
  (letfn [(serialize-form [form]
            (with-out-str (pp/pprint form)))
          (serialize-forms [forms]
            ;; `pp/pprint` adds the newline after the last form so do not include it trailing
            ;; in this `format` call.
            (format "[\n%s]"
                    (->> forms
                         (map serialize-form)
                         (str/join \newline))))
          (serialize-node [node]
            (if (map? node)
              (cond-> node
                (some? (:type node)) (update :type #(serialize-type-ref known-set prod-ns %))
                (contains? node :constraints) (update :constraints serialize-forms)
                (contains? node :args) (update :args serialize-forms))
              node))]
    (w/prewalk serialize-node condition)))

(defn serialize-lhs
  "Serializes the LHS of a rule.  Condition `:type` values are raw fact types
   here — callers must apply `prune-fns` to the RESULT (not beforehand) so the
   types are still Classes/keywords when `serialize-condition` converts them
   to TypeReferences."
  [lhs prod-ns known-set]
  (mapv #(serialize-condition % prod-ns known-set) lhs))

(defn- condition->form
  "Reconstructs a Clojure code form from a condition, mirroring defrule syntax.
   Dispatches on `schema/condition-type` so that boolean groups (`:and`, `:or`,
   `:not`, `:exists`) and accumulator `:from` conditions are recursively
   reconstructed rather than silently dropped."
  [condition]
  (case (schema/condition-type condition)
    :accumulator
    (vec (-> []
             (cond-> (:result-binding condition) (conj (:result-binding condition) '<-))
             (conj (:accumulator condition))
             (cond-> (:from condition) (conj :from (condition->form (:from condition))))))

    (:and :or :not :exists)
    (into [(first condition)] (map condition->form) (rest condition))

    ;; :fact and :test are both leaf maps with :type, :args, :constraints
    (vec (-> []
             (cond-> (:fact-binding condition) (conj (:fact-binding condition) '<-))
             (cond-> (:type condition) (conj (:type condition)))
             (cond-> (:args condition) (conj (:args condition)))
             (into (or (:constraints condition) []))))))

(defn serialize-lhs-form
  "Pretty-prints the full LHS as a single Clojure code string."
  [lhs]
  (->> lhs
       (map condition->form)
       (map (fn [form] (with-out-str (pp/pprint form))))
       str/join))

(defn serialize-rhs-form
  [rhs-form]
  (with-out-str (pp/pprint rhs-form)))

(defn remove-nil-vals
  "Returns the map `m` with all entries whose value is nil removed."
  [m]
  (->> m
       (reduce-kv (fn [m' k v]
                    (if (nil? v) (dissoc! m' k) m'))
                  (transient m))
       persistent!))

(defn serialize-dynamic-callsite
  "Serializes a dynamic callsite entry for JSON output.
   - :ns-name-sym → :ns (string).
   - select-keys allowlist restricts to API-relevant keys.
   - :fact-type (var-alias context) serializes like resolved-types tokens;
     :fact-type-spec map values are stringified (e.g. {:aliases-var my.ns/f}
     encodes as {\"aliases-var\": \"my.ns/f\"}).
   prod-ns is the production's namespace and known-set the analysis's
   serialized fact-type names, used to resolve and flag types."
  [callsite prod-ns known-set]
  (cond-> callsite
      ;; rename :ns-name-sym → :ns, convert symbol → string
    true (set/rename-keys {:ns-name-sym :ns})
    true (update :ns #(if (symbol? %) (str %) %))
    true (select-keys #{:source-str :ns :filename :status :resolved-types
                        :fact-type :fact-type-spec :constructor-sym :via})

      ;; resolve :resolved-types / :fact-type tokens to TypeReferences
    (seq (:resolved-types callsite))
    (update :resolved-types
            (fn [types]
              (into []
                    (comp (remove nil?)
                          (map #(serialize-type-ref known-set prod-ns %)))
                    types)))

    (:fact-type callsite)
    (assoc :fact-type (serialize-type-ref known-set prod-ns (:fact-type callsite)))

    (:fact-type-spec callsite)
    (update :fact-type-spec
            #(into {}
                   (map (fn [[k v]] [k (if (symbol? v) (str v) v)]))
                   %))

    (:constructor-sym callsite)
    (update :constructor-sym #(if (symbol? %) (str %) %))

    (:via callsite)
    (update :via (fn [via]
                   (cond-> via
                     (:boundary-var-name-sym via)
                     (update :boundary-var-name-sym #(if (symbol? %) (str %) %))

                     (:callstack via)
                     (update :callstack (fn [cs]
                                          (mapv (fn [entry]
                                                  (update entry :var-name-sym #(if (symbol? %) (str %) %)))
                                                cs))))))
    true remove-nil-vals))

(defn serialize-dynamic-detection
  "Serializes a dynamic detection info map (:dynamic-insert-types-detected or
   :dynamic-retract-types-detected) for JSON output.
   prod-ns is the production's namespace and known-set the analysis's
   serialized fact-type names, used to resolve type tokens."
  [detection prod-ns known-set]
  (cond-> detection
    (:callsites detection)
    (update :callsites (fn [callsites] (mapv #(serialize-dynamic-callsite % prod-ns known-set) callsites)))))
