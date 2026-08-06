(ns clara.server.graph.api
  "Reitit routes and Ring handler for the Clara Rules Explorer API."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [jsonista.core :as j]
            [schema.core :as s]
            [clara.server.graph.cache :as cache]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.fact-types :as ft]
            [clara.server.tools.graph.memory :as memory]))

(defn- json-mapper []
  (j/object-mapper
   {:encode-key-fn true
    :decode-key-fn true}))

;; ---------------------------------------------------------------------------
;; Schema definitions for API response bodies
;; ---------------------------------------------------------------------------

(s/defschema RulebaseSummary
  "Basic counts for the dashboard."
  {:rule-count s/Int
   :query-count s/Int
   :fact-type-count s/Int
   :working-memory-available s/Bool})

(s/defschema TypeReference
  "A linkable fact-type reference: `name` is the kind-explicit serialized
   type string (display), `id` the deterministic route id (linkage), and
   `known` distinguishes types linkable in this rulebase (`true`) from
   hierarchy ghosts that render as plain text."
  {:name s/Str
   :id s/Str
   :known s/Bool})

(s/defschema TypeBridgeMatch
  "A single type pair linking two productions: `producer-type` is what the
   producing rule inserts (or retracts), `consumer-type` is what the
   consuming rule's LHS requires.  Identical shape and meaning on upstream
   and downstream entries — direct matches (same type both ends) are
   included.  `:via :retract` marks a pair whose producer-type is a retract
   type of the producer (retraction coupling, distinct from production)."
  {:producer-type TypeReference
   :consumer-type TypeReference
   (s/optional-key :via) (s/enum :retract)})

(s/defschema ProductionDep
  "A reference to another production (rule or query) in the dependency graph.
   `id` is the deterministic route id for linkage.  `match` (when present)
   lists the type pairs that link the two productions."
  {:name s/Str
   :id s/Str
   :ns s/Str
   :type s/Str
   (s/optional-key :match) [TypeBridgeMatch]})

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
   :id            s/Str
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
   :id        s/Str
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
   :id    s/Str
   :ns    (s/maybe s/Str)
   :count s/Int})

(s/defschema SessionFact
  "A single fact instance in working memory."
  {:id            s/Int
   :type          TypeReference
   :ns            (s/maybe s/Str)
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
   :id    s/Str
   :type  s/Str
   :facts [SessionFact]
   (s/optional-key :ns) s/Str})

(s/defschema SessionFactTypeDetail
  "Full detail for a single fact type in the session, including role groupings."
  {:name          s/Str
   :id            s/Str
   :ns            (s/maybe s/Str)
   :count         s/Int
   :inserted-from [FactTypeRoleGroup]
   :used-by       [FactTypeRoleGroup]
   :ids           [s/Int]})

;; Internal atoms shape — the annotations atom holds a MergedAnnotations
;; value (keyword keys; see annotations/merge-layers); bare rule→annotation
;; maps (string keys) are also accepted for callers that do not care about
;; provenance.
(s/defschema AnnotationsMap
  "Either a MergedAnnotations value (keyword keys, mixed value types) or
   a bare rule→annotation map (string keys, all values are maps)."
  (s/pred (fn [m]
            (and (map? m)
                 (or (ann.merge/merged-annotations? m)
                     (and (every? string? (keys m))
                          (every? map? (vals m))))))
          'annotations-map?))

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

(defn- status-409? [resp]
  (= 409 (:status resp)))

(def ^:private no-working-memory-body
  (assoc ring-error-body :reason s/Keyword))

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
                 status-404? {:status (s/eq 404) :body ring-error-body}
                 status-409? {:status (s/eq 409) :body no-working-memory-body}))

(s/defschema GetSessionFactResponse
  (s/conditional status-200? {:status (s/eq 200) :body SessionFact}
                 status-404? {:status (s/eq 404) :body ring-error-body}
                 status-409? {:status (s/eq 409) :body no-working-memory-body}))

(s/defschema GetSessionRuleResponse
  (s/conditional status-200? {:status (s/eq 200) :body ProductionActivity}
                 status-404? {:status (s/eq 404) :body ring-error-body}
                 status-409? {:status (s/eq 409) :body no-working-memory-body}))

(s/defschema GetSessionQueryResponse
  (s/conditional status-200? {:status (s/eq 200) :body ProductionActivity}
                 status-404? {:status (s/eq 404) :body ring-error-body}
                 status-409? {:status (s/eq 409) :body no-working-memory-body}))

