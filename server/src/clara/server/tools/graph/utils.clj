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

(defn sort-by-key
  "Sorts `coll` by `key-fn`, computing `key-fn` exactly once per element
   (decorate-sort-undecorate / Schwartzian transform).  `sort-by` recomputes
   its key-fn on every comparison — O(n log n) key computations — which is
   wasteful when the key-fn stringifies or walks large values; this computes
   it O(n) times instead.

   `key-fn` results must be mutually `compare`-able; ties preserve input
   order (stable), matching `sort-by`."
  [key-fn coll]
  (->> coll
       (map (fn [x] [(key-fn x) x]))
       (sort-by first)
       (map second)))
