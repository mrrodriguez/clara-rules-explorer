(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.registry
  "The pure per-unit helpers shared by the JVM artifact registry and the babashka editor client:
  a unit's string handle, and the `:namespaces` narrowing of one unit's slim analysis.

   `clara.explorer.artifacts.registry` requires this namespace for the same definitions,
  so the JVM and bb cannot drift on how a unit is named or narrowed."
  (:require [clara.explorer.artifacts.layout :as layout]))

(defn unit-key
  "The string handle for a unit ref: `<repo>`, or `<repo>@<encoded variant path>`
  for a variant unit. The variant is encoded with `layout/variant->path`, so a
  `@` or `/` inside a value never breaks the split a reader does on the last
  `@`. A composition's key is its `_compose/<name>` repo and nothing more; its
  name may hold `@`, so a reader checks the prefix before splitting. A `UnitRef` map is not a comparable map key under the library's own
  `sorted-map` convention, so maps keyed by unit use this."
  [{:keys [repo variant]}]
  (str repo (when (seq variant) (str "@" (layout/variant->path variant)))))

(defn narrow-analysis
  "Narrow `analysis` to `unit`'s `:namespaces` filter, when present: `:rules`
  and `:queries` keep only the productions whose `:ns` is in the filter (as
  strings). `:fact-types` stays whole — keyed by type, not namespace, and the
  hierarchy benefits from staying global — and `:dep-graph` is left alone,
  because the compose merge recomputes it over the merged productions.
  `:unresolved` and `:slim` pass through.

  Without a filter the analysis is returned unchanged. The compose merge
  applies this when it reads a unit for a merge, so a `UnitRef` narrowed to a
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