(s/defn handle-get-rulebase-summary :- {:status (s/eq 200) :body RulebaseSummary}
  [session-atom annotations-atom cache working-memory-enabled? _req]
  {:status 200
   :body (assoc (core/rulebase-summary (cache/analysis cache session-atom annotations-atom))
                ;; effective state: working-memory routes are served only when
                ;; the session is a live session AND the config flag allows it
                :working-memory-available (boolean
                                           (and working-memory-enabled?
                                                (core/working-memory-available? @session-atom))))})

(defn- handle-get-analysis
  [session-atom annotations-atom cache _req]
  {:status 200
   :body (core/analysis-result (cache/analysis cache session-atom annotations-atom))})

(s/defn handle-get-rules :- {:status (s/eq 200) :body {:rules [RuleListItem]}}
  [session-atom annotations-atom cache _req]
  {:status 200
   :body {:rules (core/rules-list (cache/analysis cache session-atom annotations-atom))}})

(s/defn handle-get-rule :- GetRuleResponse
  [session-atom annotations-atom cache req]
  (let [id (get-in req [:path-params :id])
        analysis (cache/analysis cache session-atom annotations-atom)
        name (get (:production-id-index analysis) id)
        rule (get-in analysis [:rules name])]
    (if rule
      {:status 200 :body rule}
      {:status 404 :body {:error "Rule not found"}})))

(s/defn handle-get-queries :- {:status (s/eq 200) :body {:queries [QueryListItem]}}
  [session-atom annotations-atom cache _req]
  {:status 200
   :body {:queries (core/queries-list (cache/analysis cache session-atom annotations-atom))}})

(s/defn handle-get-query :- GetQueryResponse
  [session-atom annotations-atom cache req]
  (let [id (get-in req [:path-params :id])
        analysis (cache/analysis cache session-atom annotations-atom)
        name (get (:production-id-index analysis) id)
        query (get-in analysis [:queries name])]
    (if query
      {:status 200 :body query}
      {:status 404 :body {:error "Query not found"}})))

(s/defn handle-get-fact-types :- {:status (s/eq 200) :body {:fact-types [FactTypeListItem]}}
  [session-atom annotations-atom cache _req]
  {:status 200
   :body {:fact-types (ft/fact-types-list (cache/analysis cache session-atom annotations-atom))}})

(s/defn handle-get-fact-type :- GetFactTypeResponse
  [session-atom annotations-atom cache req]
  (let [id (get-in req [:path-params :id])
        analysis (cache/analysis cache session-atom annotations-atom)
        name (get (:fact-type-id-index analysis) id)
        fact-type (get-in analysis [:fact-types name])]
    (if fact-type
      {:status 200 :body fact-type}
      {:status 404 :body {:error "Fact type not found"}})))

(defn- no-working-memory-response
  "Returns a 409 with a machine-readable `:reason` key.
   `cause` is :rulebase-input or :disabled-by-config."
  [cause]
  (let [messages {:rulebase-input "No working memory: the server was started with a rulebase, not a session"
                  :disabled-by-config "No working memory: disabled by configuration (:working-memory-enabled false)"}]
    {:status 409
     :body {:error (get messages cause "No working memory")
            :reason cause}}))

(defn- with-snapshot
  "Invokes `f` with the session snapshot, or returns 409 :rulebase-input
   when the session is a rulebase (no working memory).  Session capability
   is checked per request because the session atom can be hot-swapped at
   runtime; the static `:working-memory-enabled` config flag is resolved
   once at router construction instead (see `router`)."
  [session-atom cache annotations-atom f]
  (if-let [snapshot (cache/snapshot cache session-atom annotations-atom)]
    (f snapshot)
    (no-working-memory-response :rulebase-input)))

(s/defn handle-get-session-fact-types
  :- {:status (s/cond-pre (s/eq 200) (s/eq 409))
      :body (s/conditional
             #(= 200 (:status %))
             {:types [SessionFactTypeItem] :total-count s/Int}
             #(= 409 (:status %))
             no-working-memory-body)}
  [session-atom cache annotations-atom _req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      {:status 200
       :body (ft/session-fact-types-summary snapshot)})))

(s/defn handle-get-session-fact-type
  :- GetSessionFactTypeResponse
  [session-atom cache annotations-atom req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      (let [id (get-in req [:path-params :id])
            name (get (:fact-type-id-index snapshot) id)
            type-info (get (:fact-types snapshot) name)]
        (if type-info
          {:status 200 :body type-info}
          {:status 404 :body {:error "Fact type not found in session"}})))))

(s/defn handle-get-session-fact
  :- GetSessionFactResponse
  [session-atom cache annotations-atom req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      (let [id (Integer/parseInt (get-in req [:path-params :id]))
            fact (get-in snapshot [:facts id])]
        (if fact
          {:status 200 :body fact}
          {:status 404 :body {:error "Fact not found in session"}})))))

