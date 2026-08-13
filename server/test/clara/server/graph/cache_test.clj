(ns clara.server.graph.cache-test
  "Tests for the analysis/snapshot cache, focused on memory-snapshot reuse:
   a working-memory snapshot already produced by memory enrichment must be
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
  "Bare props-layer annotations — the same shape the server threads through state."
  [session]
  (ann.merge/annotations (ann.merge/merge-layers [(ann.merge/props-layer session)])))

(deftest test-warm-reuses-enrichment-snapshot
  (testing "warm! with a memory-snapshot stores a re-stamped snapshot equal to a fresh build"
    (let [session (->test-session)
          annotations (->annotations session)
          enrichment-snapshot (memory/session-snapshot session)
          expected (memory/session-snapshot-from-analysis
                    session (core/rulebase-analysis session annotations))
          c (cache/create)]
      (is (every? (comp false? :known :type) (vals (:facts enrichment-snapshot)))
          "the enrichment snapshot starts with every fact type unknown")
      (cache/warm! c session annotations enrichment-snapshot)
      (is (= expected (cache/snapshot c session annotations enrichment-snapshot))
          "the request path serves the re-stamped snapshot"))))

(deftest test-snapshot-miss-reuses-memory-snapshot
  (let [session (->test-session)
        annotations (->annotations session)
        enrichment-snapshot (memory/session-snapshot session)
        expected (memory/session-snapshot-from-analysis
                  session (core/rulebase-analysis session annotations))]
    (testing "a cold-cache miss re-stamps a provided memory-snapshot instead of re-inspecting"
      (let [rebuilt? (atom false)
            c (cache/create)]
        (with-redefs [memory/session-snapshot-from-analysis
                      (fn [_ _]
                        (reset! rebuilt? true)
                        expected)]
          (let [served (cache/snapshot c session annotations enrichment-snapshot)]
            (is (false? @rebuilt?)
                "a miss with a memory-snapshot must re-stamp, not re-inspect")
            (is (= expected served))))))
    (testing "a cold-cache miss without a memory-snapshot falls back to a fresh snapshot"
      (let [c (cache/create)]
        (is (= expected (cache/snapshot c session annotations nil)))))))
