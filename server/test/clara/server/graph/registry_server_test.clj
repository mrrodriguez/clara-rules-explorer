(ns clara.server.graph.registry-server-test
  "Registry-backed serving: `start-system!` with `:registry` composes a selection
  and serves the analysis routes with no live session. Session routes answer
  409 `:no-session`."
  (:require [clara.server.graph.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- parse-json [s]
  (j/read-value s (j/object-mapper {:decode-key-fn true})))

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- ->registry-config []
  {:root (registry-root)
   :units [{:repo "loan-app-ruleset"}
           {:repo "loan-disposition-ruleset"}]})

(deftest start-system-builds-registry-state-test
  (let [system (server/start-system! {:registry (->registry-config) :port 0})
        state @(:state-atom system)]
    (try
      (testing "state holds a composed, rehydrated analysis and no session"
        (is (contains? state :rulebase-analysis))
        (is (nil? (:session state)))
        (is (nil? (:memory-analysis state))))
      (testing "the folded annotations carry the authored dynamic detections the
                analysis routes omit (served via /v1/annotations)"
        (let [approved (get-in state [:annotations :annotations
                                      "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"])]
          (is (contains? approved :clara-rules/dynamic-insert-types-detected))))
      (testing "annotations are the cross-unit layer fold, with provenance"
        (is (contains? (:annotations state) :layers))
        (is (contains? (:annotations state) :provenance)))
      (finally
        (server/stop! system)))))

(deftest registry-serving-routes-test
  (let [system (server/start-system! {:registry (->registry-config) :port 0})
        handler (:handler system)]
    (try
      (testing "rulebase routes answer from the composed, rehydrated analysis"
        (let [resp (handler (mock/request :get "/v1/rulebase-summary"))]
          (is (= 200 (:status resp)))
          (let [body (parse-json (:body resp))]
            (is (pos? (:rule-count body)))
            (is (pos? (:fact-type-count body)))
            (is (false? (:working-memory-available body)))))

        (let [resp (handler (mock/request :get "/v1/rulebase-analysis"))]
          (is (= 200 (:status resp)))
          (let [body (parse-json (:body resp))]
            (is (contains? body :slim) "the :slim block is served")
            (is (not (contains? body :nodes)) "the composition has no Rete network")))

        (let [resp (handler (mock/request :get "/v1/rules"))]
          (is (= 200 (:status resp)))
          (let [body (parse-json (:body resp))
                names (set (map :name (:rules body)))]
            (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"))
            (is (contains? names "clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app"))))

        (let [resp (handler (mock/request :get "/v1/annotations"))]
          (is (= 200 (:status resp)))
          (let [body (parse-json (:body resp))]
            (is (contains? body :annotations))
            (is (contains? body :layers)))))

      (testing "session routes answer 409 :no-session"
        (doseq [path ["/v1/session/fact-types" "/v1/memory-analysis"]]
          (let [resp (handler (mock/request :get path))]
            (is (= 409 (:status resp)))
            (is (= "no-session" (:reason (parse-json (:body resp))))))))

      (finally
        (server/stop! system)))))
