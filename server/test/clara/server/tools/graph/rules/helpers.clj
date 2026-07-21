(ns clara.server.tools.graph.rules.helpers
  (:require [clara.rules :as r]))

(defmacro def-fact-fn
  [name-sym & body]
  (let [rule-name (symbol (format "%s-rule" name-sym))]
    `(do
       (defn ~name-sym ~@body)
       (r/defrule ~rule-name
         '=>
         (let [resolved# (var ~name-sym)]
           (r/insert! resolved#))))))
