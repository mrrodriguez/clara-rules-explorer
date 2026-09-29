(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.artifacts.hierarchy
  "The fact-type hierarchy operations shared by the JVM artifact merge and the babashka editor
  client: the ancestor union, transitive re-closure, deterministic deepest-first ordering, and the
  two closure directions.

   `->descendants` is the transpose of a closed ancestor map. `ancestor-closure` /
  `descendant-closure` are the one-step reach over an already-transitively-closed map — the map
  passed IS the direction, so the names exist to carry which way it runs. These two directions are
  the part that is easy to get wrong: `:used-by-*` closes over descendants, while
  `:inserted-by-rules` / `:retracted-by-rules` close over ancestors.

   `union-ancestors` / `closed-ancestors` / `hierarchy-order` repair the cross-unit ancestor sets
  `clara.server.tools.graph.artifacts.compose` and
  `clara.server.tools.graph.artifacts.federate` share: `:ancestors` is a transitive closure computed
  on the classpath each analysis had, so two units can hold different ancestor sets for one type
  name and both are locally correct. The repair is the same in both: union every unit's edge set,
  re-close transitively, order deepest-first, and record disagreements rather than pick a winner.")

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

(defn union-ancestors
  "Union each type name's ancestor set across `fact-type-maps` (each a slim
  `{type-name {:ancestors [ancestor-name …]}}`), returning
  `{type-name #{ancestor-name …}}` sorted by name. Names that appear only as an
  ancestor in some map are included as keys with their own (possibly empty)
  ancestor set, so the re-closure never loses an edge because its endpoint was
  not itself a key."
  [fact-type-maps]
  (let [fact-type-maps (into [] (remove nil?) fact-type-maps)
        names (into (sorted-set)
                    (mapcat (fn [m] (into (keys m) (mapcat :ancestors (vals m)))))
                    fact-type-maps)]
    (into (sorted-map)
          (map (fn [name]
                 [name (into #{} (mapcat #(get-in % [name :ancestors])) fact-type-maps)]))
          names)))

(defn closed-ancestors
  "Transitively close `ancestors` (`{type-name #{ancestor-name}}`). A `derive`
  that lived in a component one unit did not load is absent from that unit, so
  the union can contain a type a locally-correct unit's list never reached."
  [ancestors]
  (letfn [(expand-one [m ft as]
            (let [expanded (into as (mapcat #(get m % #{})) as)]
              (if (= as expanded) m (assoc m ft expanded))))
          (step [m] (reduce-kv expand-one m m))]
    (loop [m ancestors]
      (let [m' (step m)]
        (if (= m' m) m' (recur m'))))))

(defn- pick-next
  "The next name to emit in deterministic deepest-first order: a node with no
  remaining descendant (deepest), ties broken lexicographically. Falls back to
  the lexicographically smallest remaining node on a pathological ancestor
  cycle — the same cycle guard
  `clara.server.tools.graph.fact-types/hierarchy-order` applies."
  [remaining ancestors]
  (or (->> remaining
           (filter (fn [x]
                     (not-any? (fn [d]
                                 (contains? (get ancestors d #{}) x))
                               remaining)))
           (sort)
           first)
      (first (sort remaining))))

(defn hierarchy-order
  "Order a set of ancestor names deepest-first (descendants before their own
  ancestors, ties by name) — the same rule
  `clara.server.tools.graph.fact-types/hierarchy-order` applies — against
  `ancestors`, a `{type-name #{ancestor-name}}` map."
  [ancestors raw]
  (loop [remaining (set raw)
         ordered []]
    (if (empty? remaining)
      ordered
      (let [pick (pick-next remaining ancestors)]
        (recur (disj remaining pick) (conj ordered pick))))))
