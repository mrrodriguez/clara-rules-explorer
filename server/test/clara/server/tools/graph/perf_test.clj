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

(defn run-rules!
  "Builds and fires an `n-chain` rule chain. When `n-bulk-facts` is positive,
   inserts that many heavy bulk facts into working memory for memory-analysis
   load testing."
  ([n-chain]
   (run-rules! n-chain 0))
  ([n-chain n-bulk-facts]
   (reset! state-atom {:run-rules-result (time (pgh/run-rules (long n-chain)
                                                              (long n-bulk-facts)))})
   ::done))

(defn run-memory-analysis!
  "Times `memory/->memory-analysis` on the current run-rules session."
  []
  (swap! state-atom
         (fn [{:keys [run-rules-result] :as state}]
           (assoc state
                  :memory-analysis
                  (time (memory/->memory-analysis (:session run-rules-result))))))
  ::done)

(defn run-rule-source-analysis!
  "Run clj-kondo analysis on the session rules.  The resulting rule-source
   analysis carries :var-definitions, :var-usages etc. that
   ->annotations-from-rule-source-analysis needs to find RHS callsites
   (including ->step-fact constructor calls)."
  []
  (swap! state-atom
         (fn [{:keys [run-rules-result] :as state}]
           (assoc state
                  :rule-source-analysis
                  (time (analyze/->rule-source-analysis
                         {:session-or-rulebase (:session run-rules-result)})))))
  ::done)

(defn run-rulebase-analysis! []
  (swap! state-atom
         (fn [{:keys [run-rules-result annotations] :as state}]
           (assoc state
                  :rulebase-analysis
                  (time (core/->rulebase-analysis (:session run-rules-result) annotations)))))
  ::done)

(defn run-annotations-from-rule-source-analysis! []
  (swap! state-atom
         (fn [{:keys [run-rules-result rule-source-analysis] :as state}]
           (assoc state
                  :annotations
                  (time (analyze/->annotations-from-rule-source-analysis
                         {:rule-source-analysis rule-source-analysis
                          :session-or-rulebase (:session run-rules-result)
                          :fact-constructors fact-constructors-spec})))))
  ::done)

(defn run-merge-memory-derived-insert-types! []
  (swap! state-atom
         (fn [{:keys [run-rules-result annotations] :as state}]
           (assoc state
                  :memory-derived-annotations
                  (time (analyze/merge-memory-derived-insert-types
                         annotations
                         (:session run-rules-result))))))
  ::done)
