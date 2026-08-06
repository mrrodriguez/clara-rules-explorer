(ns clara.server.graph.api-test
  (:require [clara.rules :as r]
            [clara.server.graph.api :as api]
            [clara.server.tools.graph.annotation-fixtures :as fixtures]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as j]
            [ring.mock.request :as mock]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(defn- parse-json [s]
  (j/read-value s (j/object-mapper {:decode-key-fn true})))

(defn- loan-doc-annotations
  [session]
  (fixtures/loan-doc-merged-annotations session))

(defn- ->test-session []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->handler
  ([] (->handler (->test-session)))
  ([session]
   (:handler (api/app (atom session) (atom (loan-doc-annotations session)) true))))

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
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"))
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"))
          (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?")))))))

(deftest test-v1-rules-id
  (let [handler (->handler)
        rule-name "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"
        rules (:rules (parse-json (:body (handler (mock/request :get "/v1/rules")))))
        rule-id (:id (first (filter #(= rule-name (:name %)) rules)))]
    (testing "GET /v1/rules/:id"
      (let [response (handler (mock/request :get (str "/v1/rules/" rule-id)))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= rule-name (:name body)))
          (is (seq (:downstream body))))))

    (testing "Name-based rule lookup 404s (id-only resolution)"
      (is (= 404 (:status (handler (mock/request :get
                                                 "/v1/rules/clara.server.tools.graph.rules.loan-doc-rules.collect-app-given-docs"))))))

    (testing "Unknown rule ids 404"
      (is (= 404 (:status (handler (mock/request :get "/v1/rules/not-a-real-rule-id"))))))))

(deftest test-v1-queries
  (let [handler (->handler)]
    (testing "GET /v1/queries — list with :ns populated"
      (let [response (handler (mock/request :get "/v1/queries"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              queries (:queries body)]
          (is (vector? queries))
          (is (= 2 (count queries)))
          ;; Verify names are present
          (let [names (set (map :name queries))]
            (is (contains? names "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"))
            (is (contains? names "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome")))
          ;; Verify :id is present and :ns populated for every query
          (doseq [q queries]
            (is (string? (:id q)))
            (is (string? (:ns q)))
            (is (not (str/blank? (:ns q)))
                (str "Expected non-blank :ns for query " (:name q)))))))

    (testing "GET /v1/queries/:id — detail"
      (let [query-name "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"
            query-id (:id (first (filter #(= query-name (:name %))
                                         (:queries (parse-json (:body (handler (mock/request :get "/v1/queries"))))))))
            response (handler (mock/request :get (str "/v1/queries/" query-id)))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= query-name (:name body)))
          (is (= "clara.server.tools.graph.rules.loan-doc-rules" (:ns body)))
          (is (seq (:lhs-types body)))
          (is (contains? (set (:params body)) "?app-id"))
          (is (seq (:lhs body))))))))

(deftest test-v1-fact-types
  (let [handler (->handler)]
    (testing "GET /v1/fact-types"
      (let [response (handler (mock/request :get "/v1/fact-types"))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))
              fact-types (:fact-types body)]
          (is (vector? fact-types))
          (is (seq fact-types))
          (is (every? :id fact-types) "list payload carries :id")
          (is (not-any? :ancestors fact-types) "list payload omits :ancestors")
          (is (some #{"clara.server.tools.graph.rules.loan_app_facts.Application"} (map :name fact-types))))))

    (testing "GET /v1/fact-types/:id (id from list payload)"
      (let [fact-types (:fact-types (parse-json (:body (handler (mock/request :get "/v1/fact-types")))))
            app (first (filter #(= "clara.server.tools.graph.rules.loan_app_facts.Application" (:name %))
                               fact-types))
            response (handler (mock/request :get (str "/v1/fact-types/" (:id app))))]
        (is (= 200 (:status response)))
        (let [body (parse-json (:body response))]
          (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (:name body)))
          (is (seq (:used-by-rules body)))
          (is (seq (:ancestors body)) "detail carries :ancestors"))))

    (testing "Name-based lookup 404s (id-only resolution)"
      (let [response (handler (mock/request :get "/v1/fact-types/clara.server.tools.graph.rules.loan_app_facts.Application"))]
        (is (= 404 (:status response)))))))

(deftest test-v1-fact-type-id-lookups
  (let [session (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                              :fact-type-fn lhr/fact-type-fn)
        handler (:handler (api/app (atom session)
                                   (atom (ann.merge/merge-layers
                                          [(ann.merge/props-layer session)])) true))]
    (testing "Every fact type (class, keyword, tuple) resolves by its server-issued id"
      (let [items (:fact-types (parse-json (:body (handler (mock/request :get "/v1/fact-types")))))]
        (doseq [{type-name :name type-id :id} items]
          (let [resp (handler (mock/request :get (str "/v1/fact-types/" type-id)))
                body (parse-json (:body resp))]
            (is (= 200 (:status resp)) (str "id lookup for " type-name))
            (is (= type-name (:name body)) (str "detail name matches for " type-name))
            (is (contains? body :ancestors))))))

    (testing "Raw serialized names 404 (id-only resolution)"
      (doseq [name ["clara.server.tools.graph.rules.loan_hierarchy_rules.LoanApplication"
                    ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document"]]
        (is (= 404 (:status (handler (mock/request :get (str "/v1/fact-types/" name)))))
            (str "name-based lookup must 404 for " name))))

    (testing "Unknown fact-type ids 404"
      (is (= 404 (:status (handler (mock/request :get "/v1/fact-types/not-a-real-fact-type-id"))))))))

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

(deftest test-session-snapshot-known-tracks-session-swap
  (testing "After the host swaps the session atom, the snapshot known-set is recomputed against the new session's analysis — never served stale"
    (let [loan-app-application "clara.server.tools.graph.rules.loan_app_facts.Application"
          income-document ":clara.server.tools.graph.rules.loan-hierarchy-rules/income-document"
          session-a (-> (->test-session)
                        (r/insert (clara.server.tools.graph.rules.loan_app_facts.Application. "app-1"))
                        (r/fire-rules))
          ;; A session whose analysis lacks the loan-app Application type but
          ;; contains its own keyword hierarchy (a different ruleset + fact-type-fn).
          session-b (-> (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                                      :fact-type-fn lhr/fact-type-fn)
                        (r/insert (clara.server.tools.graph.rules.loan_hierarchy_rules.LoanApplication. "app-2" :pending))
                        (r/insert (clara.server.tools.graph.rules.loan_app_facts.Application. "app-1"))
                        (r/fire-rules))
          session-atom (atom session-a)
          annotations-atom (atom (loan-doc-annotations session-a))
          {:keys [handler]} (api/app session-atom annotations-atom true)]
      ;; Warm the analysis for session A so the pre-fix stale known-set is non-empty.
      (is (= 200 (:status (handler (mock/request :get "/v1/analysis")))))
      ;; Host application swaps in the new session + its annotations (the documented
      ;; atom-swap feature).
      (reset! session-atom session-b)
      (reset! annotations-atom (loan-doc-annotations session-b))
      (let [snapshot (parse-json (:body (handler (mock/request :get "/v1/session-snapshot"))))
            facts (vals (:facts snapshot))
            app-fact (some #(when (= loan-app-application (get-in % [:type :name])) %) facts)
            income-doc-fact (some #(when (= income-document (get-in % [:type :name])) %) facts)]
        (is (some? app-fact) "the swapped-in session's memory holds the loan-app Application fact")
        (is (false? (:known (:type app-fact)))
            "a type absent from the new session's analysis is honestly unknown — not a stale known: true dead link")
        (is (some? income-doc-fact) "the swapped-in session produced its keyword-derived fact")
        (is (true? (:known (:type income-doc-fact)))
            "a type present in the new session's analysis is known — the known-set was recomputed, not served stale")
        ;; The session-snapshot request itself rebuilt the analysis against session B.
        (let [analysis (parse-json (:body (handler (mock/request :get "/v1/analysis"))))
              analysis-names (set (map :name (:fact-types analysis)))]
          (is (not (contains? analysis-names loan-app-application))
              "the analysis cache now reflects the swapped-in session B"))))))
