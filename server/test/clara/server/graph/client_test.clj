(ns clara.server.graph.client-test
  (:require [clara.rules :as r]
            [clara.server.graph.cache :as cache]
            [clara.server.graph.client :as client]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.annotation-fixtures :as fixtures]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.rules.analyze-test-rules]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clara.server.tools.graph.rules.loan-hierarchy-rules :as lhr]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(def ^:private rules-prefix "clara.server.tools.graph.rules")
(def ^:private loan-app "clara.server.tools.graph.rules.loan-app-rules")
(def ^:private atr "clara.server.tools.graph.rules.analyze-test-rules")

;; ---------------------------------------------------------------------------
;; System helpers — the client only needs :state-atom and :cache; no Jetty.
;; ---------------------------------------------------------------------------

(defn- ->system [session annotations]
  ;; Bare annotations + :analyze-cache make the state schema-valid (ServerState),
  ;; so `server/swap-session!` / `reload-annotations!` also accept it.
  {:state-atom (atom {:session session
                      :annotations (ann.merge/->bare-annotations annotations)
                      :analyze-cache {}})
   :cache (cache/->cache)})

(defn- register! [session annotations]
  (client/register! (->system session annotations)))

;; ---------------------------------------------------------------------------
;; Fixtures (computed once per namespace load)
;; ---------------------------------------------------------------------------

