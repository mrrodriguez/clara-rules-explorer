(ns clara.server.tools.graph.perf-test
  (:require
   [clara.server.tools.graph.analyze :as analyze]
   [clara.server.tools.graph.core :as core]
   [clara.server.tools.graph.memory :as memory]
   [clara.server.tools.graph.rules.perf-gen-helpers :as pgh]))

(def ^:private ->step-fact-sym
  'clara.server.tools.graph.rules.perf-gen-helpers/->step-fact)

(defn- ->step-fact-match-fn
  [sym]
  (= ->step-fact-sym sym))

(defn- ->step-fact-type-resolver
  "Resolves fact types from ->step-fact callsites. The keyword argument IS the fact type."
  [{:keys [arg-form]}]
  (when (and (seq? arg-form)
             (= 2 (count arg-form)))
    {:resolved-types [(second arg-form)]}))

(def ^:private fact-constructors-spec
  [{:match-fn ->step-fact-match-fn
    :type-resolver-fn ->step-fact-type-resolver}])

(defonce state-atom
  (atom {}))

(defn run-session!
  "Builds and fires an `n-chain` rule chain. When `n-bulk-facts` is positive,
   inserts that many heavy bulk facts into working memory for snapshot-load
   testing."
  ([n-chain]
   (run-session! n-chain 0))
  ([n-chain n-bulk-facts]
   (reset! state-atom {:run-rules-result (time (pgh/run-rules (long n-chain)
                                                              (long n-bulk-facts)))})
   ::done))

(defn run-session-snapshot!
  "Times `memory/session-snapshot` on the current run-rules session."
  []
  (swap! state-atom
         (fn [{:keys [run-rules-result] :as state}]
           (assoc state
                  :session-snapshot
                  (time (memory/session-snapshot (:session run-rules-result))))))
  ::done)

(defn run-analyze-session-rules!
  "Run clj-kondo analysis on the session rules.  The resulting analysis map
   carries :var-definitions, :var-usages etc. that generate-annotations-from-analysis
   needs to find RHS callsites (including ->step-fact constructor calls)."
  []
  (swap! state-atom
         (fn [{:keys [run-rules-result] :as state}]
           (assoc state
                  :session-rules-analysis
                  (time (analyze/analyze-session-rules
                         {:session-or-rulebase (:session run-rules-result)})))))
  ::done)

(defn run-analysis! []
  (swap! state-atom
         (fn [{:keys [run-rules-result annotations] :as state}]
           (assoc state
                  :analysis
                  (time (core/rulebase-analysis (:session run-rules-result) annotations)))))
  ::done)

(defn run-generate-annotations-from-analysis! []
  (swap! state-atom
         (fn [{:keys [run-rules-result session-rules-analysis] :as state}]
           (assoc state
                  :annotations
                  (time (analyze/generate-annotations-from-analysis
                         {:analysis session-rules-analysis
                          :session-or-rulebase (:session run-rules-result)
                          :fact-constructors fact-constructors-spec})))))
  ::done)

(defn run-enrich-annotations-from-session! []
  (swap! state-atom
         (fn [{:keys [run-rules-result annotations] :as state}]
           (assoc state
                  :memory-enriched-annotations
                  (time (analyze/enrich-annotations-from-session (:session run-rules-result)
                                                                 annotations)))))
  ::done)

