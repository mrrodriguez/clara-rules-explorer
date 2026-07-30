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
   :from/:from-var/:to/:name/:via-var-alias (see `alias-usage-map`)."
  {:from s/Symbol
   :to s/Symbol
   :name s/Symbol
   (s/optional-key :from-var) s/Symbol
   (s/optional-key :filename) s/Str
   (s/optional-key :row) s/Int
   (s/optional-key :col) s/Int
   (s/optional-key :end-row) s/Int
   (s/optional-key :end-col) s/Int
   s/Keyword s/Any})
