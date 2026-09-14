(ns clara.server.tools.graph.utils
  "Small utilities shared across the tools.graph namespaces.  Depends on
   clara-rules and nothing else in this library, so every namespace here can
   require it without risking a cycle."
  (:require [clara.rules.engine :as eng]))

(defn get-rulebase
  "The rulebase of a session, or the rulebase itself when handed one directly.

   The either-or every entry point in this library accepts, collapsed into one
   definition: `->rulebase-analysis`,
   `clara.server.tools.graph.annotations.merge/->props-layer` and
   `->annotations-from-rule-source-analysis` all take a session *or* a rulebase,
   and a caller holding one should never have to disagree with them about which.

   Tests for `:productions` rather than for a session type, so a hand-built
   rulebase map is accepted and so is any `ISession` implementation."
  [session-or-rulebase]
  (if (:productions session-or-rulebase)
    session-or-rulebase
    (-> session-or-rulebase eng/components :rulebase)))

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
