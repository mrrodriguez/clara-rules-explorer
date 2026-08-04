(ns clara.server.graph.api
  "Reitit routes and Ring handler for the Clara Rules Explorer API."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [jsonista.core :as j]
            [schema.core :as s]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.analyze :as analyze]
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

(s/defschema TypeReference
  "A linkable fact-type reference: `name` is the kind-explicit serialized
   type string (display), `id` the deterministic route id (linkage), and
   `known` distinguishes types linkable in this rulebase (`true`) from
   hierarchy ghosts that render as plain text."
  {:name s/Str
   :id s/Str
   :known s/Bool})

(s/defschema LhsCondition
  "A serialized LHS condition from the Clara Rete network.
   Known keys mirror the frontend LhsElement type:
   :type (a TypeReference), :constraints, :args, :accumulator, :from,
   :result-binding, :fact-binding."
  {(s/optional-key :type) TypeReference
   (s/optional-key :constraints) s/Str
   (s/optional-key :args) s/Str
   (s/optional-key :accumulator) s/Any
   (s/optional-key :from) (s/recursive #'LhsCondition)
   (s/optional-key :result-binding) s/Any
   (s/optional-key :fact-binding) s/Any
   s/Keyword s/Any})

(s/defschema ViaEntry
  "A single entry in a constructor callstack chain."
  {:var-name-sym s/Str})

(s/defschema ViaChain
  "Provenance chain from a boundary fn to a constructor callsite.
   `:source` marks heuristic provenance — `:record-ctor-scan` when the
   callsite comes from the subtree-wide record-ctor scan fallback rather
   than a traced call chain; heuristic entries have no `:callstack`."
  {(s/optional-key :boundary-var-name-sym) s/Str
   (s/optional-key :callstack) [ViaEntry]
   (s/optional-key :source) s/Keyword})

(s/defschema DynamicCallsiteEntry
  "A single dynamic-insert/retract callsite with source coordinates
   and optional resolution info."
  {:source-str s/Str
   :ns s/Str
   :filename s/Str
   (s/optional-key :status) (s/enum :none :partial :full)
   (s/optional-key :resolved-types) [TypeReference]
   (s/optional-key :fact-type) TypeReference
   (s/optional-key :constructor-sym) s/Str
   (s/optional-key :via) ViaChain})

(s/defschema DynamicDetectionInfo
  "Info about dynamic insert/retract callsites detected by the analyzer."
  {(s/optional-key :callsites) [DynamicCallsiteEntry]
   (s/optional-key :resolution) (s/enum :full :partial :none)
   (s/optional-key :fact-instance-derived-types) [s/Str]})

(s/defschema RuleListItem
  "Lightweight rule summary (list endpoint)."
  {:name          s/Str
   :ns            s/Str
   :doc           (s/maybe s/Str)
   :lhs-types     [TypeReference]
   :insert-types  [TypeReference]
   :retract-types [TypeReference]
   :source-rule   s/Bool
   :sink-rule     s/Bool
   (s/optional-key :unlinked-rule) (s/maybe {:downstream (s/enum :unknown)
                                             :reason s/Str})
   (s/optional-key :no-output-types) s/Bool
   (s/optional-key :upstream)   [ProductionDep]
   (s/optional-key :downstream) [ProductionDep]
   (s/optional-key :dynamic-insert-types-detected) DynamicDetectionInfo
   (s/optional-key :dynamic-retract-types-detected) DynamicDetectionInfo})

(s/defschema Rule
  "Full rule detail with LHS/RHS forms, props, and annotations."
  (merge RuleListItem
         {:props              {s/Str s/Any}
          :lhs                [LhsCondition]
          :lhs-form           s/Str
          :rhs-form           s/Str
          (s/optional-key :notes) (s/maybe s/Str)}))

(s/defschema QueryListItem
  "Lightweight query summary (list endpoint)."
  {:name      s/Str
   :ns        s/Str
   :doc       (s/maybe s/Str)
   :lhs-types [TypeReference]
   :params    (s/maybe #{s/Str})
   (s/optional-key :upstream)   [ProductionDep]
   (s/optional-key :downstream) [ProductionDep]})

(s/defschema Query
  "Full query detail."
  (merge QueryListItem
         {:props              {s/Str s/Any}
          :lhs                [LhsCondition]
          :lhs-form           s/Str
          (s/optional-key :notes) (s/maybe s/Str)}))

(s/defschema FactTypeListItem
  "Lightweight fact-type summary (list endpoint).  `:ancestors` is
   detail-only."
  {:name               s/Str
   :id                 s/Str
   :ns                 (s/maybe s/Str)
   :used-by-rules      [ProductionDep]
   :used-by-queries    [ProductionDep]
   :inserted-by-rules  [ProductionDep]
   :retracted-by-rules [ProductionDep]})

(s/defschema FactTypeDetail
  "Full fact-type summary (detail endpoint) — the list shape plus the
   hierarchy-ordered `:ancestors` (TypeReferences; `known: false` ghosts
   render as plain text, never links)."
  (merge FactTypeListItem
         {:ancestors [TypeReference]}))

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

;; Internal atoms shape — the annotations atom holds a MergedAnnotations
;; value (keyword keys; see annotations/merge-layers); bare rule→annotation
;; maps (string keys) are also accepted for callers that do not care about
;; provenance.
(s/defschema AnnotationsMap
  (s/cond-pre {s/Keyword s/Any}
              {s/Str s/Any}))

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
  (s/conditional status-200? {:status (s/eq 200) :body FactTypeDetail}
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
  (let [session @session-atom
        cached @snapshot-cache]
    (if (and cached (= (:session cached) session))
      (:snapshot cached)
      (let [snapshot (memory/session-snapshot session)]
        (reset! snapshot-cache {:session session :snapshot snapshot})
        snapshot))))

(defn- enriched-annotations
  "Returns annotations enriched with fact-type provenance from the session's
   working memory when a live session is available.  Takes a bare
   rule→annotation map (the caller unwraps any MergedAnnotations) and returns
   a bare map."
  [session bare-annotations]
  (if (instance? clara.rules.engine.LocalSession session)
    (analyze/enrich-annotations-from-session session bare-annotations)
    bare-annotations))

(defn- ->bare-annotations
  "Unwraps a MergedAnnotations value to the bare rule→annotation map; bare
   maps pass through.  (Key membership is tested with `some` — a bare map may
   be a string-keyed sorted map, where a keyword `contains?` throws
   ClassCastException.)"
  [x]
  (if (and (map? x) (some #{:annotations} (keys x)))
    (:annotations x)
    x))

(defn- get-analysis-state
  "Returns the cached analysis state (the analysis map plus derived internal
   indexes), rebuilding when the session or annotations have changed.  Each
   detail handler was previously calling core/rulebase-analysis on every
   request, which builds all rules, queries, fact-types, the dep graph, and
   nodes — even to serve a single rule lookup.  The fact-type id reverse
   index is internal: it is never part of the /v1/analysis payload."
  [session-atom annotations-atom analysis-cache]
  (let [session @session-atom
        annotations @annotations-atom
        cached @analysis-cache]
    (if (and cached
             (identical? (:session cached) session)
             (identical? (:annotations cached) annotations))
      cached
      (let [analysis (core/rulebase-analysis
                      session
                      (enriched-annotations session (->bare-annotations annotations)))]
        (reset! analysis-cache
                {:session session
                 :annotations annotations
                 :analysis analysis
                 :fact-type-id-index (core/build-fact-type-id-index analysis)})
        @analysis-cache))))

(defn- get-analysis
  "The cached analysis map (see `get-analysis-state`)."
  [session-atom annotations-atom analysis-cache]
  (:analysis (get-analysis-state session-atom annotations-atom analysis-cache)))

(s/defn handle-get-rulebase-summary :- {:status (s/eq 200) :body RulebaseSummary}
  [session-atom annotations-atom analysis-cache _req]
  {:status 200
   :body (core/rulebase-summary (get-analysis session-atom annotations-atom analysis-cache))})

(defn- handle-get-analysis
  [session-atom annotations-atom analysis-cache _req]
  {:status 200
   :body (get-analysis session-atom annotations-atom analysis-cache)})

(s/defn handle-get-rules :- {:status (s/eq 200) :body {:rules [RuleListItem]}}
  [session-atom annotations-atom analysis-cache _req]
  {:status 200
   :body {:rules (core/rules-list (get-analysis session-atom annotations-atom analysis-cache))}})

(s/defn handle-get-rule :- GetRuleResponse
  [session-atom annotations-atom analysis-cache req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        rule (get-in (get-analysis session-atom annotations-atom analysis-cache) [:rules fq-name])]
    (if rule
      {:status 200 :body rule}
      {:status 404 :body {:error "Rule not found"}})))

(s/defn handle-get-queries :- {:status (s/eq 200) :body {:queries [QueryListItem]}}
  [session-atom annotations-atom analysis-cache _req]
  {:status 200
   :body {:queries (core/queries-list (get-analysis session-atom annotations-atom analysis-cache))}})

(s/defn handle-get-query :- GetQueryResponse
  [session-atom annotations-atom analysis-cache req]
  (let [fq-name (fq-name-from-param (get-in req [:path-params :fq-name]))
        query (get-in (get-analysis session-atom annotations-atom analysis-cache) [:queries fq-name])]
    (if query
      {:status 200 :body query}
      {:status 404 :body {:error "Query not found"}})))

(s/defn handle-get-fact-types :- {:status (s/eq 200) :body {:fact-types [FactTypeListItem]}}
  [session-atom annotations-atom analysis-cache _req]
  {:status 200
   :body {:fact-types (core/fact-types-list (get-analysis session-atom annotations-atom analysis-cache))}})

(s/defn handle-get-fact-type :- GetFactTypeResponse
  [session-atom annotations-atom analysis-cache req]
  (let [id (get-in req [:path-params :id])
        state (get-analysis-state session-atom annotations-atom analysis-cache)
        name (get (:fact-type-id-index state) id)
        fact-type (get-in state [:analysis :fact-types name])]
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

(defn router
  [session-atom annotations-atom analysis-cache]
  (let [snapshot-cache (atom nil)]
    (ring/router
     [["/v1"
       ["/rulebase-summary"
        {:get (partial handle-get-rulebase-summary session-atom annotations-atom analysis-cache)}]

       ["/analysis"
        {:get (partial handle-get-analysis session-atom annotations-atom analysis-cache)}]

       ["/rules"
        [""
         {:get (partial handle-get-rules session-atom annotations-atom analysis-cache)}]
        ["/:fq-name"
         {:get (partial handle-get-rule session-atom annotations-atom analysis-cache)}]]

       ["/queries"
        [""
         {:get (partial handle-get-queries session-atom annotations-atom analysis-cache)}]
        ["/:fq-name"
         {:get (partial handle-get-query session-atom annotations-atom analysis-cache)}]]

       ["/fact-types"
        [""
         {:get (partial handle-get-fact-types session-atom annotations-atom analysis-cache)}]
        ["/:id"
         {:get (partial handle-get-fact-type session-atom annotations-atom analysis-cache)}]]

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
         {:get (partial handle-get-annotations session-atom annotations-atom)}]]]]

     {:data {:muuntaja (m/create
                        (assoc-in m/default-options
                                  [:formats "application/json" :encoder-opts]
                                  {:mapper (json-mapper)}))
             :middleware [muuntaja/format-middleware]}})))

(defn warm-analysis-cache!
  "Eagerly populates the analysis cache.  Call during server startup so the
   first request does not pay the full rulebase-analysis build cost."
  [session-atom annotations-atom analysis-cache]
  (get-analysis session-atom annotations-atom analysis-cache)
  nil)

(defn app
  "Returns {:keys [handler analysis-cache]}."
  [session-atom annotations-atom]
  (let [analysis-cache (atom nil)]
    {:handler (ring/ring-handler
               (router session-atom annotations-atom analysis-cache)
               (ring/create-default-handler))
     :analysis-cache analysis-cache}))
