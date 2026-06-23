(ns clara.server.graph.api-test
  (:require [clara.rules :as r]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.annotations :as ann]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- parse-json [s]
  (j/read-value s (j/object-mapper {:decode-key-fn true})))

(def ^:private loan-doc-annotations
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath
          ann/load-sidecar))

(defn- ->test-session []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->handler
  ([] (->handler (->test-session)))
  ([session]
   (api/app (atom session) (atom loan-doc-annotations))))

(deftest test-not-found
  (let [handler (->handler)]
    (testing "404 for missing rule"
      (let [response (handler (mock/request :get "/v1/rules/non.existent.rule"))]
        (is (= 404 (:status response)))))))

(deftest test-v1-rulebase-summary
  (let [handler (->handler)]
    (testing "GET /v1/rulebase-summary"
      (let [response (handler (mock/request :get "/v1/rulebase-summary"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (number? (:rule-count body)))
          (is (number? (:query-count body)))
          (is (number? (:fact-type-count body))))))))

(deftest test-v1-analysis
  (let [handler (->handler)]
    (testing "GET /v1/analysis"
      (let [response (handler (mock/request :get "/v1/analysis"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (contains? body :rules))
          (is (contains? body :nodes)))))))

(deftest test-v1-rules
  (let [handler (->handler)]
    (testing "GET /v1/rules"
      (let [response (handler (mock/request :get "/v1/rules"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              rules (:rules body)
              names (set (map :name rules))]
          (is (vector? rules))
          (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"))
          (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"))
          (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input"))
          (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/collect-all-missing-required-docs"))
          (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"))
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved"))
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied"))
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending")))))))

(deftest test-v1-rules-fq-name
  (let [handler (->handler)]
    (testing "GET /v1/rules/:fq-name"
      (let [response (handler (mock/request :get "/v1/rules/clara.server.tools.graph.rules.loan-doc-rules.collect-app-given-docs"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs" (:name body)))
          (is (seq (:downstream body))))))))

(deftest test-v1-fact-types
  (let [handler (->handler)]
    (testing "GET /v1/fact-types"
      (let [response (handler (mock/request :get "/v1/fact-types"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              fact-types (:fact-types body)]
          (is (vector? fact-types))
          (is (seq fact-types))
          (is (some #{"clara.server.tools.graph.rules.loan_app_facts.Application"} (map :name fact-types))))))

    (testing "GET /v1/fact-types/:fq-name"
      (let [response (handler (mock/request :get "/v1/fact-types/clara.server.tools.graph.rules.loan_app_facts.Application"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (:name body)))
          (is (seq (:used-by-rules body))))))))

(deftest test-v1-session-snapshot
  (let [session (-> (->test-session)
                    (r/insert (clara.server.tools.graph.rules.loan_app_facts.Application. "app-1"))
                    (r/fire-rules))
        handler (->handler session)]
    (testing "GET /v1/session-snapshot"
      (let [response (handler (mock/request :get "/v1/session-snapshot"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (contains? body :fact-types))
          (is (contains? body :facts))
          (is (contains? body :used-by))
          (is (contains? body :origin))
          (is (seq (:facts body))))))))
