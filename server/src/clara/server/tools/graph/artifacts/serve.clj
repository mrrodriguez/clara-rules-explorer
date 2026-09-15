(ns clara.server.tools.graph.artifacts.serve
  "Picking which persisted layer files a server is handed.

  Two things `clara.server.graph.server/start!` does that a caller therefore must
  not: it folds the rule-`:props` layer in as the base itself, and it re-reads
  every file-backed layer on `reload-annotations!` — so editing an overlay needs
  no restart.

  The layers go in as the `:source` of the server's annotations spec. A stack
  assembled here normally carries no `:enrichment`: the files being served
  already hold the result of static analysis and working-memory enrichment, and
  the server's own `:auto-detect` modes would re-derive both from scratch, off a
  different (uncurated) base.

  They are *layers*, never `merged-annotations.edn`. Serving the layers is not an
  approximation of that file — it is the same fold, recomputed live, so edits
  show up on reload. `merged-annotations.edn` is the fold's *output*, a
  `MergedAnnotations` value carrying `:layers`/`:provenance` rather than the
  `Layer` shape, and the server rejects it on validation."
  (:require
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.artifacts.store :as store]
   [clojure.java.io :as io]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(s/defn existing-layer-paths :- [s/Str]
  "Paths of the layer artifacts `ks` that are actually on disk, in
  `store/layer-artifacts` fold order."
  [ks :- [schema/LayerArtifactKey]
   opts :- schema/ArtifactOpts]
  (into [] (comp (map #(store/get-artifact-path % opts))
                 (filter #(.exists (io/file ^String %))))
        ks))

(s/defn ->served-layer-paths :- [s/Str]
  "The layer files `which` selects, lowest precedence first.

  `which` is a `schema/ServedLayerSelector`:

    :merged          every layer file on disk — the source of truth, and the
                     sensible default
    a layer key      one of `store/layer-artifacts`, if it exists
    a coll of those  those that exist, re-ordered into fold order
    a path           served as given
    a coll of paths  served as given, in the order given

  A layer key that names no file on disk is dropped rather than refused, which
  is what makes `:merged` mean \"whatever this run actually produced\". An
  explicit path is never checked: a caller naming a file means it."
  [which :- schema/ServedLayerSelector
   opts :- schema/ArtifactOpts]
  (let [layer-keys (set (keys store/layer-artifacts))]
    (cond
      (string? which) [which]
      (= :merged which) (existing-layer-paths (keys store/layer-artifacts) opts)
      (layer-keys which) (existing-layer-paths [which] opts)

      (and (sequential? which) (every? layer-keys which))
      (existing-layer-paths (filter (set which) (keys store/layer-artifacts)) opts)

      (sequential? which) (vec which)

      :else (throw (ex-info (str "which must be :merged, a layer key "
                                 (pr-str (vec (keys store/layer-artifacts)))
                                 ", a coll of those, a path, or a coll of paths")
                            {:which which :layer-keys layer-keys})))))
