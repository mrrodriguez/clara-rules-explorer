(ns clara.server.graph.client
  "Editor-facing query surface. Pure EDN in, EDN out. No HTTP.

   Both the Emacs (CIDER) and future neovim (Conjure) clients eval
   `clara.server.graph.client/navigate` over nREPL and read the printed EDN
   result.  This namespace is the shared contract; nothing transport-specific
   lives here and no Class ever crosses the wire.

   Resolution reuses the analyzer's own logic (`ctor/resolve-record-type`
   for record/Java constructors, the serialized
   `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected`
   callsite linkage for user-defined constructors) so navigation can never
   drift from what the annotations computed."
  (:require [clara.server.graph.cache :as cache]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.analyze.ctor :as ctor]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [schema.core :as s]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(s/defschema NavigateInput
  {(s/optional-key :production) (s/maybe s/Str)   ; fq "ns/rule"; nil = global path
   (s/optional-key :side)       (s/enum :lhs :rhs)
   (s/optional-key :caller-ns)  s/Str             ; buffer ns, for global path + ctor resolution
   :token                       s/Str})

(s/defschema SourceLoc
  {:var?   s/Bool
   :file   (s/maybe s/Str)
   :line   (s/maybe s/Int)
   :column (s/maybe s/Int)})

(s/defschema NavigateTarget
  {:name   s/Str
   :ns     s/Str
   :type   s/Str
   :via    (s/enum :insert :retract)
   :source SourceLoc})

(s/defschema NavigateResult
  {:direction  (s/enum :producer :consumer :type)
   :production (s/maybe s/Str)
   :type       s/Str
   :targets    [NavigateTarget]})

(s/defschema NavigateError
  {:error s/Str})

(s/defschema NavigateResponse
  "A `navigate` result: either a `NavigateResult` or an error map."
  (s/conditional #(contains? % :error) NavigateError
                 #(contains? % :direction) NavigateResult))

;; ---------------------------------------------------------------------------
;; System registration
;; ---------------------------------------------------------------------------

(defonce ^:private registered-system (atom nil))

(s/defn register! :- s/Keyword
  "Registers an explicit system map (the result of `server/start!` /
   `server/start-system!`) as the system `navigate` queries."
  [sys]
  (reset! registered-system sys)
  (log/info "clara.server.graph.client: system registered")
  ::ok)

(defn get-current-system
  "Returns the registered system, falling back to `server/get-current-system`."
  []
  (or @registered-system (server/get-current-system)))

;; ---------------------------------------------------------------------------
;; Source location (var metadata tier)
;; ---------------------------------------------------------------------------

(defn- var-source
  "Source location for a production from its var metadata, or nil when the
   production does not intern a resolvable var (non-var productions)."
  [fq-name]
  (let [sym (symbol fq-name)
        ns-sym (some-> sym namespace symbol)
        name-sym (some-> sym name symbol)]
    (when (and ns-sym name-sym)
      (when-let [v (some-> (find-ns ns-sym) (ns-resolve name-sym))]
        (let [{:keys [file line column]} (meta v)]
          {:var? true
           :file file
           :line (some-> line int)
           :column (some-> column int)})))))

(defn- unknown-source
  "Source placeholder for a production with no resolvable var."
  []
  {:var? false :file nil :line nil :column nil})

(s/defn get-production-source :- SourceLoc
  "Returns the source location of a production (`\"ns/rule\"`), from var
   metadata where the production interns a var, else a `:var? false`
   placeholder (the kondo tier / elisp regex fallback takes over)."
  [fq-name :- s/Str]
  (or (var-source fq-name) (unknown-source)))

(s/defn get-production-locations
  "Full map of every fq production name to its `SourceLoc` (debugging)."
  []
  (if-let [sys (get-current-system)]
    (let [{:keys [state-atom cache]} sys
          {:keys [session annotations memory-analysis]} @state-atom
          analysis (cache/get-rulebase-analysis cache session annotations memory-analysis)]
      (into {}
            (map (fn [name] [name (get-production-source name)]))
            (concat (keys (:rules analysis)) (keys (:queries analysis)))))
    {:error "no explorer system registered"}))

;; ---------------------------------------------------------------------------
;; Token resolution (§7)
;; ---------------------------------------------------------------------------

(def ^:private unreadable ::unreadable)

(defn- read-token
  "Reads the raw token text into a single Clojure form with the Clojure
   reader, binding `*ns*` to the caller namespace (so `::` keywords
   auto-resolve) and setting `*read-eval*` false — the token is trusted
   editor state eval'd inside the user's own nREPL session, the same trust
   boundary as any CIDER eval, not untrusted input.  Returns `::unreadable`
   on failure."
  [caller-ns-sym token]
  (let [t (str/trim token)
        the-ns (when caller-ns-sym (find-ns caller-ns-sym))]
    (if (and (str/starts-with? t "::") (nil? the-ns))
      unreadable
      (binding [*read-eval* false
                *ns* (or the-ns *ns*)]
        (try
          (read-string t)
          (catch Exception _ unreadable))))))

(defn- ctor-result->name
  "Converts a `ctor/resolve-record-type` result (Class or fq class-name
   symbol) to its kind-explicit string form."
  [ctor-result]
  (cond
    (class? ctor-result) (.getName ^Class ctor-result)
    (symbol? ctor-result) (str ctor-result)
    :else nil))