(def ^:private loan-app-session
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(def ^:private loan-app-annotations
  (fixtures/loan-doc-merged-annotations loan-app-session))

;; analyze-test-rules + loan-doc-rules: DocumentCheck is retracted by the
;; edge-case rules and consumed by loan-doc-rules, so retract coupling and
;; global-consumer paths have real targets.
(def ^:private combined-session
  (r/mk-session 'clara.server.tools.graph.rules.analyze-test-rules
                'clara.server.tools.graph.rules.loan-doc-rules))

(def ^:private combined-analysis
  (analyze/->rule-source-analysis
   {:session-or-rulebase combined-session
    :include-ns-prefixes [rules-prefix]}))

(def ^:private combined-annotations
  (analyze/->annotations-from-rule-source-analysis
   {:rule-source-analysis combined-analysis
    :session-or-rulebase combined-session}))

(def ^:private hierarchy-session
  (r/mk-session 'clara.server.tools.graph.rules.loan-hierarchy-rules
                :fact-type-fn lhr/fact-type-fn))

(def ^:private hierarchy-annotations
  (ann.merge/merge-layers [(ann.merge/props-layer hierarchy-session)]))

;; ---------------------------------------------------------------------------
;; Producer path (LHS)
;; ---------------------------------------------------------------------------

(deftest test-producer-single-target
  (register! loan-app-session loan-app-annotations)
  (let [result (client/navigate {:production (str loan-app "/app-outcome-approved?")
                                 :side :lhs
                                 :token "DocumentCheck"})]
    (is (= :producer (:direction result)))
    (is (= "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
           (:type result)))
    (is (= ["clara.server.tools.graph.rules.loan-doc-rules/app-has-all-required-docs"]
           (mapv :name (:targets result))))
    (let [target (first (:targets result))]
      (is (= "rule" (:type target)))
      (is (= :insert (:via target)))
      (is (true? (:var? (:source target))))
      (is (str/ends-with? (:file (:source target)) "loan_doc_rules.clj")))))

(deftest test-producer-zero-targets-errors
  (register! loan-app-session loan-app-annotations)
  (let [result (client/navigate {:production (str loan-app "/app-outcome-pending?")
                                 :side :lhs
                                 :token "Application"})]
    (is (str/includes? (:error result) "no producer of"))
    (is (str/includes? (:error result)
                       "clara.server.tools.graph.rules.loan_app_facts.Application"))
    (is (str/includes? (:error result)
                       (str loan-app "/app-outcome-pending?")))))

(deftest test-producer-auto-resolved-keyword
  (register! hierarchy-session hierarchy-annotations)
  (let [result (client/navigate
                {:production "clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document"
                 :side :lhs
                 :token "::supporting-document"})]
    (is (= :producer (:direction result)))
    (is (= ":clara.server.tools.graph.rules.loan-hierarchy-rules/supporting-document"
           (:type result)))
    (is (= ["clara.server.tools.graph.rules.loan-hierarchy-rules/insert-income-document"]
           (mapv :name (:targets result))))))

;; ---------------------------------------------------------------------------
;; Consumer path (RHS)
;; ---------------------------------------------------------------------------

(deftest test-consumer-multi-target-deterministic-ordering
  (register! loan-app-session loan-app-annotations)
  (let [result (client/navigate {:production (str loan-app "/app-outcome-approved?")
                                 :side :rhs
                                 :token "map->ApplicationOutcome"})]
    (is (= :consumer (:direction result)))
    (is (= "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome"
           (:type result)))
    (let [names (mapv :name (:targets result))]
      (is (= (sort names) names) "targets sorted by fq name")
      (is (contains? (set names)
                     "clara.server.tools.graph.rules.loan-app-rules/app-outcome-denied?"))
      (is (contains? (set names)
                     "clara.server.tools.graph.rules.loan-app-rules/find-app-outcome"))
      (is (< 1 (count names)) "multi-target case"))))

(deftest test-consumer-retract-via-flag
  (register! combined-session combined-annotations)
  (let [result (client/navigate {:production (str atr "/rule-retract-java-dot")
                                 :side :rhs
                                 :token "DocumentCheck."})]
    (is (= :consumer (:direction result)))
    (is (seq (:targets result)))
    (is (every? #(= :retract (:via %)) (:targets result)))))

(deftest test-consumer-java-ctor-tokens
  (register! combined-session combined-annotations)
  (doseq [token ["DocumentCheck." "DocumentCheck/new"]]
    (testing (str "token " token)
      (let [result (client/navigate {:production (str atr "/rule-java-constructor-dot")
                                     :side :rhs
                                     :token token})]
        (is (= :consumer (:direction result)))
        (is (= "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
               (:type result)))
        (is (seq (:targets result)))))))

;; ---------------------------------------------------------------------------
;; Global path (outside a defrule/defquery)
;; ---------------------------------------------------------------------------

(deftest test-global-consumers-callsite-linked-ctor
  (register! combined-session combined-annotations)
  (let [result (client/navigate {:production nil
                                 :caller-ns atr
                                 :token "laf/map->DocumentCheck"})]
    (is (= :type (:direction result)))
    (is (nil? (:production result)))
    (is (= "clara.server.tools.graph.rules.loan_app_facts.DocumentCheck"
           (:type result)))
    (is (seq (:targets result)))
    (is (contains? (set (map :name (:targets result)))
                   "clara.server.tools.graph.rules.loan-doc-rules/find-document-check"))))

(deftest test-global-unresolvable-token-errors
  (register! combined-session combined-annotations)
  (let [result (client/navigate {:production nil
                                 :caller-ns atr
                                 :token "map->DocumentCheck"})]
    ;; Unqualified "map->DocumentCheck" is not resolvable in analyze-test-rules
    ;; (the helper uses the laf/ alias), so no fact type is found.
    (is (str/includes? (:error result) "no fact type found"))))

;; ---------------------------------------------------------------------------
;; Error paths
;; ---------------------------------------------------------------------------

(deftest test-query-has-no-rhs
  (register! loan-app-session loan-app-annotations)
  (let [result (client/navigate {:production (str loan-app "/find-app-outcome")
                                 :side :rhs
                                 :token "ApplicationOutcome"})]
    (is (str/includes? (:error result) "queries have no RHS"))))

(deftest test-unknown-production-errors
  (register! loan-app-session loan-app-annotations)
  (let [result (client/navigate {:production (str loan-app "/no-such-rule")
                                 :side :lhs
                                 :token "Application"})]
    (is (str/includes? (:error result) "no production named"))))

(deftest test-no-system-registered-errors
  (with-redefs [client/get-current-system (constantly nil)]
    (is (= {:error "no explorer system registered"}
           (client/navigate {:production (str loan-app "/find-app-outcome")
                             :side :lhs
                             :token "Application"})))))

;; ---------------------------------------------------------------------------
;; get-production-source
;; ---------------------------------------------------------------------------

(deftest test-get-production-source-var-metadata
  (is (true? (:var? (client/get-production-source
                     "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"))))
  (is (false? (:var? (client/get-production-source "no.such.ns/not-a-rule")))))

(deftest test-session-swap-reflected-in-navigation
  (let [sys (->system combined-session combined-annotations)]
    (client/register! sys)
    (is (= :consumer
           (:direction (client/navigate {:production (str atr "/rule-retract-java-dot")
                                         :side :rhs
                                         :token "DocumentCheck."}))))
    ;; Swap to a session without analyze-test-rules; the production is gone.
    (server/swap-session! sys {:session loan-app-session
                               :annotations loan-app-annotations})
    (is (str/includes?
         (:error (client/navigate {:production (str atr "/rule-retract-java-dot")
                                   :side :rhs
                                   :token "DocumentCheck."}))
         "no production named"))))
