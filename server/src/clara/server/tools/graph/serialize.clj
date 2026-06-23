(ns clara.server.tools.graph.serialize
  "Helpers for serializing Clara rulebase structures to JSON-friendly formats."
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.walk :as w]))

(defn resolve-type
  "Resolves a type (Class, Symbol, etc.) to a string representation for JSON."
  [ns-name x]
  (cond
    (nil? x) nil
    (class? x) (.getName ^Class x)
    (keyword? x) (-> x symbol str)
    (symbol? x) (if-let [resolved (and ns-name (ns-resolve (the-ns ns-name) x))]
                  (cond
                    (class? resolved) (.getName ^Class resolved)
                    (var? resolved) (let [{vns :ns vname :name} (meta resolved)
                                          ns-str (name (ns-name vns))
                                          name-str (name vname)]
                                      (str (symbol ns-str name-str)))
                    :else (str resolved))
                  (str x))
    :else (str x)))

(defn serialize-fact-type
  [production-ns-name x]
  (resolve-type production-ns-name x))

(defn prune-fns
  "Recursively walks a data structure and replaces items that implement IFn
   with a string placeholder or their symbol if available."
  [x]
  (cond
    (record? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns v)))
                           {}
                           x)
    (map? x) (reduce-kv (fn [m k v] (assoc m k (prune-fns v)))
                        (empty x)
                        x)
    ;; seq-like things or list will insert items to the head, which will reverse the order with
    ;; `into`. This avoids that.
    (or (list? x)
        (and (sequential? x)
             (not (vector? x)))) (into (empty x)
                                       (map prune-fns)
                                       (reverse x))
    ;; Do not preserve type here, it could be a lazy seq and we'd get reversed order. If it is not
    ;; covered by the seq-like checks above, use a vector.
    (sequential? x) (into []
                          (map prune-fns)
                          x)
    (coll? x) (into (empty x)
                    (map prune-fns)
                    x)
    (keyword? x) x
    (symbol? x) x
    (ifn? x) (str x)
    :else x))

(defn stringify-map-keys
  "Recursively converts keyword keys in a map to their string names.
   Returns nil if input is nil."
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc
                        (if (keyword? k) (name k) k)
                        (if (map? v) (stringify-map-keys v) v)))
               (empty m)
               m)))

(defn stringify-idents-coll
  "Converts a coll of symbols or keywords to a set of their string names.
   Returns nil if input is nil."
  [coll]
  (when coll
    (into (empty coll) (map name) coll)))

(defn serialize-production-dep
  [production-map fq-dep-name]
  (let [{p-ns-name :ns-name :keys [rhs]} (get production-map fq-dep-name)
        base
        (if (and (string? fq-dep-name)
                 (str/includes? fq-dep-name "/"))
          (let [fq-sym (-> fq-dep-name symbol)
                ns-part (namespace fq-sym)]
            {:ns ns-part
             ;; NOTE: It should stay fully-qualified since the caller expects this for now.
             :name (str fq-dep-name)})
          {:ns (str p-ns-name)
           :name fq-dep-name})]
    (cond-> base
      (seq rhs) (assoc :type "rule")
      (nil? rhs) (assoc :type "query"))))

(defn serialize-condition
  "Serializes a single condition, including pretty-printing its constraints."
  [condition]
  (letfn [(serialize-constraint [constraint]
            (with-out-str (pp/pprint constraint)))
          (serialize-constraints [constraints]
            ;; `pp/pprint` adds the newline after the last constraint so do not include it trailing
            ;; in this `format` call.
            (format "[\n%s]"
                    (->> constraints
                         (map serialize-constraint)
                         (str/join \newline))))
          (serialize-node [node]
            (if (and (map? node)
                     (contains? node :constraints))
              (update node :constraints serialize-constraints)
              node))]
    (w/prewalk serialize-node condition)))

(defn serialize-lhs
  "Serializes the LHS of a rule."
  [lhs]
  (mapv serialize-condition lhs))

(defn serialize-rhs-form
  [rhs-form]
  (with-out-str (pp/pprint rhs-form)))