(defn- resolve-ctor-token
  "Resolves a bare constructor token (`->X`, `map->X`, `X.`, `X/new`, `new X`)
   to a kind-explicit class-name string, or nil.  Java ctor syntaxes are
   normalized to a class symbol and delegated to `ctor/resolve-record-type`."
  [caller-ns-sym form]
  (let [n (name form)
        ns-part (namespace form)]
    (cond
      ;; ->X / map->X record constructors
      (ctor/constructor-fn-name? n)
      (ctor-result->name (ctor/resolve-record-type caller-ns-sym form))

      ;; X.  (Java constructor)
      (str/ends-with? n ".")
      (let [class-sym (if ns-part
                        (symbol ns-part (subs n 0 (dec (count n))))
                        (symbol (subs n 0 (dec (count n)))))]
        (ctor-result->name (ctor/resolve-record-type caller-ns-sym class-sym)))

      ;; X/new (modern Java constructor)
      (= n "new")
      (when ns-part
        (ctor-result->name (ctor/resolve-record-type caller-ns-sym (symbol ns-part))))

      :else nil)))

(defn- resolve-symbol-type
  "Resolves a symbol token form to a kind-explicit type name string.  Mirrors
   `clara.server.tools.graph.serialize/resolve-type` but delegates record/Java
   constructors to `ctor/resolve-record-type` so the same subtle rules
   (hyphen → underscore, class-load check) apply."
  [caller-ns-sym form]
  (or (resolve-ctor-token caller-ns-sym form)
      (let [the-ns (when caller-ns-sym (find-ns caller-ns-sym))]
        (if-let [resolved (some-> the-ns (ns-resolve form))]
          (cond
            (class? resolved) (.getName ^Class resolved)

            (var? resolved)
            (let [ctor-name (ctor/resolve-record-type caller-ns-sym form)]
              (cond
                (class? ctor-name) (.getName ^Class ctor-name)
                (symbol? ctor-name) (str ctor-name)
                :else (let [{vns :ns vname :name} (meta resolved)]
                        (str (symbol (name (ns-name vns)) (name vname))))))

            :else (str form))
          (str "symbol[" form "]")))))

(defn- resolve-token-type
  "Resolves a raw token form to a kind-explicit type name string, or nil for
   an unreadable token."
  [caller-ns-sym form]
  (when-not (= unreadable form)
    (cond
      (keyword? form) (str form)
      (string? form) (pr-str form)
      (symbol? form) (resolve-symbol-type caller-ns-sym form)
      :else (str form))))

(defn- resolve-token
  "Resolves the raw token text to a kind-explicit type name string."
  [caller-ns-sym token]
  (resolve-token-type caller-ns-sym (read-token caller-ns-sym token)))

(defn- var-fq-symbol
  "Fully-qualified symbol of a var: ns-name/name."
  [v]
  (symbol (str (ns-name (:ns (meta v))))
          (str (:name (meta v)))))

(defn- token->fq-sym
  "Fully-qualifies a symbol token via `ns-resolve` in the caller namespace.
   Returns the fq symbol of the resolved var/class, or nil."
  [caller-ns-sym token]
  (let [form (read-token caller-ns-sym token)]
    (when (symbol? form)
      (let [the-ns (when caller-ns-sym (find-ns caller-ns-sym))]
        (when-let [resolved (some-> the-ns (ns-resolve form))]
          (cond
            (class? resolved) (symbol (.getName ^Class resolved))
            (var? resolved) (var-fq-symbol resolved)
            :else nil))))))

