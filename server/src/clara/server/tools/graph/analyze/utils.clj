(ns clara.server.tools.graph.analyze.utils
  "Shared vocabulary for the analyze pipeline: clj-kondo usage shapes and the
   small helpers every pass builds on.  Kept dependency-free (aside from
   schema.core) so any `analyze.*` namespace can use it without require cycles."
  (:require [schema.core :as s]))

(defn fq-sym
  "Returns the fully-qualified symbol `[ns name]` as a single symbol."
  [ns-sym name-sym]
  (symbol (str ns-sym) (str name-sym)))

(defn var-usage-caller
  "Returns the fully-qualified caller symbol from a kondo `:var-usage` map."
  [usage]
  (fq-sym (:from usage) (:from-var usage)))

(defn var-usage-callee
  "Returns the fully-qualified callee symbol from a kondo `:var-usage` map."
  [usage]
  (fq-sym (:to usage) (:name usage)))

(s/defschema KondoVarUsage
  "A clj-kondo `:var-usages` entry.  Open map — clj-kondo emits more keys than
   we consume; the ones we rely on are declared.  The positional keys are
   optional because synthetic usages injected by the var-alias pass carry only
   :from/:from-var/:to/:name/:via-var-alias (see `alias-usage-map`); when
   present they may still be nil (e.g. usages from clara-rules' own compiler
   namespaces in the merged analysis).  `:to` is a keyword for unknown-
   namespace tokens (e.g. `:clj-kondo/unknown-namespace/token`);
   `var-usage-callee` stringifies it either way."
  {:from s/Symbol
   :to (s/conditional keyword? s/Keyword symbol? s/Symbol)
   :name s/Symbol
   (s/optional-key :from-var) (s/maybe s/Symbol)
   (s/optional-key :filename) s/Str
   (s/optional-key :row) (s/maybe s/Int)
   (s/optional-key :col) (s/maybe s/Int)
   (s/optional-key :end-row) (s/maybe s/Int)
   (s/optional-key :end-col) (s/maybe s/Int)
   s/Keyword s/Any})
