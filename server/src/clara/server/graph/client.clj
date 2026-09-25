(ns clara.server.graph.client
  "Editor-facing query surface. Pure EDN in, EDN out. No HTTP.

   Both the Emacs (CIDER) and future neovim (Conjure) clients eval
   `clara.server.graph.client/navigate` over nREPL and read the printed EDN
   result.  This namespace is the JVM shell: system registration, schema
   validation, live-namespace token resolution, and var-metadata source
   locations. The navigation itself lives in `shared-navigate/navigate`,
   which this namespace calls with the JVM runtime map.

   Resolution reuses the analyzer's own logic (`ctor/resolve-record-type`
   for record/Java constructors, the serialized
   `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected`
   callsite linkage for user-defined constructors) so navigation can never
   drift from what the annotations computed."
  (:require [clara.server.graph.cache :as cache]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.analyze.ctor :as ctor]
            [clara.server.tools.graph.shared.navigate :as shared-navigate]
            [clara.server.tools.graph.shared.schema :as shared-schema]
            [clara.server.tools.graph.shared.tokens :as shared-tokens]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [schema.core :as s]))

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
;; Session swap — opts-fn indirection for editor clients
;; ---------------------------------------------------------------------------

(defonce ^:private session-swap-opts-fn-atom (atom nil))

(defn register-session-swap-opts-fn
  "Registers a 0-arg fn that returns the opts map for `swap-session!`.
   The elisp `clara-explorer-swap-session` calls the 0-arity `swap-session!`
   when no explicit opts are given, which delegates to this fn."
  [f]
  (reset! session-swap-opts-fn-atom f)
  ::ok)

(defn current-session-swap-opts-fn
  "Returns the currently registered session-swap opts fn, or nil."
  []
  @session-swap-opts-fn-atom)

(defn swap-session!
  "Hot-swap the explorer session.  1-arity is the direct opts path
   (full `server/swap-session!` opts, not just `:session`); 0-arity
   delegates to the fn registered via `register-session-swap-opts-fn`.
   Both arities return `::ok` so the nREPL result stays small."
  ([]
   (if-let [f @session-swap-opts-fn-atom]
     (swap-session! (f))
     (throw (ex-info "No session-swap opts fn registered — call register-session-swap-opts-fn first" {}))))
  ([opts]
   (server/swap-session! (get-current-system) opts)
   ::ok))

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

(s/defn get-production-source :- shared-schema/SourceLoc
  "Returns the source location of a production (`\"ns/rule\"`), from var
   metadata where the production interns a var, else a `:var? false`
   placeholder (the kondo tier / elisp regex fallback takes over)."
  [fq-name :- s/Str]
  (or (var-source fq-name) (unknown-source)))

(s/defn get-production-locations
  "Full map of every fq production name to its `shared-schema/SourceLoc` (debugging)."
  []
  (if-let [sys (get-current-system)]
    (let [{:keys [state-atom cache]} sys
          analysis (cache/get-rulebase-analysis cache @state-atom)]
      (into {}
            (map (fn [name] [name (get-production-source name)]))
            (concat (keys (:rules analysis)) (keys (:queries analysis)))))
    {:error "no explorer system registered"}))

;; ---------------------------------------------------------------------------
;; Token resolution (live namespaces)
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
    (class? ctor-result) (Class/.getName ctor-result)
    (symbol? ctor-result) (str ctor-result)
    :else nil))

(defn- resolve-ctor-token
  "Resolves a bare constructor token (`->X`, `map->X`, `X.`, `X/new`)
   to a kind-explicit class-name string, or nil.  Java ctor syntaxes are
   normalized to a class symbol (see `shared-tokens/normalize-ctor-target`)
   and delegated to `ctor/resolve-record-type`."
  [caller-ns-sym form]
  (if (ctor/constructor-fn-name? (name form))
    ;; ->X / map->X record constructors resolve against the form itself.
    (ctor-result->name (ctor/resolve-record-type caller-ns-sym form))
    ;; X. / X/new normalize to a class symbol first; anything else is nil.
    (when-let [class-sym (shared-tokens/normalize-ctor-target form)]
      (ctor-result->name (ctor/resolve-record-type caller-ns-sym class-sym)))))

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
            (class? resolved) (Class/.getName resolved)

            (var? resolved)
            (let [ctor-name (ctor/resolve-record-type caller-ns-sym form)]
              (cond
                (class? ctor-name) (Class/.getName ctor-name)
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
            (class? resolved) (symbol (Class/.getName resolved))
            (var? resolved) (var-fq-symbol resolved)
            :else nil))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- jvm-runtime
  "The `shared-navigate/navigate` runtime map over the editor's live
   namespaces: token resolution via `ns-resolve` / record-constructor
   class-loading, source locations from var metadata."
  []
  {:resolve-token resolve-token
   :token->fq-sym token->fq-sym
   :production-source get-production-source})

(s/defn navigate :- shared-schema/NavigateResponse
  "Resolves editor navigation for a fact-type token.  Returns a
   `shared-schema/NavigateResponse`.  Input shape is `shared-schema/NavigateInput`;
   schema enforcement runs through the `schema.test/validate-schemas` test
   fixture (via `shared-navigate/navigate`), not an explicit runtime validate."
  [input]
  (let [{:keys [production side caller-ns token]} input]
    (log/infof "navigate: production=%s side=%s caller-ns=%s token=%s"
               production side caller-ns (pr-str token))
    (try
      (let [result
            (if-let [sys (get-current-system)]
              (let [{:keys [state-atom cache]} sys
                    analysis (cache/get-rulebase-analysis cache @state-atom)]
                (shared-navigate/navigate analysis (jvm-runtime) input))
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