(defn- real-type-name?
  "True when a resolved name is a genuine type name (not the unresolved-symbol
   sentinel, and not nil)."
  [name]
  (and (some? name)
       (not (str/starts-with? name "symbol["))))

(defn- callsite-matches-token?
  "True when a serialized callsite entry's `:constructor-sym` / `:fact-type` /
   `:fact-type-spec` matches the fully-qualified token symbol (a string)."
  [fq-sym-str callsite]
  (or (= fq-sym-str (:constructor-sym callsite))
      (= fq-sym-str (get-in callsite [:fact-type :name]))
      (some #(= fq-sym-str %) (vals (:fact-type-spec callsite)))))

;; ---------------------------------------------------------------------------
;; Analysis access helpers
;; ---------------------------------------------------------------------------

(defn- production-summary
  [analysis production]
  (or (get-in analysis [:rules production])
      (get-in analysis [:queries production])))

(defn- type-name-set
  [type-refs]
  (into #{} (keep :name) type-refs))

(defn- declared-lhs-type-names [summary]
  (type-name-set (:lhs-types summary)))

(defn- declared-rhs-type-names [summary]
  (type-name-set (concat (:insert-types summary) (:retract-types summary))))

(defn- resolve-rhs-types
  "Resolves the RHS token to a set of candidate kind-explicit type names,
   combining direct (kind/ctor) resolution with the production's serialized
   dynamic-insert/retract callsite linkage (§7.4)."
  [summary caller-ns-sym token]
  (let [direct (resolve-token caller-ns-sym token)
        fq-sym (token->fq-sym caller-ns-sym token)
        callsite-types
        (->> [(get summary :dynamic-insert-types-detected)
              (get summary :dynamic-retract-types-detected)]
             (keep :callsites)
             (apply concat)
             (filter #(when fq-sym (callsite-matches-token? (str fq-sym) %)))
             (mapcat :resolved-types)
             (keep :name)
             set)]
    (cond-> callsite-types
      (real-type-name? direct) (conj direct))))

(defn- match-via
  "`:retract` when any matching type-bridge pair is retraction-coupled, else
   `:insert`."
  [matching-pairs]
  (if (some #(= :retract (:via %)) matching-pairs)
    :retract
    :insert))

(defn- dep->target
  "Builds a `NavigateTarget` from a serialized production dep plus `via`."
  [dep via]
  {:name   (:name dep)
   :ns     (:ns dep)
   :type   (:type dep)
   :via    via
   :source (get-production-source (:name dep))})

(defn- dep->matching-target
  "Builds a `NavigateTarget` from a serialized production dep when it has a
   `:match` pair whose `:consumer-type`/`:producer-type` name (selected by
   `type-ref-key`) is in `type-names`; otherwise nil."
  [dep type-ref-key type-names]
  (let [pairs (filter #(contains? type-names (get-in % [type-ref-key :name]))
                      (:match dep))]
    (when (seq pairs)
      (dep->target dep (match-via pairs)))))

(defn- deps->targets
  "Serialized production deps → `NavigateTarget`s, keeping only deps with a
   matching `:match` pair (see `dep->matching-target`), sorted by fq name."
  [deps type-ref-key type-names]
  (->> deps
       (keep #(dep->matching-target % type-ref-key type-names))
       (sort-by :name)
       vec))

;; ---------------------------------------------------------------------------
;; Scoped navigation (inside a defrule/defquery)
;; ---------------------------------------------------------------------------

(defn- lhs-navigate
  [summary production resolve-ns token]
  (let [token-name (resolve-token resolve-ns token)]
    (if (or (nil? token-name)
            (not (contains? (declared-lhs-type-names summary) token-name)))
      {:error (str "no fact type found under cursor in " production)}
      (let [targets (deps->targets (:upstream summary)
                                   :consumer-type
                                   #{token-name})]
        (if (empty? targets)
          {:error (str "no producer of " token-name " for " production)}
          {:direction  :producer
           :production production
           :type       token-name
           :targets    targets})))))

(defn- rhs-navigate
  [summary production resolve-ns token]
  (let [candidates (resolve-rhs-types summary resolve-ns token)
        matched-types (set/intersection candidates (declared-rhs-type-names summary))]
    (if (empty? matched-types)
      {:error (str "no fact type found under cursor in " production)}
      (let [targets (deps->targets (:downstream summary)
                                   :producer-type
                                   matched-types)
            type-name (first (sort matched-types))]
        (if (empty? targets)
          {:error (str "no consumer of " type-name " for " production)}
          {:direction  :consumer
           :production production
           :type       type-name
           :targets    targets})))))

(defn- navigate-scoped
  [analysis production side caller-ns-sym token]
  (let [prod-ns (some-> production symbol namespace symbol)
        resolve-ns (or caller-ns-sym prod-ns)
        summary (production-summary analysis production)]
    (cond
      (nil? summary)
      {:error (str "no production named " production)}

      (and (= :rhs side)
           (not (contains? (:rules analysis) production)))
      {:error (str production " has no RHS (queries have no RHS)")}

      (= :lhs side) (lhs-navigate summary production resolve-ns token)

      (= :rhs side) (rhs-navigate summary production resolve-ns token)

      :else {:error "a :side is required for scoped navigation"})))

;; ---------------------------------------------------------------------------
;; Global navigation (outside a defrule/defquery, §9.7)
;; ---------------------------------------------------------------------------

(defn- global-callsite-resolved-types
  "Across every production, finds callsites whose `:constructor-sym` /
   `:fact-type` matches the fq token symbol and collects their resolved type
   names."
  [analysis fq-sym]
  (when fq-sym
    (let [fq-str (str fq-sym)
          detections
          (fn [summary]
            [(get summary :dynamic-insert-types-detected)
             (get summary :dynamic-retract-types-detected)])]
      (->> (concat (vals (:rules analysis)) (vals (:queries analysis)))
           (mapcat detections)
           (keep :callsites)
           (apply concat)
           (filter #(callsite-matches-token? fq-str %))
           (mapcat :resolved-types)
           (keep :name)
           set))))

(defn- global-consumer-targets
  "Builds `NavigateTarget`s from a fact type's `used-by-rules` /
   `used-by-queries` production refs (hierarchy-aware consumers)."
  [analysis type-name]
  (let [fact-type (get-in analysis [:fact-types type-name])
        refs (concat (:used-by-rules fact-type)
                     (:used-by-queries fact-type))]
    (->> refs
         (map #(dep->target % :insert))
         (sort-by :name)
         vec)))

(defn- navigate-global
  [analysis caller-ns-sym token]
  (let [direct (resolve-token caller-ns-sym token)
        fq-sym (token->fq-sym caller-ns-sym token)
        callsite-names (global-callsite-resolved-types analysis fq-sym)
        candidates (cond-> callsite-names
                     (real-type-name? direct) (conj direct))
        known-types (set (keys (:fact-types analysis)))
        matched (set/intersection candidates known-types)]
    (cond
      (empty? matched)
      {:error (str "no fact type found under cursor for token " (pr-str token))}

      :else
      (let [type-name (first (sort matched))
            targets (->> matched
                         (mapcat #(global-consumer-targets analysis %))
                         (sort-by :name)
                         vec)]
        (if (empty? targets)
          {:error (str "no consumer of " type-name)}
          {:direction  :type
           :production nil
           :type       type-name
           :targets    targets})))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(s/defn navigate :- NavigateResponse
  "Resolves editor navigation for a fact-type token.  Returns a
   `NavigateResult` or `{:error \"…\"}`.  Input is validated against
   `NavigateInput` at this choke point."
  [input]
  (let [{:keys [production side caller-ns token]} input]
    (log/infof "navigate: production=%s side=%s caller-ns=%s token=%s"
               production side caller-ns (pr-str token))
    (try
      (s/validate NavigateInput input)
      (let [result
            (if-let [sys (get-current-system)]
              (let [{:keys [state-atom cache]} sys
                    {:keys [session annotations memory-analysis]} @state-atom
                    analysis (cache/get-rulebase-analysis cache session annotations memory-analysis)
                    caller-ns-sym (some-> caller-ns symbol)]
                (if (nil? production)
                  (navigate-global analysis caller-ns-sym token)
                  (navigate-scoped analysis production side caller-ns-sym token)))
              {:error "no explorer system registered"})]
        (if (:error result)
          (log/warnf "navigate: %s" (:error result))
          (log/infof "navigate: direction=%s targets=%d"
                     (:direction result) (count (:targets result))))
        result)
      (catch Exception e
        (log/errorf e "navigate failed: production=%s side=%s token=%s"
                    production side (pr-str token))
        {:error (str "internal error: "
                     (or (some-> e .getCause .getMessage)
                         (.getMessage e)))}))))
