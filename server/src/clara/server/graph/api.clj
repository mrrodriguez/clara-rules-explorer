(ns clara.server.graph.api
  "Reitit routes and Ring handler for the Clara Rules Explorer API."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [jsonista.core :as j]
            [schema.core :as s]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clojure.string :as str]))

(defn- json-mapper []
  (j/object-mapper
   {:encode-key-fn true
    :decode-key-fn true}))

(defn- fq-name-from-param [p]
  (let [parts (str/split p #"\.")]
    (if (> (count parts) 1)
      (format "%s/%s"
              (str/join "." (butlast parts))
              (last parts))
      p)))

;; ---------------------------------------------------------------------------
;; Schema definitions for API response bodies
;; ---------------------------------------------------------------------------

(s/defschema RulebaseSummary
  "Basic counts for the dashboard."
  {:rule-count s/Int
   :query-count s/Int
   :fact-type-count s/Int})

(s/defschema ProductionDep
  "A reference to another production (rule or query) in the dependency graph."
  {:name s/Str
   :ns s/Str
   :type s/Str})

(s/defschema LhsCondition
  "A serialized LHS condition from the Clara Rete network.
   Known keys mirror the frontend LhsElement type:
   :type, :constraints, :accumulator, :from, :result-binding, :fact-binding."
  {(s/optional-key :type) s/Any
   (s/optional-key :constraints) s/Str
   (s/optional-key :accumulator) s/Any
   (s/optional-key :from) (s/recursive #'LhsCondition)
   (s/optional-key :result-binding) s/Any
   (s/optional-key :fact-binding) s/Any
   s/Keyword s/Any})

(s/defschema RuleListItem
  "Lightweight rule summary (list endpoint)."
  {:name          s/Str
   :ns            s/Str
   :doc           (s/maybe s/Str)
   :lhs-types     [s/Str]
   :insert-types  [s/Str]
   :retract-types [s/Str]
   :source-rule   s/Bool
   :sink-rule     s/Bool
   (s/optional-key :unlinked-rule) (s/maybe {:downstream (s/enum :unknown)
                                              :reason s/Str})
   (s/optional-key :no-output-types) s/Bool
   (s/optional-key :upstream)   [ProductionDep]
   (s/optional-key :downstream) [ProductionDep]})

(s/defschema Rule
  "Full rule detail with LHS/RHS forms, props, and annotations."
  (merge RuleListItem
         {:props              {s/Str s/Any}
          :lhs                [LhsCondition]
          :rhs-form           s/Str
          :annotation-sources [s/Keyword]
          (s/optional-key :notes) (s/maybe s/Str)}))

(s/defschema QueryListItem
  "Lightweight query summary (list endpoint)."
  {:name      s/Str
   :ns        s/Str
   :doc       (s/maybe s/Str)
   :lhs-types [s/Str]
   :params    (s/maybe #{s/Str})
   (s/optional-key :upstream)   [ProductionDep]
   (s/optional-key :downstream) [ProductionDep]})

(s/defschema Query
  "Full query detail."
  (merge QueryListItem
         {:props              {s/Str s/Any}
          :lhs                [LhsCondition]
          :annotation-sources [s/Keyword]
          (s/optional-key :notes) (s/maybe s/Str)}))

(s/defschema FactTypeListItem
  "Lightweight fact-type summary (list endpoint)."
  {:name               s/Str
   :used-by-rules      [s/Str]
   :used-by-queries    [s/Str]
   :inserted-by-rules  [s/Str]
   :retracted-by-rules [s/Str]})

(s/defschema SessionFactTypeItem
  "A fact-type entry in the session fact-types summary."
  {:name  s/Str
   :count s/Int})

(s/defschema SessionFact
  "A single fact instance in working memory."
  {:id            s/Int
   :type          s/Str
   :data          s/Any
   :is-root       s/Bool
   :inserted-from [ProductionDep]
   :used-by       [ProductionDep]})

(s/defschema ProductionActivity
  "Unified activity view for a rule or query in the current session."
  {:matches [SessionFact]
   (s/optional-key :inserted-facts) [SessionFact]})

(s/defschema FactTypeRoleGroup
  "A grouping of fact instances by a production (rule/query) or root origin."
  {:name  s/Str
   :type  s/Str
   :facts [SessionFact]
   (s/optional-key :ns) s/Str})

(s/defschema SessionFactTypeDetail
  "Full detail for a single fact type in the session, including role groupings."
  {:name          s/Str
   :count         s/Int
   :inserted-from [FactTypeRoleGroup]
   :used-by       [FactTypeRoleGroup]
   :ids           [s/Int]})

;; Internal atoms shape
(s/defschema AnnotationsMap
  {s/Str s/Any})

;; ---------------------------------------------------------------------------
;; Handler helpers
;; ---------------------------------------------------------------------------

(def ^:private ring-error-body
  {:error s/Str})

;; ---------------------------------------------------------------------------
;; Status predicates
;; ---------------------------------------------------------------------------

(defn- status-200? [resp]
  (= 200 (:status resp)))

(defn- status-404? [resp]
  (= 404 (:status resp)))

;; ---------------------------------------------------------------------------
;; Response schemas
;; ---------------------------------------------------------------------------

(s/defschema GetRuleResponse
  (s/conditional status-200? {:status (s/eq 200) :body Rule}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetQueryResponse
  (s/conditional status-200? {:status (s/eq 200) :body Query}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetFactTypeResponse
  (s/conditional status-200? {:status (s/eq 200) :body FactTypeListItem}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetSessionFactTypeResponse
  (s/conditional status-200? {:status (s/eq 200) :body SessionFactTypeDetail}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetSessionFactResponse
  (s/conditional status-200? {:status (s/eq 200) :body SessionFact}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetSessionRuleResponse
  (s/conditional status-200? {:status (s/eq 200) :body ProductionActivity}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(s/defschema GetSessionQueryResponse
  (s/conditional status-200? {:status (s/eq 200) :body ProductionActivity}
                 status-404? {:status (s/eq 404) :body ring-error-body}))

(defn- get-snapshot [session-atom snapshot-cache]
  (let [session @session-atom]
    (if (and @snapshot-cache (= (:session @snapshot-cache) session))
      (:snapshot @snapshot-cache)
      (let [snapshot (memory/session-snapshot session)]
        (reset! snapshot-cache {:session session :snapshot snapshot})
        snapshot))))

(s/defn handle-get-rulebase-summary :- {:status (s/eq 200) :body RulebaseSummary}
  [session-atom annotations-atom _req]
  (let [analysis (core/rulebase-analysis @session-atom @annotations-atom)]
    {:status 200
     :body (core/rulebase-summary analysis)}))

(defn- handle-get-analysis
  [session-atom annotations-atom _req]
  {:status 200
   :body (core/rulebase-analysis @session-atom @annotations-atom)})

(s/defn handle-get-rules :- {:status (s/eq 200) :body {:rules [RuleListItem]}}
  [session-atom annotations-atom _req]
  (let [analysis (core/rulebase-analysis @session-atom @annotations-atom)]
    {:status 200
     :body {:rules (core/rules-list analysis)}}))

(s/defn handle-get-rule :- GetRuleResponse
  [session-atom annotations-atom req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        analysis (core/rulebase-analysis @session-atom @annotations-atom)
        rule (get-in analysis [:rules fq-name])]
    (if rule
      {:status 200 :body rule}
      {:status 404 :body {:error "Rule not found"}})))

(s/defn handle-get-queries :- {:status (s/eq 200) :body {:queries [QueryListItem]}}
  [session-atom annotations-atom _req]
  (let [analysis (core/rulebase-analysis @session-atom @annotations-atom)]
    {:status 200
     :body {:queries (core/queries-list analysis)}}))

(s/defn handle-get-query :- GetQueryResponse
  [session-atom annotations-atom req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        analysis (core/rulebase-analysis @session-atom @annotations-atom)
        query (get-in analysis [:queries fq-name])]
    (if query
      {:status 200 :body query}
      {:status 404 :body {:error "Query not found"}})))

(s/defn handle-get-fact-types :- {:status (s/eq 200) :body {:fact-types [FactTypeListItem]}}
  [session-atom annotations-atom _req]
  (let [analysis (core/rulebase-analysis @session-atom @annotations-atom)]
    {:status 200
     :body {:fact-types (core/fact-types-list analysis)}}))

(s/defn handle-get-fact-type :- GetFactTypeResponse
  [session-atom annotations-atom req]
  (let [p (get-in req [:path-params :fq-name])
        analysis (core/rulebase-analysis @session-atom @annotations-atom)
        fact-types (:fact-types analysis)
        fact-type (or (get fact-types p)
                      (get fact-types (fq-name-from-param p)))]
    (if fact-type
      {:status 200 :body fact-type}
      {:status 404 :body {:error "Fact type not found"}})))

(s/defn handle-get-session-fact-types
  :- {:status (s/eq 200) :body {:types [SessionFactTypeItem] :total-count s/Int}}
  [session-atom snapshot-cache _req]
  (let [snapshot (get-snapshot session-atom snapshot-cache)]
    {:status 200
     :body (core/session-fact-types-summary snapshot)}))

(s/defn handle-get-session-fact-type
  :- GetSessionFactTypeResponse
  [session-atom snapshot-cache req]
  (let [p (get-in req [:path-params :fq-name])
        snapshot (get-snapshot session-atom snapshot-cache)
        fact-types (:fact-types snapshot)
        type-info (or (get fact-types p)
                      (get fact-types (fq-name-from-param p)))]
    (if type-info
      {:status 200 :body type-info}
      {:status 404 :body {:error "Fact type not found in session"}})))

(s/defn handle-get-session-fact
  :- GetSessionFactResponse
  [session-atom snapshot-cache req]
  (let [id (Integer/parseInt (get-in req [:path-params :id]))
        snapshot (get-snapshot session-atom snapshot-cache)
        fact (get-in snapshot [:facts id])]
    (if fact
      {:status 200 :body fact}
      {:status 404 :body {:error "Fact not found in session"}})))

(s/defn handle-get-session-rule
  :- GetSessionRuleResponse
  [session-atom snapshot-cache req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        snapshot (get-snapshot session-atom snapshot-cache)
        rule-activity (memory/get-session-rule-activity snapshot fq-name)]
    (if rule-activity
      {:status 200 :body rule-activity}
      {:status 404 :body {:error "Rule matches not found"}})))

(s/defn handle-get-session-query
  :- GetSessionQueryResponse
  [session-atom snapshot-cache req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        snapshot (get-snapshot session-atom snapshot-cache)
        query-activity (memory/get-session-query-activity snapshot fq-name)]
    (if query-activity
      {:status 200 :body query-activity}
      {:status 404 :body {:error "Query matches not found"}})))

(defn- handle-get-session-snapshot
  [session-atom snapshot-cache _req]
  {:status 200
   :body (get-snapshot session-atom snapshot-cache)})

(s/defn handle-get-annotations :- {:status (s/eq 200) :body AnnotationsMap}
  [_session-atom annotations-atom _req]
  {:status 200
   :body @annotations-atom})

(defn- handle-post-annotations-reload
  [_session-atom _annotations-atom _req]
  {:status 501 :body {:error "Reload not implemented in api.clj (requires config path)"}})

(defn router
  [session-atom annotations-atom]
  (let [snapshot-cache (atom nil)]
    (ring/router
     [["/v1"
       ["/rulebase-summary"
        {:get (partial handle-get-rulebase-summary session-atom annotations-atom)}]

       ["/analysis"
        {:get (partial handle-get-analysis session-atom annotations-atom)}]

       ["/rules"
        [""
         {:get (partial handle-get-rules session-atom annotations-atom)}]
        ["/:fq-name"
         {:get (partial handle-get-rule session-atom annotations-atom)}]]

       ["/queries"
        [""
         {:get (partial handle-get-queries session-atom annotations-atom)}]
        ["/:fq-name"
         {:get (partial handle-get-query session-atom annotations-atom)}]]

       ["/fact-types"
        [""
         {:get (partial handle-get-fact-types session-atom annotations-atom)}]
        ["/:fq-name"
         {:get (partial handle-get-fact-type session-atom annotations-atom)}]]

       ["/session"
        ["/fact-types"
         ["" {:get (partial handle-get-session-fact-types session-atom snapshot-cache)}]
         ["/:fq-name" {:get (partial handle-get-session-fact-type session-atom snapshot-cache)}]]
        ["/facts/:id"
         {:get (partial handle-get-session-fact session-atom snapshot-cache)}]
        ["/rules/:fq-name"
         {:get (partial handle-get-session-rule session-atom snapshot-cache)}]
        ["/queries/:fq-name"
         {:get (partial handle-get-session-query session-atom snapshot-cache)}]]

       ["/session-snapshot"
        {:get (partial handle-get-session-snapshot session-atom snapshot-cache)}]

       ["/annotations"
        [""
         {:get (partial handle-get-annotations session-atom annotations-atom)}]

        ["/reload"
         {:post (partial handle-post-annotations-reload session-atom annotations-atom)}]]]]

     {:data {:muuntaja (m/create
                        (assoc-in m/default-options
                                  [:formats "application/json" :encoder-opts]
                                  {:mapper (json-mapper)}))
             :middleware [muuntaja/format-middleware]}})))

(defn app [session-atom annotations-atom]
  (ring/ring-handler
   (router session-atom annotations-atom)
   (ring/create-default-handler)))
