(ns clara.server.tools.graph.artifacts.rehydrate
  "The inverse of `clara.server.tools.graph.artifacts.slim`: rebuild every
  direction the slim pass drops because it is recomputable, so a persisted
  analysis can answer the same questions a live one can.

  The pure recomputation — the four usage closures, the `:downstream` transpose,
  and the reference expansion — lives in `clara.server.tools.graph.shared.rehydrate`.
  This namespace is the JVM half: the annotation-backed restoration and the
  `:slim` block narrowing, which need the live annotation merge.

    (rehydrate-analysis analysis)                  ; inverses + indexes rebuilt
    (rehydrate-analysis analysis {:annotations …}) ; also :merged-annotations + authored dynamic detections

  Left absent, and still declared in a narrowed `:slim` block: `:nodes`,
  `:lhs-form`, `:raw-condition`, `::conditions/normalized`, and `:ns-deps`.
  `:lhs-form` looks recoverable and is not — it renders from the raw Clara LHS,
  which `:raw-condition` carried and `slim` drops; a re-render from the
  serialized `:lhs` would produce a similar string that is not the same string."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.conditions :as conditions]
   [clara.server.tools.graph.shared.rehydrate :as shared-rehydrate]
   [clojure.set :as set]))

(set! *warn-on-reflection* true)

(def ^:private always-absent
  "Keys rehydrate can never put back — they need the live rulebase, not what
  `slim` kept. `:raw-condition` / `::conditions/normalized` are condition-node
  keys inside a kept `:lhs`, not top-level keys, but they are part of the
  `:slim :dropped` vocabulary and stay named there."
  #{:nodes :lhs-form :raw-condition ::conditions/normalized :ns-deps})

(def ^:private annotation-restored
  "Keys restored only when the caller hands over the merged annotations the
  analysis was computed from: the input itself, and the authored dynamic
  detection maps (symbols / raw type tokens) the serialized form was derived
  from."
  #{:merged-annotations :dynamic-insert-types-detected :dynamic-retract-types-detected})

(defn- restore-dynamic-detected
  "The authored dynamic-detection maps from the merged annotations, per rule.
  This is the authored form (symbols, raw type tokens), not the serialized one
  `GET /v1/rules/:fq-name` serves."
  [analysis annotations]
  (let [restore (fn [productions]
                  (when productions
                    (into (sorted-map)
                          (map (fn [[p-name production]]
                                 [p-name
                                  (cond-> production
                                    (contains? (get annotations p-name)
                                               :clara-rules/dynamic-insert-types-detected)
                                    (assoc :dynamic-insert-types-detected
                                           (get-in annotations
                                                   [p-name :clara-rules/dynamic-insert-types-detected]))

                                    (contains? (get annotations p-name)
                                               :clara-rules/dynamic-retract-types-detected)
                                    (assoc :dynamic-retract-types-detected
                                           (get-in annotations
                                                   [p-name :clara-rules/dynamic-retract-types-detected])))]))
                          productions)))]
    (-> analysis
        (update :rules restore)
        (update :queries restore))))

(defn- narrow-slim
  "The still-absent set, written back into the `:slim` block so a reader knows
  what rehydrate put back and what it could not."
  [analysis annotations?]
  (let [dropped (set/union always-absent
                           (when-not annotations? annotation-restored))]
    (update analysis :slim
            (fn [slim-block]
              (-> slim-block
                  (assoc :dropped dropped)
                  (update :recover #(select-keys % dropped)))))))

(defn rehydrate-analysis
  "`analysis` with every direction `slim` dropped because it is recomputable put
  back. The pure recomputation delegates to
  `clara.server.tools.graph.shared.rehydrate/rehydrate-analysis`; this wrapper
  adds the annotation-backed restoration and narrows the `:slim` block.

  `opts`:
    :annotations — a merged annotations value (or bare rule→annotation map).
    When present, `:merged-annotations` is restored and each production regains
    its authored `:dynamic-insert-types-detected` /
    `:dynamic-retract-types-detected` maps."
  ([analysis]
   (rehydrate-analysis analysis nil))
  ([analysis {:keys [annotations]}]
   (let [annotations (some-> annotations ann.merge/->bare-annotations)
         result (shared-rehydrate/rehydrate-analysis analysis)
         result (if (some? annotations)
                  (assoc result :merged-annotations annotations)
                  result)
         result (if (some? annotations)
                  (restore-dynamic-detected result annotations)
                  result)]
     (narrow-slim result (some? annotations)))))
