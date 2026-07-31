(ns clara.server.graph.smoke-test
  (:require [clara.rules :as r]
            [clj-http.client :as client]
            [jsonista.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.java.io :as io]))

(def ^:dynamic *port* 9001)

(defn ->url
  [path]
  (format "http://localhost:%s/v1%s" *port* path))

(defn run-app-outcome-approved
  [session]
  (-> session
      (r/insert (laf/map->Application {:app-id "app-1"})
                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :paycheck})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :bank-statement})
                (laf/map->IdentityCheck {:app-id "app-1" :status :pass})
                (laf/map->FraudCheck {:app-id "app-1" :status :pass}))
      (r/fire-rules)))

(def ^:private loan-doc-annotations-path
  (some-> (io/resource "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn")
          .getPath))

(defn run-rules
  ([]
   (run-rules {:with-facts? true}))
  ([{:keys [with-facts?]}]
   (cond-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                         'clara.server.tools.graph.rules.loan-app-rules)
     with-facts? run-app-outcome-approved)))

(defn run-smoke-test
  ([]
   (run-smoke-test {:session-opts {:with-facts? true}}))
  ([{:keys [session-opts]}]
   (let [session (run-rules session-opts)
         server (server/start! {:port *port* :session session :layers [loan-doc-annotations-path]})]
     server)))

(defn get-rules []
  (-> (client/get (->url "/rules") {:accept :json})
      :body
      json/read-value))

(defn get-fact-types []
  (-> (client/get (->url "/fact-types") {:accept :json})
      :body
      json/read-value))

(defn get-session-snapshot []
  (-> (client/get (->url "/session-snapshot") {:accept :json})
      :body
      json/read-value))

(defn get-session-fact-types []
  (-> (client/get (->url "/session/fact-types") {:accept :json})
      :body
      json/read-value))

(defn get-rulebase-summary []
  (-> (client/get (->url "/rulebase-summary") {:accept :json})
      :body
      json/read-value))

(defn get-analysis []
  (-> (client/get (->url "/analysis") {:accept :json})
      :body
      json/read-value))

(defn get-rule [fq-name]
  (-> (client/get (->url (str "/rules/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-queries []
  (-> (client/get (->url "/queries") {:accept :json})
      :body
      json/read-value))

(defn get-query [fq-name]
  (-> (client/get (->url (str "/queries/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-fact-type [fq-name]
  (-> (client/get (->url (str "/fact-types/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-session-fact-type [fq-name]
  (-> (client/get (->url (str "/session/fact-types/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-session-fact [id]
  (-> (client/get (->url (str "/session/facts/" id)) {:accept :json})
      :body
      json/read-value))

(defn get-session-rule [fq-name]
  (-> (client/get (->url (str "/session/rules/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-session-query [fq-name]
  (-> (client/get (->url (str "/session/queries/" fq-name)) {:accept :json})
      :body
      json/read-value))

(defn get-annotations []
  (-> (client/get (->url "/annotations") {:accept :json})
      :body
      json/read-value))

(defn post-annotations-reload []
  (-> (client/post (->url "/annotations/reload") {:accept :json})
      :status))

(def ^:dynamic *server* nil)

(defn with-server [f]
  (binding [*port* 19001]
    (let [server (run-smoke-test)]
      (binding [*server* server]
        (try
          (f)
          (finally
            (server/stop!)))))))

(use-fixtures :once with-server)

(deftest test-rulebase-analysis-endpoints
  (testing "Summary and analysis"
    (let [summary (get-rulebase-summary)
          analysis (get-analysis)]
      (is (some? summary))
      (is (some? analysis))))

  (testing "Rules endpoints"
    (let [rules (get-rules)
          rule (get-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved%3F")]
      (is (seq rules))
      (is (= "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?" (get rule "name")))))

  (testing "Queries endpoints"
    (let [queries (get-queries)
          query (get-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome")]
      (is (seq queries))
      (is (= "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome" (get query "name")))))

  (testing "Fact types endpoints"
    (let [fact-types (get-fact-types)
          fact-type (get-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
      (is (seq fact-types))
      (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get fact-type "name"))))))

(deftest test-session-state-endpoints
  (testing "Session snapshot and facts"
    (let [ss (get-session-snapshot)
          session-fact-types (get-session-fact-types)
          session-fact-type (get-session-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
      (is (some? ss))
      (is (seq session-fact-types))
      (is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get session-fact-type "name")))

      (testing "Individual fact retrieval"
        (let [fact-id (ffirst (get ss "facts"))
              fact (get-session-fact fact-id)]
          (is (some? fact))
          (is (= (str fact-id) (str (get fact "id"))))))))

  (testing "Session rules and queries"
    (let [session-rule (get-session-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved%3F")
          session-query (get-session-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome")]
      (is (some? session-rule))
      (is (some? session-query)))))

(deftest test-annotations-endpoints
  (testing "Annotations retrieval and reload"
    (let [annotations (get-annotations)
          annotations-reload (post-annotations-reload)]
      (is (some? annotations))
      (is (= 200 annotations-reload)))))

(comment
  ;; Start the server with a pre-populated session
  (def s (run-smoke-test))

  (def s (run-smoke-test {:session-opts {:with-facts? false}}))

  (.stop s)

  ;; --- Rulebase analysis (static, no session required) ---

  (def summary (get-rulebase-summary))
  (def analysis (get-analysis))

  (def rules (get-rules))
  (def rule (get-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved%3F"))
  (def rule (get-rule "clara.server.tools.graph.rules.loan-doc-rules.dynamic-insert-audit-trail"))
  (def rule (get-rule "clara.server.tools.graph.rules.loan-doc-rules.dynamic-insert-compliance-review"))

  (def queries (get-queries))
  (def query (get-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome"))

  (def fact-types (get-fact-types))
  (def fact-type (get-fact-type "clara.server.tools.graph.rules.loan-app-facts.Application"))

  ;; --- Session state (requires running session) ---

  (def ss (clara.server.graph.smoke-test/get-session-snapshot))

  (def session-fact-types (get-session-fact-types))
  (def session-fact-type (get-session-fact-type "clara.server.tools.graph.rules.loan-app-facts.Application"))

  ;; Pick a fact id from the snapshot, e.g.:
  (def fact (get-session-fact (ffirst (get ss "facts"))))

  (def session-rule (get-session-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved%3F"))
  (def session-query (get-session-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome"))

  ;; --- Annotations ---

  (def annotations (get-annotations))
  (def annotations-reload (post-annotations-reload))

  ::done)
