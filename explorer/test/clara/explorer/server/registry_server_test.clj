(ns clara.explorer.server.registry-server-test
  "Registry-backed serving: `start-system!` with `:registry` composes a selection
  and serves the analysis routes with no live session. Session routes answer
  409 `:no-session`."
  (:require [clara.explorer.server.client :as client]
            [clara.explorer.server.serve :as server]
            ;; Loaded only so fq record/class tokens resolve through `ns-resolve`; this namespace
            ;; still serves a composed registry selection, never a live session.
            [clara.explorer.test.rules.loan-app-rules]
            [clara.explorer.test.rules.loan-doc-rules]
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

(deftest navigate-targets-carry-the-composed-unit-test
  (testing "a registry-mode navigate target names the unit it came from"
    (let [system (server/start-system! {:registry (->registry-config) :port 0})]
      (try
        (client/register! system)
        (let [result (client/navigate {:production nil
                                       :side :lhs
                                       :token ":loan-app/application-outcome"})]
          (is (= :producer (:direction result)) result)
          (is (seq (:targets result)) result)
          (is (= #{"loan-app-ruleset"} (into #{} (map :unit) (:targets result)))))
        (finally
          (client/register! nil)
          (server/stop! system))))))

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
                                      "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?"])]
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
            (is (contains? names "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?"))
            (is (contains? names "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app"))))

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

