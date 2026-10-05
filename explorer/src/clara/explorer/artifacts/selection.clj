(ns clara.explorer.artifacts.selection
  "Materialize a caller-named selection of artifact units for merging: read each
  unit's slim analysis (refusing absent analysis and shape skew), narrow each to
  its `:namespaces` filter, and compute the unioned, re-closed hierarchy and the
  namespace coverage — once, for every merge mode.

  The pure preamble lives in
  `clara.explorer.artifacts.shared.selection/->selection`, which takes
  the registry I/O as an injected capabilities map so the babashka editor client
  can share it without loading `clara.explorer.artifacts.registry`.
  This namespace supplies the JVM registry's capabilities: memoized
  `read-analysis` and the shape-skew `assert-compatible!`.

  `clara.explorer.artifacts.compose` consumes this: it is the
  selection preamble the merge mode needs — the per-unit narrowed analyses, the
  same `registry/assert-compatible!` refusal of shape skew, and the same
  unioned hierarchy for its fact-type merge and the manifest's coverage.

  Nothing here decides which units belong together — the selection arrives named
  and ordered, and this namespace returns a value describing it."
  (:require
   [clara.explorer.artifacts.registry :as registry]
   [clara.explorer.artifacts.shared.selection :as shared-selection]))

(set! *warn-on-reflection* true)

(defn ->selection
  "A mergeable selection of `units` under `registry`, as one value — delegates
  to `clara.explorer.artifacts.shared.selection/->selection` with the
  JVM registry's capabilities. See that namespace for the returned value's
  shape."
  [registry selection]
  (shared-selection/->selection
   {:read-analysis #(registry/read-analysis registry %)
    :assert-compatible! #(registry/assert-compatible! registry %)}
   selection))
