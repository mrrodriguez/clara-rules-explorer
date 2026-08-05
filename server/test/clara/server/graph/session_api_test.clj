(ns clara.server.graph.session-api-test
  (:require [clara.rules :as r]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [jsonista.core :as j]))

(defn- parse-json [s]
  (j/read-value s (j/object-mapper {:decode-key-fn true})))

(defn- ->test-session []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->handler []
  (let [session (-> (->test-session)
                    (r/insert (laf/map->Application {:app-id "app-1"}))
                    (r/fire-rules))]
    (:handler (api/app (atom session) (atom {})))))

(defn- id-for
  "Given an id→name reverse index (parsed from JSON, so id keys are
   keywords), returns the string id of the named entry, or nil."
  [id-index target-name]
  (some (fn [[id n]]
          (when (= n target-name)
            (if (keyword? id) (clojure.core/name id) id)))
        id-index))

(deftest test-session-fact-types
  (let [handler (->handler)]
    (testing "GET /v1/session/fact-types — list with :id and :ns"
      (let [response (handler (mock/request :get "/v1/session/fact-types"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              types (:types body)]
          (is (seq types))
          (is (every? :id types))
          (is (contains? (set (map :name types))
                         "clara.server.tools.graph.rules.loan_app_facts.Application")))))

    (testing "GET /v1/session/fact-types/:id"
      (let [snapshot (parse-json (:body (handler (mock/request :get "/v1/session-snapshot"))))
            app-id (id-for (:fact-type-id-index snapshot)
                           "clara.server.tools.graph.rules.loan_app_facts.Application")
            response (handler (mock/request :get (str "/v1/session/fact-types/" app-id)))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (:name body)))
          (is (= (:id body) app-id)))))))

(deftest test-session-facts-granular
  (let [handler (->handler)]
    (testing "GET /v1/session/facts/:id"
      ;; First get the snapshot to find an ID
      (let [snapshot-resp (handler (mock/request :get "/v1/session-snapshot"))
            snapshot (parse-json (:body snapshot-resp))
            ;; Be more specific: lookup by type AND app-id
            fact-id (some (fn [[id f]]
                            (when (and (= (get-in f [:type :name])
                                          "clara.server.tools.graph.rules.loan_app_facts.Application")
                                       (= (get-in f [:data :app-id]) "app-1"))
                              id))
                          (:facts snapshot))

            ;; Now test the granular endpoint
            response (handler (mock/request :get (str "/v1/session/facts/" (name fact-id))))]

        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application"
                 (get-in body [:type :name])))
          (is (= "app-1" (get-in body [:data :app-id])))
          (is (contains? body :used-by)))))))

(deftest test-session-rules-activations
  (let [handler (->handler)]
    (testing "GET /v1/session/rules/:id"
      (let [snapshot (parse-json (:body (handler (mock/request :get "/v1/session-snapshot"))))
            rule-id (id-for (:rule-id-index snapshot)
                            "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")
            response (handler (mock/request :get (str "/v1/session/rules/" rule-id)))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (seq (:matches body)))
          (is (contains? body :inserted-facts)))))

    (testing "Name-based session rule lookup 404s (id-only resolution)"
      (is (= 404 (:status (handler (mock/request :get
                                                 "/v1/session/rules/clara.server.tools.graph.rules.loan-doc-rules.collect-app-req-docs"))))))))
