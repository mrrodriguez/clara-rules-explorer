(ns clara.server.tools.graph.rules.helpers
  (:require [clara.rules :as r]))

(defmacro def-fact-fn
  [name-sym fact-type & body]
  (let [rule-name (symbol (format "%s-rule" name-sym))
        name-sym-with-meta (with-meta name-sym {:type fact-type})]
    `(do
       (defn ~name-sym-with-meta ~@body)
       (r/defrule ~rule-name
         ~'=>
         (let [resolved# (var ~name-sym-with-meta)]
           (r/insert! resolved#))))))

(defn ->fact
  [fact-type fact-data]
  (with-meta fact-data {:type fact-type}))
