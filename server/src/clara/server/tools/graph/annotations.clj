(ns clara.server.tools.graph.annotations
  "Logic for loading and merging rule metadata from internal props and sidecar files.
   Handles arbitrary fact types (classes, keywords, symbols) as supported by Clara's
   pluggable fact-type-fn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(defn load-sidecar
  "Loads annotations from an EDN file path. Keyed by rule/query FQ-name strings
   or symbols."
  [path]
  (if (and path (.exists (io/file path)))
    (with-open [r (io/reader path)]
      (edn/read (java.io.PushbackReader. r)))
    {}))

(defn- merge-types
  "Merges props and sidecar type vectors.
   Strategy: :merge (default) concatenates and dedupes; :replace uses sidecar only."
  [props-types sidecar-types strategy]
  (if (= :replace strategy)
    (into [] (distinct) sidecar-types)
    (into [] (comp cat (distinct)) [props-types sidecar-types])))

(defn- resolve-no-output-types
  "Resolves the :no-output-types boolean annotation from sidecar or props.
   Sidecar takes priority over props when both declare the key."
  [sidecar props]
  (get sidecar :clara-rules/no-output-types (get props :clara-rules/no-output-types)))

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
  [production-ns unresolved-types]
  (mapv #(resolve-type-locally production-ns %)
        unresolved-types))

(defn- ->resolved-annotation-data
  "Returns a per-key map of annotation resolution status.
   Each key maps to :props, :sidecar, :merge, or nil (undeclared)."
  [{:keys [props-inserts sidecar-inserts insert-strategy
           props-retracts sidecar-retracts retract-strategy
           has-props-no-output-types has-sidecar-no-output-types
           has-props-notes has-sidecar-notes]}]
  (let [has-pi (seq props-inserts)
        has-si (seq sidecar-inserts)
        has-pr (seq props-retracts)
        has-sr (seq sidecar-retracts)]
    {:clara-rules/insert-types
     (cond
       (and has-pi has-si (= :replace insert-strategy)) :sidecar
       (and has-pi has-si) :merge
       has-si :sidecar
       has-pi :props)

     :clara-rules/retract-types
     (cond
       (and has-pr has-sr (= :replace retract-strategy)) :sidecar
       (and has-pr has-sr) :merge
       has-sr :sidecar
       has-pr :props)

     :clara-rules/no-output-types
     (cond
       has-sidecar-no-output-types :sidecar
       has-props-no-output-types :props)

     :clara-rules/notes
     (cond
       has-sidecar-notes :sidecar
       has-props-notes :props)}))

(defn- normalize-key [k]
  (if (string? k)
    (symbol k)
    k))

(defn normalize-annotations
  "Normalizes keys in an annotations map.
   String keys are converted to symbols.
   Returns a lookup map of normalized-key -> [original-key original-value]."
  [annotations]
  (into {} (map (fn [[k v]] [(normalize-key k) [k v]]) annotations)))

(defn merge-annotations
  "Merges existing annotations with generated annotations.
   Existing annotations take precedence, but empty or missing fields are populated.
   New rules in generated annotations are added."
  [existing generated]
  (let [normalized-existing (normalize-annotations existing)]
    (reduce (fn [acc [rule-sym gen-val]]
              (if-let [[orig-key orig-val] (get normalized-existing rule-sym)]
                ;; Rule exists, merge properties
                (let [merged-val (cond-> orig-val
                                   (and (not (contains? orig-val :clara-rules/insert-types))
                                        (seq (:clara-rules/insert-types gen-val)))
                                   (assoc :clara-rules/insert-types (:clara-rules/insert-types gen-val))

                                   (and (not (contains? orig-val :clara-rules/retract-types))
                                        (seq (:clara-rules/retract-types gen-val)))
                                   (assoc :clara-rules/retract-types (:clara-rules/retract-types gen-val))

                                   (and (not (contains? orig-val :clara-rules/dynamic-insert-types-detected))
                                        (contains? gen-val :clara-rules/dynamic-insert-types-detected)
                                        (not (seq (:clara-rules/insert-types orig-val))))
                                   (assoc :clara-rules/dynamic-insert-types-detected (:clara-rules/dynamic-insert-types-detected gen-val))

                                   (and (not (contains? orig-val :clara-rules/dynamic-retract-types-detected))
                                        (contains? gen-val :clara-rules/dynamic-retract-types-detected)
                                        (not (seq (:clara-rules/retract-types orig-val))))
                                   (assoc :clara-rules/dynamic-retract-types-detected (:clara-rules/dynamic-retract-types-detected gen-val))

                                   (and (not (contains? orig-val :clara-rules/no-output-types))
                                        (:clara-rules/no-output-types gen-val)
                                        (not (seq (:clara-rules/insert-types orig-val)))
                                        (not (seq (:clara-rules/retract-types orig-val))))
                                   (assoc :clara-rules/no-output-types true))]
                  (assoc acc orig-key merged-val))
                ;; New rule, add it
                (assoc acc rule-sym gen-val)))
            existing
            generated)))

(defn write-annotations!
  "Writes the annotations map to the specified file path as pretty-printed EDN."
  [path annotations]
  (with-open [w (io/writer path)]
    (binding [*print-meta* true]
      (pp/pprint annotations w))))

(defn resolve-annotations
  "Merges Path A (rule props) and Path B (sidecar) metadata for a production.
   Returns a map with resolved :insert-types, :retract-types, :resolved-annotation-data, and :notes."
  [production sidecar-annotations]
  (let [fq-name (:name production)
        props (:props production)
        sidecar (or (get sidecar-annotations fq-name)
                    (get sidecar-annotations (symbol fq-name)))
        merge-props (get sidecar :clara-rules/merge-props)

        production-ns (get-production-ns production)

        props-inserts (resolve-types production-ns
                                     (get props :clara-rules/insert-types))
        props-retracts (resolve-types production-ns
                                      (get props :clara-rules/retract-types))

        sidecar-inserts (resolve-types production-ns
                                       (get sidecar :clara-rules/insert-types))
        sidecar-retracts (resolve-types production-ns
                                        (get sidecar :clara-rules/retract-types))

        insert-strategy  (get merge-props :clara-rules/insert-types :merge)
        retract-strategy (get merge-props :clara-rules/retract-types :merge)

        final-inserts (merge-types props-inserts sidecar-inserts insert-strategy)
        final-retracts (merge-types props-retracts sidecar-retracts retract-strategy)

        annotation-data (->resolved-annotation-data
                         {:props-inserts props-inserts
                          :sidecar-inserts sidecar-inserts
                          :insert-strategy insert-strategy
                          :props-retracts props-retracts
                          :sidecar-retracts sidecar-retracts
                          :retract-strategy retract-strategy
                          :has-props-no-output-types (some? (get props :clara-rules/no-output-types))
                          :has-sidecar-no-output-types (some? (get sidecar :clara-rules/no-output-types))
                          :has-props-notes (some? (get props :clara-rules/notes))
                          :has-sidecar-notes (some? (get sidecar :clara-rules/notes))})]

    {:insert-types final-inserts
     :retract-types final-retracts
     :no-output-types (resolve-no-output-types sidecar props)
     :annotation-sources (into [] (keep (fn [[k v]] (when v k))) annotation-data)
     :resolved-annotation-data annotation-data
     :notes (or (get sidecar :clara-rules/notes)
                (get props :clara-rules/notes))}))
