(ns clara.server.tools.graph.artifacts.hierarchy
  "The fact-type hierarchy union, transitive re-closure, and deterministic
  deepest-first ordering shared by `clara.server.tools.graph.artifacts.compose`
  and `clara.server.tools.graph.artifacts.federate` — the one place the
  cross-unit ancestor repair lives.

  Both merge modes face the same defect `artifact-registry-plan.md` §7 names:
  `:ancestors` is a transitive closure computed on the classpath each analysis
  had, so two units can hold different ancestor sets for one type name and both
  are locally correct. The repair is the same in both: union every unit's edge
  set, re-close transitively, order deepest-first, and record disagreements
  rather than pick a winner. `compose/union-fact-types` and `federate/->index`
  both call these.")

(set! *warn-on-reflection* true)

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

(defn ->descendants
  "Transpose of a closed ancestor map: `{ancestor-name #{descendant-name …}}`."
  [ancestors]
  (let [index (volatile! {})]
    (doseq [[ft as] ancestors
            ancestor as]
      (vswap! index update ancestor (fnil conj #{}) ft))
    @index))

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
