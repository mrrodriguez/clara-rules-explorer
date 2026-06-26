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
  "Merges two type vectors per strategy.
   :merge  (default) — concatenates and dedupes, val1 first.
   :replace          — uses val2 only."
  [val1-types val2-types strategy]
  (if (= :replace strategy)
    (into [] (distinct) val2-types)
    (into [] (comp cat (distinct)) [val1-types val2-types])))

(defn- resolve-no-output-types
  "Resolves the `:clara-rules/no-output-types` boolean annotation.
   val2 takes priority over val1 when both declare the key."
  [val2 val1]
  (get val2 :clara-rules/no-output-types (get val1 :clara-rules/no-output-types)))

(defn- merge-rule-fields
  "Merges two per-rule annotation value maps.
   val2 is layered on top of val1.

   Merge strategies for type vectors are read from val2's
   `:clara-rules/merge-props` map (default :merge for both insert and retract).

   For `:clara-rules/no-output-types` and `:clara-rules/notes`, val2 takes priority when declared.
   Dynamic detection flags are controlled by val2:
   present if val2 declares them, omitted otherwise."
  [val1 val2]
  (let [merge-props      (get val2 :clara-rules/merge-props)
        insert-strategy   (get merge-props :clara-rules/insert-types :merge)
        retract-strategy  (get merge-props :clara-rules/retract-types :merge)

        final-inserts  (merge-types (:clara-rules/insert-types val1)
                                    (:clara-rules/insert-types val2)
                                    insert-strategy)
        final-retracts (merge-types (:clara-rules/retract-types val1)
                                    (:clara-rules/retract-types val2)
                                    retract-strategy)

        no-output      (resolve-no-output-types val2 val1)
        notes          (or (:clara-rules/notes val2)
                           (:clara-rules/notes val1))]
    (cond-> {}
      (seq final-inserts)
      (assoc :clara-rules/insert-types final-inserts)

      (seq final-retracts)
      (assoc :clara-rules/retract-types final-retracts)

      (some? no-output)
      (assoc :clara-rules/no-output-types no-output)

      (some? notes)
      (assoc :clara-rules/notes notes)

      (contains? val2 :clara-rules/dynamic-insert-types-detected)
      (assoc :clara-rules/dynamic-insert-types-detected
             (:clara-rules/dynamic-insert-types-detected val2))

      (contains? val2 :clara-rules/dynamic-retract-types-detected)
      (assoc :clara-rules/dynamic-retract-types-detected
             (:clara-rules/dynamic-retract-types-detected val2)))))

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
  "Normalizes an annotations map to canonical form.
   String keys are converted to symbols."
  [annotations]
  (into (empty annotations)
        (map (fn [[k v]] [(normalize-key k) v]))
        annotations))

(defn merge-annotations
  "Merges two annotations maps. annos2 is layered on top of annos1.

   For rules present in both maps, per-field merge strategies are read from
   annos2's `:clara-rules/merge-props` (same contract as `resolve-annotations`).
   Types use :merge (concatenate) by default, :replace to use annos2 only.
   `:clara-rules/no-output-types` and `:clara-rules/notes` prefer annos2 when declared.

   Rules only in annos1 are kept unchanged.
   Rules only in annos2 are added."
  [annos1 annos2]
  (let [a1 (normalize-annotations annos1)
        a2 (normalize-annotations annos2)]
    (reduce-kv (fn [acc rule-sym val2]
                 (if-let [val1 (get acc rule-sym)]
                   (assoc acc rule-sym (merge-rule-fields val1 val2))
                   (assoc acc rule-sym val2)))
               a1
               a2)))

(defn write-annotations!
  "Writes the annotations map to the specified file path as pretty-printed EDN."
  [path annotations]
  (with-open [w (io/writer path)]
    (binding [*print-meta* true]
      (pp/pprint annotations w))))

(defn resolve-annotations
  "Merges Path A (rule props) and Path B (sidecar) metadata for a production.
   Returns a map with resolved `:insert-types`, `:retract-types`,
   `:resolved-annotation-data`, and `:notes`."
  [production sidecar-annotations]
  (let [fq-name (:name production)
        props (:props production)
        sidecar (or (get sidecar-annotations fq-name)
                    (get sidecar-annotations (symbol fq-name)))
        production-ns (get-production-ns production)

        props-inserts   (resolve-types production-ns
                                       (get props :clara-rules/insert-types))
        props-retracts  (resolve-types production-ns
                                       (get props :clara-rules/retract-types))
        sidecar-inserts (resolve-types production-ns
                                       (get sidecar :clara-rules/insert-types))
        sidecar-retracts (resolve-types production-ns
                                        (get sidecar :clara-rules/retract-types))

        {merge-props :clara-rules/merge-props} sidecar
        insert-strategy  (get merge-props :clara-rules/insert-types :merge)
        retract-strategy (get merge-props :clara-rules/retract-types :merge)

        props-val   (assoc props
                           :clara-rules/insert-types props-inserts
                           :clara-rules/retract-types props-retracts)
        sidecar-val (assoc sidecar
                           :clara-rules/insert-types sidecar-inserts
                           :clara-rules/retract-types sidecar-retracts)

        merged (merge-rule-fields props-val sidecar-val)

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

    {:insert-types                   (:clara-rules/insert-types merged)
     :retract-types                  (:clara-rules/retract-types merged)
     :no-output-types                (:clara-rules/no-output-types merged)
     :notes                          (:clara-rules/notes merged)
     :dynamic-insert-types-detected  (:clara-rules/dynamic-insert-types-detected merged)
     :dynamic-retract-types-detected (:clara-rules/dynamic-retract-types-detected merged)
     :annotation-sources             (into [] (keep (fn [[k v]] (when v k))) annotation-data)
     :resolved-annotation-data       annotation-data}))
