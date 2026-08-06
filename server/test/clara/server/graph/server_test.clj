(ns clara.server.graph.server-test
  "Tests for server lifecycle and hot-swap-session functionality."
  (:require [clara.rules :as r]
            [clara.rules.engine :as eng]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clj-http.client :as client]
            [jsonista.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *port* 19003)

(defn- ->url [path]
  (format "http://localhost:%s/v1%s" *port* path))

(defn- ->test-session
  "A bare session (no facts inserted) over the loan-doc + loan-app rulesets."
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->test-session-with-facts
  "A session with a single Application inserted and rules fired."
  []
  (-> (->test-session)
      (r/insert (laf/map->Application {:app-id "app-1"}))
      (r/fire-rules)))

(defn- ->rulebase
  "Extracts the raw rulebase from a session."
  [session]
  (-> session eng/components :rulebase))

(defn- start-server!
  "Starts a server on *port* with the given session and annotations layers."
  ([session layers]
   (server/start! {:port *port*
                   :session session
                   :layers layers
                   :working-memory-enabled true}))
  ([session]
   (start-server! session [])))

;; ---------------------------------------------------------------------------
;; Fixture — one server started, tests exercise swap-session! against it
;; ---------------------------------------------------------------------------

(def ^:private test-session
  "A bare session (no facts) for fixture setup."
  (delay (->test-session)))

(defn- server-fixture
  "Start a server once, run tests, then stop."
  [f]
  (start-server! @test-session)
  (try
    (f)
    (finally
      (server/stop!))))

(use-fixtures :once st/validate-schemas server-fixture)

;; ---------------------------------------------------------------------------
;; Helper — parse JSON body
;; ---------------------------------------------------------------------------

(defn- parse-body [resp]
  (json/read-value (:body resp) (json/object-mapper {:decode-key-fn true})))

;; ---------------------------------------------------------------------------
;; swap-session! :session only
;; ---------------------------------------------------------------------------

(deftest test-swap-session-session-only
  (testing "Providing :session swaps the session atom; /v1/rulebase-summary reflects it"
    (let [session-b (->test-session-with-facts)
          result (server/swap-session! {:session session-b :warm-cache? true})]
      ;; swap-session! returns the new annotations map (recomputed from new session)
      (is (map? result))

      ;; HTTP: the server now sees the swapped-in session with working memory
      (let [summary (-> (client/get (->url "/rulebase-summary") {:accept :json})
                        :body
                        (json/read-value (json/object-mapper {:decode-key-fn true})))]
        (is (true? (:working-memory-available summary))
            "swapped-in session has working memory"))

      ;; Session-snapshot should now return 200 (was 409 before swap for bare session)
      (let [snap-resp (client/get (->url "/session-snapshot") {:accept :json})]
        (is (= 200 (:status snap-resp)))))))

;; ---------------------------------------------------------------------------
;; swap-session! :annotations only — bare map
;; ---------------------------------------------------------------------------

(deftest test-swap-session-annotations-bare-map
  (testing "Providing :annotations as a bare map updates /v1/annotations"
    (let [new-annos {"some-rule" {:clara-rules/notes "hello"}}
          result (server/swap-session! {:annotations new-annos})]
      ;; Return value is the annotations map
      (is (= new-annos result))

      ;; HTTP endpoint reflects the new annotations
      (let [resp (-> (client/get (->url "/annotations") {:accept :json})
                     parse-body)]
        (is (= "hello"
               (get-in resp [:some-rule :clara-rules/notes])))))))

;; ---------------------------------------------------------------------------
;; swap-session! :annotations only — MergedAnnotations
;; ---------------------------------------------------------------------------

(deftest test-swap-session-annotations-merged
  (testing "Providing :annotations as MergedAnnotations unwraps to bare map"
    (let [merged {:annotations {"r1" {:clara-rules/notes "merged"}}
                  :provenance {"r1" {:clara-rules/notes :derived}}}
          result (server/swap-session! {:annotations merged})]
      ;; Unwrapped
      (is (= {"r1" {:clara-rules/notes "merged"}} result))

      (let [resp (-> (client/get (->url "/annotations") {:accept :json})
                     parse-body)]
        (is (= "merged"
               (get-in resp [:r1 :clara-rules/notes])))))))

;; ---------------------------------------------------------------------------
;; swap-session! :annotations only — vector of Layers
;; ---------------------------------------------------------------------------

(deftest test-swap-session-annotations-layers-vector
  (testing "Providing :annotations as a vector of Layers merges them"
    (let [layer [{:id :test-layer
                  :annotations
                  {"clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
                   {:clara-rules/notes "from test layer"}}}]
          result (server/swap-session! {:annotations layer})]
      (is (map? result))
      (is (contains? result
                     "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"))

      (let [resp (-> (client/get (->url "/annotations") {:accept :json})
                     parse-body)]
        (is (= "from test layer"
               (get-in resp [:clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?
                             :clara-rules/notes])))))))

;; ---------------------------------------------------------------------------
;; swap-session! :session + :annotations
;; ---------------------------------------------------------------------------

(deftest test-swap-session-both
  (testing "Providing both :session and :annotations updates both"
    (let [session-b (->test-session-with-facts)
          new-annos {"rule-x" {:clara-rules/notes "both-test"}}
          result (server/swap-session! {:session session-b
                                        :annotations new-annos})]
      ;; Session: working memory available now
      (let [summary (-> (client/get (->url "/rulebase-summary") {:accept :json})
                        :body
                        (json/read-value (json/object-mapper {:decode-key-fn true})))]
        (is (true? (:working-memory-available summary))))

      ;; Annotations: our custom ones are served (not the recomputed ones from
      ;; session props, since we supplied explicit annotations)
      (is (= new-annos result))
      (let [resp (-> (client/get (->url "/annotations") {:accept :json})
                     parse-body)]
        (is (= "both-test"
               (get-in resp [:rule-x :clara-rules/notes])))))))

;; ---------------------------------------------------------------------------
;; swap-session! error case
;; ---------------------------------------------------------------------------

(deftest test-swap-session-requires-args
  (testing "swap-session! throws when neither :session nor :annotations provided"
    (is (thrown? IllegalArgumentException
                 (server/swap-session! {})))
    (is (thrown? IllegalArgumentException
                 (server/swap-session! {:enrich-from-session? true})))))

;; ---------------------------------------------------------------------------
;; swap-session! with rulebase (no working memory)
;; ---------------------------------------------------------------------------

(deftest test-swap-session-rulebase-enrich-noop
  (testing "swap-session! to a rulebase: enrichment is a no-op, WM routes 409"
    (let [rulebase (->rulebase (->test-session))
          result (server/swap-session! {:session rulebase
                                        :enrich-from-session? true})]
      (is (map? result))
      ;; WM routes now return 409 because the session became a rulebase
      (let [resp (client/get (->url "/session-snapshot")
                             {:accept :json :throw-exceptions? false})]
        (is (= 409 (:status resp)))
        (is (= "rulebase-input"
               (-> resp :body (json/read-value (json/object-mapper {:decode-key-fn true})) :reason))))
      ;; Rulebase routes still work
      (let [resp (client/get (->url "/rulebase-summary") {:accept :json})]
        (is (= 200 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; swap-session! return value
;; ---------------------------------------------------------------------------

(deftest test-swap-session-returns-annotations
  (testing "swap-session! always returns the annotations-atom value"
    (let [annos {"r1" {:clara-rules/notes "return-test"}}
          result (server/swap-session! {:annotations annos})]
      (is (= annos result))
      (let [resp (-> (client/get (->url "/annotations") {:accept :json})
                     parse-body)]
        ;; Same content served from HTTP endpoint
        (is (= "return-test"
               (get-in resp [:r1 :clara-rules/notes])))))))
