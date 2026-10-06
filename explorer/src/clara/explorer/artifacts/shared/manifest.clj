(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.manifest)

(defn dedupe-history
  "`manifest` with exact-duplicate `:history` entries removed, keeping the first of each. A
  manifest with no `:history` is returned unchanged.

  This is the one definition of a duplicate: whole-entry value equality."
  [manifest]
  (cond-> manifest
    (contains? manifest :history)
    (update :history #(into [] (distinct) %))))
