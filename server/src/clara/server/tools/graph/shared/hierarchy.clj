(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.hierarchy
  "The fact-type hierarchy transpose and the two closure directions shared by the JVM artifact
  merge and the babashka editor client.

   `->descendants` is the transpose of a closed ancestor map. `ancestor-closure` /
  `descendant-closure` are the one-step reach over an already-transitively-closed map — the map
  passed IS the direction, so the names exist to carry which way it runs. These two directions are
  the part that is easy to get wrong: `:used-by-*` closes over descendants, while
  `:inserted-by-rules` / `:retracted-by-rules` close over ancestors.")

(defn ->descendants
  "Transpose of a closed ancestor map `{type-name #{ancestor-name …}}` into
  `{ancestor-name #{descendant-name …}}`."
  [ancestors]
  (let [index (volatile! {})]
    (doseq [[ft as] ancestors
            ancestor as]
      (vswap! index update ancestor (fnil conj #{}) ft))
    @index))

(defn- ->closure
  "`base-types` plus every name reached through `edge-map` (`{type-name #{type-name}}`). One `get`
  per name is the whole closure because the map passed is already transitively closed — passing a
  non-closed map here is a wrong answer no exception will flag."
  [edge-map base-types]
  (reduce (fn [acc t] (into acc (cons t (get edge-map t #{})))) #{} base-types))

(defn ancestor-closure
  "`base-types` and everything they derive from: the set a holder of `base-types` satisfies — a fact
  of type `T` *is a* each of `T`'s ancestors. `ancestors` is a closed
  `{type-name #{ancestor-name}}` map."
  [ancestors base-types]
  (->closure ancestors base-types))

(defn descendant-closure
  "`base-types` and everything deriving from them: the set a matcher of `base-types` is reached by —
  a rule matching `T` is matched by any descendant of `T`. `descendants` is a closed
  `{ancestor-name #{descendant-name}}` map."
  [descendants base-types]
  (->closure descendants base-types))
