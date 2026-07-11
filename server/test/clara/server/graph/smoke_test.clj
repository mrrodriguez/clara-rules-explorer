(ns clara.server.graph.smoke-test
  (:require [clara.rules :as r]
            [clj-http.client
             :as client]
            [jsonista.core :as json]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.java.io :as io]))

(def port 9001)

(defn ->url
  [path]
  (format "http://localhost:%s/v1%s" port path))

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

(defn run-smoke-test []
  (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                  'clara.server.tools.graph.rules.loan-app-rules)
                    run-app-outcome-approved)
        server (server/start! {:port port :session session :annotations-file loan-doc-annotations-path})]
    server))

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
  (let [server (run-smoke-test)]
    (binding [*server* server]
      (try
        (f)
        (finally
          (server/stop!))))))

(clojure.test/use-fixtures :once with-server)

(clojure.test/deftest test-rulebase-analysis-endpoints
  (clojure.test/testing "Summary and analysis"
    (let [summary (get-rulebase-summary)
          analysis (get-analysis)]
      (clojure.test/is (some? summary))
      (clojure.test/is (some? analysis))))
      
  (clojure.test/testing "Rules endpoints"
    (let [rules (get-rules)
          rule (get-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved")]
      (clojure.test/is (seq rules))
      (clojure.test/is (= "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved" (get rule "name")))))

  (clojure.test/testing "Queries endpoints"
    (let [queries (get-queries)
          query (get-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome")]
      (clojure.test/is (seq queries))
      (clojure.test/is (= "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome" (get query "name")))))

  (clojure.test/testing "Fact types endpoints"
    (let [fact-types (get-fact-types)
          fact-type (get-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
      (clojure.test/is (seq fact-types))
      (clojure.test/is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get fact-type "name"))))))

(clojure.test/deftest test-session-state-endpoints
  (clojure.test/testing "Session snapshot and facts"
    (let [ss (get-session-snapshot)
          session-fact-types (get-session-fact-types)
          session-fact-type (get-session-fact-type "clara.server.tools.graph.rules.loan_app_facts.Application")]
      (clojure.test/is (some? ss))
      (clojure.test/is (seq session-fact-types))
      (clojure.test/is (= "clara.server.tools.graph.rules.loan_app_facts.Application" (get session-fact-type "name")))
      
      (clojure.test/testing "Individual fact retrieval"
        (let [fact-id (ffirst (get ss "facts"))
              fact (get-session-fact fact-id)]
          (clojure.test/is (some? fact))
          (clojure.test/is (= (str fact-id) (str (get fact "id"))))))))

  (clojure.test/testing "Session rules and queries"
    (let [session-rule (get-session-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved")
          session-query (get-session-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome")]
      (clojure.test/is (some? session-rule))
      (clojure.test/is (some? session-query)))))

(clojure.test/deftest test-annotations-endpoints
  (clojure.test/testing "Annotations retrieval and reload"
    (let [annotations (get-annotations)
          annotations-reload (post-annotations-reload)]
      (clojure.test/is (some? annotations))
      (clojure.test/is (= 200 annotations-reload)))))

(comment
  ;; Start the server with a pre-populated session
  (def s (run-smoke-test))

  ;; --- Rulebase analysis (static, no session required) ---

  (def summary (get-rulebase-summary))
  (def analysis (get-analysis))

  (def rules (get-rules))
  (def rule (get-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved"))

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

  (def session-rule (get-session-rule "clara.server.tools.graph.rules.loan-app-rules.app-outcome-approved"))
  (def session-query (get-session-query "clara.server.tools.graph.rules.loan-app-rules.find-app-outcome"))

  ;; --- Annotations ---

  (def annotations (get-annotations))
  (def annotations-reload (post-annotations-reload))

  ::done)