(deftest registry-narrowed-annotations-agree-with-rules-test
  (let [config {:root (registry-root)
                :units [{:repo "loan-app-ruleset"
                         :namespaces ["clara.explorer.test.rules.loan-app-rules"]}
                        {:repo "loan-disposition-ruleset"}]}
        system (server/start-system! {:registry config :port 0})
        handler (:handler system)]
    (try
      (let [rules-body (parse-json (:body (handler (mock/request :get "/v1/rules"))))
            anns-body (parse-json (:body (handler (mock/request :get "/v1/annotations"))))
            rule-names (set (map :name (:rules rules-body)))
            ann-names (set (map #(str (symbol %)) (keys (:annotations anns-body))))]
        (testing "the narrowed scope serves the same rules and annotations"
          (is (seq rule-names))
          (is (= rule-names ann-names)))
        (testing "the filtered-out namespace is absent from both routes"
          (is (contains? rule-names "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?"))
          (is (not (contains? rule-names "clara.explorer.test.rules.loan-doc-rules/extract-doc-meta-rule")))
          (is (not (contains? ann-names "clara.explorer.test.rules.loan-doc-rules/extract-doc-meta-rule")))))
      (finally
        (server/stop! system)))))

;; ---------------------------------------------------------------------------
;; Fact type provenance over the composed two-unit selection
;; ---------------------------------------------------------------------------

(def ^:private outcome-keyword ":loan-app/application-outcome")
(def ^:private outcome-record
  "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome")
(def ^:private application-type
  "clara.explorer.test.rules.loan_app_facts.Application")
(def ^:private document-check-type
  "clara.explorer.test.rules.loan_app_facts.DocumentCheck")
(def ^:private stale-notice-type
  "clara.explorer.test.rules.loan_doc_rules.StaleDocumentNotice")
(def ^:private app-approved
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?")
(def ^:private app-denied
  "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?")
(def ^:private app-pending
  "clara.explorer.test.rules.loan-app-rules/app-outcome-pending?")
(def ^:private args-demo
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved-args-demo")
(def ^:private find-outcome
  "clara.explorer.test.rules.loan-app-rules/find-app-outcome")
(def ^:private notice-approved
  "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app")
(def ^:private notice-denied
  "clara.explorer.test.rules.loan-outcome-notices/notice-denied-app")
(def ^:private retract-stale
  "clara.explorer.test.rules.loan-doc-rules/dynamic-retract-stale-notice")

(def ^:private caller-ns "clara.explorer.test.rules.loan-app-rules")

(defn- with-registry-client
  "Runs F with the composed two-unit registry registered as the client's current system."
  [f]
  (let [system (server/start-system! {:registry (->registry-config) :port 0})]
    (try
      (client/register! system)
      (f)
      (finally
        (client/register! nil)
        (server/stop! system)))))

(defn- provenance
  [input]
  (client/fact-type-provenance
   (merge {:direction :producer :depth 0 :ancestors []} input)))

(defn- cursor-root
  ([type-name direction depth]
   (cursor-root type-name direction depth {}))
  ([type-name direction depth input]
   (:node (provenance {:node {:kind :cursor
                              :input (merge {:token type-name :caller-ns caller-ns} input)}
                       :direction direction
                       :depth depth}))))

(defn- by-name
  [nodes]
  (into {} (map (juxt :name identity)) nodes))

(defn- production-nodes
  [node]
  (concat (when (= :production (:kind node)) [node])
          (mapcat production-nodes (:children node))))

(deftest provenance-producer-root-crosses-the-keyword-test
  (with-registry-client
    (fn []
      (let [root (cursor-root outcome-keyword :producer 0)]
        (is (= :fact-type (:kind root)))
        (is (= [app-approved app-denied app-pending] (mapv :name (:children root))))
        (testing "each producer reaches the keyword through the record"
          (doseq [p (:children root)]
            (is (= :insert (:via p)))
            (is (= [outcome-record] (mapv :name (:matches p))))
            (is (true? (:bridge? p)))
            (is (= "loan-app-ruleset" (:unit p)))))))))

(deftest provenance-consumer-root-crosses-the-keyword-test
  (with-registry-client
    (fn []
      (let [root (cursor-root outcome-record :consumer 0)
            children (by-name (:children root))]
        (testing "the downstream unit's consumers reach the record through the keyword"
          (doseq [p-name [notice-approved notice-denied]]
            (is (contains? children p-name))
            (is (= [outcome-keyword] (mapv :name (:matches (children p-name)))))
            (is (true? (:bridge? (children p-name))))
            (is (= "loan-disposition-ruleset" (:unit (children p-name))))))
        (testing "negated matches are consumers too"
          (is (contains? children app-denied))
          (is (contains? children app-pending)))
        (testing "a query is a leaf"
          (is (= "query" (:type (children find-outcome))))
          (is (= [] (:children (children find-outcome)))))
        (testing "a declared no-outputs rule is a leaf"
          (is (= [] (:children (children args-demo))))
          (is (not (contains? (children args-demo) :outputs))))))))

(deftest provenance-producer-root-with-no-producer-is-external-test
  (with-registry-client
    (fn []
      (let [root (cursor-root application-type :producer 0)]
        (is (= :fact-type (:kind root)))
        (is (true? (:external? root)))
        (is (= [] (:children root)))))))

(deftest provenance-consumer-retract-only-production-is-not-a-leaf-test
  (with-registry-client
    (fn []
      (let [root (cursor-root document-check-type :consumer 1)
            rule (first (filter #(= retract-stale (:name %)) (production-nodes root)))
            stale (first (filter #(= stale-notice-type (:type %)) (:children rule)))]
        (is (some? rule))
        (is (contains? rule :children) "a retract-only production is not a leaf")
        (is (= [:retract] (:vias stale)) "the retract side is kept")
        (is (= [] (:children stale)))))))

(deftest provenance-production-root-marks-its-self-loop-test
  (with-registry-client
    (fn []
      (let [root (:node (provenance {:node {:kind :production :name app-denied}
                                     :direction :producer
                                     :depth 0
                                     :ancestors []}))
            outcome-node (first (filter #(= outcome-record (:type %)) (:children root)))
            children (by-name (:children outcome-node))]
        (is (= :production (:kind root)))
        (is (not (contains? root :matches)) "a root production has no parent type")
        (is (not (contains? root :via)))
        (is (true? (:cycle? (children app-denied))))
        (is (not (contains? (children app-denied) :children)))
        (is (contains? children app-approved))
        (is (not (contains? (children app-approved) :children))
            "a non-cycle producer stays expandable at depth 0")))))

(deftest provenance-cursor-inside-a-production-is-not-hidden-test
  (with-registry-client
    (fn []
      (let [root (cursor-root outcome-record :consumer 0
                              {:production args-demo
                               :side :lhs
                               :caller-ns "clara.explorer.test.rules.loan-app-rules"})]
        (is (contains? (set (map :name (:children root))) args-demo)
            "the production the cursor sits in is still a consumer")))))
