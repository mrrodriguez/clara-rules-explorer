(ns clara.server.graph.cache-test
  "Tests for the analysis/memory-analysis cache, focused on memory-analysis
   reuse: a memory-analysis already produced by memory enrichment must be
   re-stamped (O(facts)) rather than rebuilt (re-inspect + re-sort + re-index)
   on a cache miss."
  (:require [clara.rules :as r]
            [clara.server.graph.cache :as cache]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- ->test-session
  []
  (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                    'clara.server.tools.graph.rules.loan-app-rules)
      (r/insert (laf/map->Application {:app-id "app-1"}))
      (r/fire-rules)))

(defn- ->annotations
  "Bare ->props-layer annotations — the same shape the server threads through state."
  [session]
  (ann.merge/annotations (ann.merge/merge-layers [(ann.merge/->props-layer session)])))

(defn- ->state
  "A session-mode server state map, the shape the cache consumes."
  [session annotations memory-analysis]
  {:session session
   :annotations annotations
   :memory-analysis memory-analysis})

(deftest test-warm-reuses-enrichment-memory-analysis
  (testing "warm! with a memory-analysis stores a re-stamped memory-analysis equal to a fresh build"
    (let [session (->test-session)
          annotations (->annotations session)
          enrichment-memory-analysis (memory/->memory-analysis session)
          analysis (core/->rulebase-analysis session annotations)
          expected (memory/->memory-analysis session (-> analysis :fact-types keys set))
          c (cache/->cache)
          state (->state session annotations enrichment-memory-analysis)]
      (is (every? (comp false? :known :type) (vals (:facts enrichment-memory-analysis)))
          "the enrichment memory-analysis starts with every fact type unknown")
      (cache/warm! c state)
      (is (= expected (cache/get-memory-analysis c state))
          "the request path serves the re-stamped memory-analysis"))))

(deftest test-memory-analysis-miss-reuses-memory-analysis
  (let [session (->test-session)
        annotations (->annotations session)
        enrichment-memory-analysis (memory/->memory-analysis session)
        analysis (core/->rulebase-analysis session annotations)
        expected (memory/->memory-analysis session (-> analysis :fact-types keys set))]
    (testing "a cold-cache miss re-stamps a provided memory-analysis instead of re-inspecting"
      (let [rebuilt? (atom false)
            c (cache/->cache)
            state (->state session annotations enrichment-memory-analysis)]
        (with-redefs [memory/->memory-analysis
                      (fn [_ _]
                        (reset! rebuilt? true)
                        expected)]
          (let [served (cache/get-memory-analysis c state)]
            (is (false? @rebuilt?)
                "a miss with a memory-analysis must re-stamp, not re-inspect")
            (is (= expected served))))))
    (testing "a cold-cache miss without a memory-analysis falls back to a fresh memory-analysis"
      (let [c (cache/->cache)
            state (->state session annotations nil)]
        (is (= expected (cache/get-memory-analysis c state)))))))
