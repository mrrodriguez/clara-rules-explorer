(ns clara.server.tools.graph.utils
  "Small, dependency-free utilities shared across the tools.graph namespaces.")

(defn remove-nil-vals
  "Returns the map `m` with all entries whose value is nil removed."
  [m]
  (->> m
       (reduce-kv (fn [m' k v]
                    (if (nil? v) (dissoc! m' k) m'))
                  (transient m))
       persistent!))
