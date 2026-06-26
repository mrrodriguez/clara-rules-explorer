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

(deftest test-session-fact-types
  (let [session (-> (->test-session)
                    (r/insert (laf/map->Application {:app-id "app-1"}))
                    (r/fire-rules))
        session-atom (atom session)
        annotations-atom (atom {})
        handler (api/app session-atom annotations-atom)]

    (testing "GET /v1/session/fact-types"
      (let [response (handler (mock/request :get "/v1/session/fact-types"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              types (:types body)]
          (is (seq types))
          ;; Use Java class name as currently returned by serialize/serialize-fact-type
          (is (some #(= (:name %) "clara.server.tools.graph.rules.loan_app_facts.Application") types)))))))

(deftest test-session-facts-granular
  (let [app (laf/map->Application {:app-id "app-1"})
        session (-> (->test-session)
                    (r/insert app)
                    (r/fire-rules))
        session-atom (atom session)
        annotations-atom (atom {})
        handler (api/app session-atom annotations-atom)]

    (testing "GET /v1/session/facts/:id"
      ;; First get the snapshot to find an ID
      (let [snapshot-resp (handler (mock/request :get "/v1/session-snapshot"))
            snapshot (parse-json (:body snapshot-resp))
            ;; Be more specific: lookup by type AND app-id
            fact-id (some (fn [[id f]]
                            (when (and (= (:type f) "clara.server.tools.graph.rules.loan_app_facts.Application")
                                       (= (get-in f [:data :app-id]) "app-1"))
                              id))
                          (:facts snapshot))

            ;; Now test the granular endpoint
            response (handler (mock/request :get (str "/v1/session/facts/" (name fact-id))))]

        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (:type body)))
          (is (= "app-1" (get-in body [:data :app-id])))
          (is (contains? body :used-by)))))))

(deftest test-session-rules-activations
  (let [app (laf/map->Application {:app-id "app-1"})
        session (-> (->test-session)
                    (r/insert app)
                    (r/fire-rules))
        session-atom (atom session)
        annotations-atom (atom {})
        handler (api/app session-atom annotations-atom)]

    (testing "GET /v1/session/rules/:fq-name"
      (let [response (handler (mock/request :get "/v1/session/rules/clara.server.tools.graph.rules.loan-doc-rules.collect-app-req-docs"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (seq (:matches body)))
          ;; In minimalist mode, we return inserted-facts
          (is (contains? body :inserted-facts)))))))
