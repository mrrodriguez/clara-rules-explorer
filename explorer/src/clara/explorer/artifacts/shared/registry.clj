(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.registry
  "The pure per-unit helpers shared by the JVM artifact registry and the babashka editor client:
  a unit's string handle, and the `:namespaces` narrowing of one unit's slim analysis.

   `clara.explorer.artifacts.registry` requires this namespace for the same definitions,
  so the JVM and bb cannot drift on how a unit is named or narrowed.")

(defn unit-key
  "The string handle for a unit ref: `<repo>[@<branch>]`. A `UnitRef` map is not
  a comparable map key under the library's own `sorted-map` convention, so maps
  keyed by unit use this."
  [{:keys [repo branch]}]
  (str repo (when (seq branch) (str "@" branch))))

(defn narrow-analysis
  "Narrow `analysis` to `unit`'s `:namespaces` filter, when present: `:rules`
  and `:queries` keep only the productions whose `:ns` is in the filter (as
  strings). `:fact-types` stays whole — keyed by type, not namespace, and the
  hierarchy benefits from staying global — and `:dep-graph` is left alone,
  because the compose merge recomputes it over the merged productions.
  `:unresolved` and `:slim` pass through.

  Without a filter the analysis is returned unchanged. Both
  `clara.explorer.artifacts.federate/->index` and the compose merge
  apply this when they read a unit for a merge, so a `UnitRef` narrowed to a
  subset of a unit's namespaces excludes the productions outside that subset."
  [analysis unit]
  (if-let [nses (:namespaces unit)]
    (let [nses (into #{} (map str) nses)
          keep? (fn [[_ {:keys [ns]}]] (contains? nses (str ns)))
          narrow (fn [productions]
                   (into (sorted-map) (filter keep?) productions))]
      (cond-> analysis
        (contains? analysis :rules) (update :rules narrow)
        (contains? analysis :queries) (update :queries narrow)))
    analysis))
