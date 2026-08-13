(ns clara.server.graph.session-api-test
  (:require [clara.rules :as r]
            [clara.rules.engine :as eng]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [jsonista.core :as j]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- parse-json [s]
  (j/read-value s (j/object-mapper {:decode-key-fn true})))

(defn- ->test-session []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->handler []
  (let [session (-> (->test-session)
                    (r/insert (laf/map->Application {:app-id "app-1"}))
                    (r/fire-rules))]
    (:handler (api/app (atom {:session session :annotations {}}) true))))

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

;; ---------------------------------------------------------------------------
;; Rulebase-only: working-memory routes return 409
;; ---------------------------------------------------------------------------

(deftest test-rulebase-only-409
  ;; NOTE: flag=true matches the shipped start! wiring for rulebase input:
  ;; start! passes the raw :working-memory-enabled flag (default true) and
  ;; the 409 is attributed dynamically by with-snapshot (:rulebase-input).
  (let [rulebase (-> (->test-session) eng/components :rulebase)
        handler (:handler (api/app (atom {:session rulebase :annotations {}}) true))]

    (testing "GET /v1/session/fact-types → 409"
      (let [resp (handler (mock/request :get "/v1/session/fact-types"))]
        (is (= 409 (:status resp)))
        (let [body (parse-json (:body resp))]
          (is (= "rulebase-input" (:reason body)))
          (is (string? (:error body)) "error should be a string"))))

    (testing "GET /v1/session-snapshot → 409"
      (let [resp (handler (mock/request :get "/v1/session-snapshot"))]
        (is (= 409 (:status resp)))
        (let [body (parse-json (:body resp))]
          (is (= "rulebase-input" (:reason body))))))

    (testing "GET /v1/session/rules/:id → 409"
      (let [resp (handler (mock/request :get "/v1/session/rules/some-rule"))]
        (is (= 409 (:status resp)))
        (is (= "rulebase-input" (:reason (parse-json (:body resp)))))))

    (testing "Rulebase routes still 200"
      (is (= 200 (:status (handler (mock/request :get "/v1/rulebase-summary")))))
      (is (= 200 (:status (handler (mock/request :get "/v1/analysis"))))))))

(deftest test-rulebase-summary-working-memory-flag
  (testing "RulebaseSummary :working-memory-available is false for rulebase"
    (let [rulebase (-> (->test-session) eng/components :rulebase)
          handler (:handler (api/app (atom {:session rulebase :annotations {}}) true))
          resp (handler (mock/request :get "/v1/rulebase-summary"))
          body (parse-json (:body resp))]
      (is (= 200 (:status resp)))
      (is (false? (:working-memory-available body)))))

  (testing "RulebaseSummary :working-memory-available is true for live session"
    (let [handler (->handler)
          resp (handler (mock/request :get "/v1/rulebase-summary"))
          body (parse-json (:body resp))]
      (is (= 200 (:status resp)))
      (is (true? (:working-memory-available body)))))

  (testing ":working-memory-available is effective state: false for live session + opt-out"
    (let [session (-> (->test-session)
                      (r/insert (laf/map->Application {:app-id "app-1"}))
                      (r/fire-rules))
          handler (:handler (api/app (atom {:session session :annotations {}}) false))
          resp (handler (mock/request :get "/v1/rulebase-summary"))
          body (parse-json (:body resp))]
      (is (= 200 (:status resp)))
      (is (false? (:working-memory-available body))
          "flag reflects whether working-memory routes are served, not mere capability"))))

;; ---------------------------------------------------------------------------
;; Explicit opt-out: live session + :working-memory-enabled false
;; ---------------------------------------------------------------------------

(deftest test-working-memory-opt-out-409
  (let [session (-> (->test-session)
                    (r/insert (laf/map->Application {:app-id "app-1"}))
                    (r/fire-rules))
        handler (:handler (api/app (atom {:session session :annotations {}}) false))]

    (testing "session routes 409 with :disabled-by-config despite live session"
      (doseq [uri ["/v1/session/fact-types"
                   "/v1/session-snapshot"
                   "/v1/session/rules/some-rule"
                   "/v1/session/queries/some-query"
                   "/v1/session/facts/0"
                   "/v1/session/fact-types/some-type"]]
        (let [resp (handler (mock/request :get uri))]
          (is (= 409 (:status resp)) (str uri " must 409"))
          (is (= "disabled-by-config" (:reason (parse-json (:body resp))))
              (str uri " must attribute the cause to the config flag")))))

    (testing "rulebase routes still 200 under opt-out"
      (is (= 200 (:status (handler (mock/request :get "/v1/rulebase-summary")))))
      (is (= 200 (:status (handler (mock/request :get "/v1/rules"))))))))
