(ns clara.server.tools.graph.analyze.alias
  "Var-alias chains (`:fact-type-spec-fn`) — caller-guided var-as-fact discovery.

   The caller may declare that a fact type aliases a var (the var-as-fact
   pattern — a fact IS a function var, bound on the LHS and invoked in the
   RHS).  `alias-usage-map` emits synthetic var-usages linking consuming rules
   to their aliased vars so the existing reachability explores the var's call
   chain; callsites discovered through that chain bypass the ctor chain (see
   `analyze.callsite`) and are recorded :none (unresolved) with
   :fact-type/:fact-type-spec context attached (then handed to the caller's
   `:callsite-resolver-fn`)."
  (:require [schema.core :as s]
            [clojure.tools.logging :as log]
            [clara.server.tools.graph.analyze.utils :as u]
            [clara.server.tools.graph.conditions :as conditions]))

(defn- rhs-uses-binding?
  "True when ?sym occurs as a free symbol in the rule's RHS. Kondo records
   free ?syms as var-usages (:to :clj-kondo/unknown-namespace) attributed to
   the rule's snippet var; snippets contain only the RHS form, so any such
   usage is an RHS usage.  Answered via the by-caller usage index — only the
   rule's own usages are scanned, never the whole `:var-usages` vector."
  [usages-by-caller rule-ns rule-local-name binding-sym]
  (boolean
   (some #(= binding-sym (:name %))
         (get usages-by-caller (u/fq-sym rule-ns rule-local-name)))))

(defn- apply-spec-fn
  "Invokes the caller's `:fact-type-spec-fn` on a fact type (keyword, fq
   class-name symbol, … — whatever the caller's rules use); exceptions are
   contained (logged, treated as no spec)."
  [fact-type-spec-fn fact-type]
  (try
    (fact-type-spec-fn fact-type)
    (catch Throwable t
      (log/errorf t "clara.server.tools.graph.analyze: :fact-type-spec-fn threw: %s"
                  (ex-message t))
      nil)))

;; ---------------------------------------------------------------------------
;; Schemas for `alias-usage-map`
;; ---------------------------------------------------------------------------

(s/defschema VarAliasSyntheticUsage
  "A synthetic `:var-usage` linking a rule to its aliased var.
   `:fact-type` is `s/Any`: LHS condition types may be keywords OR fq
   class-name symbols (record facts).  `:fact-type-spec` is an open
   caller-defined map; the only key the analyzer reads is `:aliases-var`."
  {:from s/Symbol
   :from-var s/Symbol
   :to s/Symbol
   :name s/Symbol
   :via-var-alias {:fact-type s/Any
                   :fact-type-spec {s/Keyword s/Any}
                   :var s/Symbol}})

(s/defschema VarAliasContext
  "Per-chain alias context attached to callsites discovered through a
   var-alias chain.  `:fact-type` is `s/Any` — see
   `VarAliasSyntheticUsage`."
  {:fact-type s/Any
   :fact-type-spec {s/Keyword s/Any}
   :var s/Symbol
   :root s/Symbol})

(s/defschema AliasUsageMapEntry
  "Per-rule value in the `alias-usage-map` result."
  {:usages [VarAliasSyntheticUsage]
   :contexts [VarAliasContext]})

(s/defschema AliasUsageMap
  "Return type of `alias-usage-map`."
  {s/Symbol AliasUsageMapEntry})

;; ---------------------------------------------------------------------------
;; `alias-usage-map` helpers
;; ---------------------------------------------------------------------------

(defn- build-alias-pair
  "Returns nil or a single `{:fact-type :fact-type-spec :var}` entry when
   `fact-type` maps through `fact-type-spec-fn` to an alias and the binding
   is used in the rule's RHS."
  [fact-type-spec-fn usages-by-caller rule-ns rule-local {:keys [binding fact-type]}]
  (when-let [spec (apply-spec-fn fact-type-spec-fn fact-type)]
    (when-let [v (:aliases-var spec)]
      (when (and (symbol? v)
                 (namespace v)
                 (rhs-uses-binding? usages-by-caller rule-ns rule-local binding))
        {:fact-type fact-type
         :fact-type-spec spec
         :var v}))))

(defn- build-alias-pairs
  "Scans the production's normalized `:lhs` for
   `conditions/extract-var-bindings` and returns the deduplicated vector of
   alias pair entries for rules whose bound fact types map through
   `fact-type-spec-fn` to an alias."
  [production usages-by-caller fact-type-spec-fn rule-ns rule-local]
  (let [pairs (into []
                    (keep (partial build-alias-pair
                                   fact-type-spec-fn usages-by-caller rule-ns rule-local))
                    (conditions/extract-var-bindings (:lhs production)))]
    (distinct pairs)))

(defn- build-synthetic-usage
  "Builds a synthetic `:var-usage` map for a single alias pair."
  [rule-ns rule-local {:keys [fact-type fact-type-spec] aliased-var :var}]
  {:from rule-ns
   :from-var rule-local
   :to (symbol (namespace aliased-var))
   :name (symbol (name aliased-var))
   :via-var-alias {:fact-type fact-type
                   :fact-type-spec fact-type-spec
                   :var aliased-var}})

(defn- build-alias-context
  "Builds a context entry for a single alias pair — carried by callsites
   discovered through the chain (see `:fact-type`, `:fact-type-spec` keys)."
  [{:keys [fact-type fact-type-spec] aliased-var :var}]
  {:fact-type fact-type
   :fact-type-spec fact-type-spec
   :var aliased-var
   :root (symbol (namespace aliased-var) (name aliased-var))})

(defn alias-usage-map
  "Builds the var-alias linkage for the `:fact-type-spec-fn` mechanism.

   For each rule production in `rule-vars`: scans its normalized `:lhs` for
   bound fact variables (`conditions/extract-var-bindings`), and when
   `(fact-type-spec-fn fact-type)`
   returns a spec with `:aliases-var` pointing at a fully-qualified var AND
   the binding is used in the rule's RHS, emits a synthetic `:var-usage`
   tagged `:via-var-alias`.  Merged into the analysis before graph building,
   this lets the existing reachability explore the aliased var's whole call
   chain for boundary fns.  (If the var is invisible to `clj-kondo` —
   macro-emitted, unhooked — its chain is empty and nothing is found; that
   is the caller `:config-dir` situation.)

   `productions-by-name` maps fq rule symbol -> full production.
   `usages-by-caller` is the by-caller var-usage index of the analysis
   (pre-alias-injection).

   Returns an `AliasUsageMap` — `{rule-fq-sym {:usages [...] :contexts [...]}}`."
  [productions-by-name rule-vars usages-by-caller fact-type-spec-fn]
  (->> rule-vars
       (keep (fn [rule-fq-sym]
               (when-let [production (get productions-by-name rule-fq-sym)]
                 (let [rule-ns (symbol (namespace rule-fq-sym))
                       rule-local (symbol (name rule-fq-sym))
                       pairs (build-alias-pairs production usages-by-caller
                                                fact-type-spec-fn rule-ns rule-local)]
                   (when (seq pairs)
                     [rule-fq-sym
                      {:usages (mapv (partial build-synthetic-usage rule-ns rule-local) pairs)
                       :contexts (mapv build-alias-context pairs)}])))))
       (into {})))