(s/defn handle-get-session-rule
  :- GetSessionRuleResponse
  [session-atom cache annotations-atom req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      (let [id (get-in req [:path-params :id])
            name (get (:rule-id-index snapshot) id)
            rule-activity (memory/get-session-rule-activity snapshot name)]
        (if rule-activity
          {:status 200 :body rule-activity}
          {:status 404 :body {:error "Rule matches not found"}})))))

(s/defn handle-get-session-query
  :- GetSessionQueryResponse
  [session-atom cache annotations-atom req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      (let [id (get-in req [:path-params :id])
            name (get (:query-id-index snapshot) id)
            query-activity (memory/get-session-query-activity snapshot name)]
        (if query-activity
          {:status 200 :body query-activity}
          {:status 404 :body {:error "Query matches not found"}})))))

(defn- handle-get-session-snapshot
  [session-atom cache annotations-atom _req]
  (with-snapshot session-atom cache annotations-atom
    (fn [snapshot]
      {:status 200
       :body (dissoc snapshot :fact-raw-types)})))

(s/defn handle-get-annotations :- {:status (s/eq 200) :body AnnotationsMap}
  [_session-atom annotations-atom _req]
  {:status 200
   :body @annotations-atom})

(defn router
  [session-atom annotations-atom cache working-memory-enabled?]
  (let [;; The config flag is static: when working memory is disabled by
        ;; configuration, bind the working-memory routes to a fixed 409
        ;; handler once, here, instead of branching on the flag per request.
        ;; Rulebase input is NOT resolved here — the session atom can be
        ;; hot-swapped at runtime, so that cause stays dynamic inside
        ;; with-snapshot / cache/snapshot.
        wm-disabled-handler (when-not working-memory-enabled?
                              (fn [_req] (no-working-memory-response :disabled-by-config)))
        wm-route (fn [handler] (or wm-disabled-handler handler))]
    (ring/router
     ["/v1"
      ["/rulebase-summary"
       {:get (partial handle-get-rulebase-summary session-atom annotations-atom cache working-memory-enabled?)}]

      ["/analysis"
       {:get (partial handle-get-analysis session-atom annotations-atom cache)}]

      ["/rules"
       [""
        {:get (partial handle-get-rules session-atom annotations-atom cache)}]
       ["/:id"
        {:get (partial handle-get-rule session-atom annotations-atom cache)}]]

      ["/queries"
       [""
        {:get (partial handle-get-queries session-atom annotations-atom cache)}]
       ["/:id"
        {:get (partial handle-get-query session-atom annotations-atom cache)}]]

      ["/fact-types"
       [""
        {:get (partial handle-get-fact-types session-atom annotations-atom cache)}]
       ["/:id"
        {:get (partial handle-get-fact-type session-atom annotations-atom cache)}]]

      ["/session"
       ["/fact-types"
        ["" {:get (wm-route (partial handle-get-session-fact-types session-atom cache annotations-atom))}]
        ["/:id" {:get (wm-route (partial handle-get-session-fact-type session-atom cache annotations-atom))}]]
       ["/facts/:id"
        {:get (wm-route (partial handle-get-session-fact session-atom cache annotations-atom))}]
       ["/rules/:id"
        {:get (wm-route (partial handle-get-session-rule session-atom cache annotations-atom))}]
       ["/queries/:id"
        {:get (wm-route (partial handle-get-session-query session-atom cache annotations-atom))}]]

      ["/session-snapshot"
       {:get (wm-route (partial handle-get-session-snapshot session-atom cache annotations-atom))}]

      ["/annotations"
       [""
        {:get (partial handle-get-annotations session-atom annotations-atom)}]]]

     {:data {:muuntaja (m/create
                        (assoc-in m/default-options
                                  [:formats "application/json" :encoder-opts]
                                  {:mapper (json-mapper)}))
             :middleware [muuntaja/format-middleware]}})))

(defn app
  "Returns {:keys [handler cache]}.
   `working-memory-enabled?` is the raw `:working-memory-enabled` config
   flag (default true at the `start!` level).  When false, working-memory
   routes are bound to a fixed 409 `:disabled-by-config` handler at router
   construction.  A rulebase session is detected dynamically and yields 409
   `:rulebase-input`."
  [session-atom annotations-atom working-memory-enabled?]
  (let [cache-atom (cache/create)]
    {:handler (ring/ring-handler
               (router session-atom annotations-atom cache-atom working-memory-enabled?)
               (ring/create-default-handler))
     :cache cache-atom}))
