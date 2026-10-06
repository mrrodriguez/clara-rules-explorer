(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.manifest
  "The pure core of `clara.explorer.artifacts.manifest`, shared by the JVM and babashka: the
  `:history` rule that the manifest writer appends by, and that a registry owner applies to
  manifests it has read but is not about to regenerate.")

(defn dedupe-history
  "`manifest` with exact-duplicate `:history` entries removed, keeping the first of each. A
  manifest with no `:history` is returned unchanged.

  This is the one definition of a duplicate: whole-entry value equality."
  [manifest]
  (cond-> manifest
    (contains? manifest :history)
    (update :history #(into [] (distinct) %))))
