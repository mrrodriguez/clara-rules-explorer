(ns clara.server.tools.graph.annotations
  "Logic for loading and merging rule metadata from internal props and sidecar files.
   Handles arbitrary fact types (classes, keywords, symbols) as supported by Clara's
   pluggable fact-type-fn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
