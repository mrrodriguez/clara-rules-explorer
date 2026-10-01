(ns clara.explorer.artifacts.manifest
  "Provenance manifest for an annotation-generation run (rules-inspect-manifest.edn).

  Records **state-of-the-world only**: git shas / branch / working-tree of every
  input that can drift over time, plus the analysis method + Clojure version +
  analyzed namespaces and an append-only `:history`. It deliberately records **no
  derived analysis** (no counts, no per-rule resolution detail) — the annotation
  layers' per-callsite `:status` / per-dimension `:resolution` is the source of
  truth for what resolved.

  `:agent-annotations` is a presence pointer, not a summary: a curated overlay
  layer is an *input that drifts*, so whether one exists belongs here, but what it
  resolved stays in the annotations EDN — per key in the merge's `:provenance`,
  per callsite as `:from-layer`.

  ## What the caller contributes

  This namespace knows the artifact set, the run's own checkout, and the runtime.
  It does not know where a host's rule sources were resolved from, what tooling
  was on the classpath, or how the session was built — so those arrive as
  `:blocks` (merged into the manifest whole) and `:analysis-run` (merged into
  that block). See `schema/ManifestOptions`.

  Written as EDN to the `:manifest` entry of
  `clara.explorer.artifacts.store/artifact-files`."
  (:require
   [clara.explorer.analyze :as analyze]
   [clara.explorer.artifacts.schema :as schema]
   [clara.explorer.artifacts.shared.git :as shared-git]
   [clara.explorer.artifacts.store :as store]
   [clara.explorer.edn-io :as edn-io]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [schema.core :as s])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; git provenance
;; ===========================================================================

(s/defn get-git-info :- schema/GitInfo
  "State-of-the-world for a repo dir, or nil if `dir` is missing / not a git
  repo.

  Records the checkout's *identity* (remote + sha + branch), never its location:
  manifests are meant to be committed beside the artifacts they describe, so a
  local filesystem path would be noise to every reader but the machine that
  wrote it. Where a reader's own checkout lives is their own to answer.

  Public because a caller assembling its own `:blocks` needs the same shape for
  the checkouts only it knows about.

  Delegates to `clara.explorer.artifacts.shared.git/get-git-info` — the same
  implementation the offline bb `status` report reads through — including its
  detached-checkout resolution of `:branch` to the remote branch."
  [dir :- (s/maybe (s/cond-pre s/Str File))]
  (when (and dir (.exists (io/file (str dir))))
    (shared-git/get-git-info (str dir))))

;; ===========================================================================
;; manifest construction
;; ===========================================================================

(def ^:private agent-annotations-note
  (str "presence pointer only. When present, a curated overlay layer contributed annotations "
       "that static analysis could not resolve. It is folded over the generated layer (and the "
       "rule-:props base) into merged-annotations.edn, whose :provenance says per key which "
       "layer claimed what, and whose callsites carry :from-layer. What resolved and how is "
       "recorded there per-callsite (:status / :resolution), not counted here."))

(s/defn ^:private get-analyzed-namespaces :- [s/Str]
  "The namespaces the analysis reflects. Preference order: explicit
  `:namespaces`, a live `:session` (via the analyzer's extractor), else a
  caller-supplied `:namespaces-fn`. None of the three, and the run analyzed
  namespaces this manifest cannot name — which it says by recording an empty
  list rather than by guessing."
  [{:keys [namespaces session namespaces-fn] :as opts} :- schema/ManifestOptions]
  (cond
    (seq namespaces) (mapv str namespaces)
    session (mapv str (analyze/extract-rule-namespaces session))
    namespaces-fn (mapv str (namespaces-fn opts))
    :else []))

(s/defn ^:private ->analysis-run-provenance :- {s/Keyword s/Any}
  "The `:analysis-run` block: how the session was built, what it was built from,
  and which namespaces the analysis covers.

  `:layer-ids` records which `:id` each layer file carries, so a reader can
  decode the `:provenance` / `:from-layer` in merged-annotations.edn without
  opening it. The rule-`:props` layer (`:id :props`) folds first and is read off
  the rulebase, not from a file.

  The caller's own `:analysis-run` is merged *over* this, so it can state
  `:method` / `:session-build` / `:scope` — none of which this namespace can
  know — without restating `:layer-ids` or `:clojure-version`."
  [{:keys [analysis-run] :as opts} :- schema/ManifestOptions]
  (merge {:method "clara.explorer.analyze/->rule-source-analysis"
          :clojure-version (clojure-version)
          :layer-ids store/layer-artifacts
          :namespaces (get-analyzed-namespaces opts)}
         analysis-run))

(s/defn ^:private artifact-present? :- s/Bool
  "Whether `filename` sits beside the manifest in `out-dir`."
  [out-dir :- (s/maybe s/Str)
   filename :- s/Str]
  (boolean (some-> out-dir (io/file filename) (.exists))))

(s/defn ^:private ->pointer-blocks :- {s/Keyword {s/Keyword s/Any}}
  "The presence-pointer block for the curated overlay layer. It says only whether
  the thing exists; what it holds stays in the file itself."
  [out-dir :- (s/maybe s/Str)]
  {:agent-annotations {:note agent-annotations-note
                       :source (:agent store/artifact-files)
                       :present (artifact-present? out-dir (:agent store/artifact-files))}})

(s/defn ->manifest :- {s/Keyword s/Any}
  "Build the manifest map from git + runtime + whatever the caller contributed.

  `opts` is a `schema/ManifestOptions`. `:repo` and `:generated-by` are required:
  a manifest that silently describes the wrong repo, or claims the wrong tool
  wrote it, is worse than one that refuses to be written. `:repo-path` defaults
  to the process's cwd.

  `:variant` is recorded only on a variant unit — its absence is what says
  \"this is the mainline state of the world\". On the write path (`:canonical?`
  present) it is derived from the same git info `:source` reads; on the read
  path it is the already-complete `[axis value]` vector, ending in `[:ref …]`."
  [{:keys [repo variant canonical? repo-path generated-by working-tree-notes change out-dir blocks]
    :as opts} :- schema/ManifestOptions]
  (let [today (str (java.time.LocalDate/now))
        git (get-git-info (or repo-path (System/getProperty "user.dir")))
        variant (if (contains? opts :canonical?)
                  (store/write-variant variant canonical? git)
                  variant)]
    (cond-> (merge
             {:repo repo
              :generated-by generated-by
              :created today
              :updated today
              :artifacts store/unit-artifact-files
              :source (merge {:working-tree-notes (or working-tree-notes "")}
                             git)
              :analysis-run (->analysis-run-provenance opts)
              :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
              :history [{:date today
                         :change (or change
                                     (str "Annotation generation via " generated-by "."))}]}
             (->pointer-blocks out-dir)
             blocks)
      variant (assoc :variant variant))))

;; ===========================================================================
;; write (preserve :created, append :history on re-runs)
;; ===========================================================================

(s/defn ^:private read-existing :- (s/maybe {s/Keyword s/Any})
  [file :- File]
  (when (.exists file)
    (try (edn/read-string (slurp file)) (catch Throwable _ nil))))

(s/defn write-manifest! :- s/Str
  "Build and write the provenance manifest into the artifact dir. Preserves an
  existing manifest's `:created` and appends this run's entry to `:history`.
  Returns the written file path.

  `opts` is a `schema/ManifestOptions`; its `:root`/`:dir`/`:repo`/`:variant`/
  `:canonical?` resolve the directory through `store/get-out-dir`, the same way
  every other artifact does."
  [opts :- schema/ManifestOptions]
  ;; The whole opts map, so `:variant`/`:canonical?` land the manifest in the
  ;; same directory as the layers it describes rather than at the mainline base.
  (let [dir (store/get-out-dir opts)
        _ (.mkdirs (io/file dir))
        file (store/get-artifact-file :manifest opts)
        fresh (->manifest (assoc opts :out-dir dir))
        existing (read-existing file)
        manifest (if existing
                   (-> fresh
                       (assoc :created (get existing :created (:created fresh)))
                       (assoc :history (vec (concat (get existing :history []) (:history fresh)))))
                   fresh)]
    (edn-io/write-edn-file! file manifest)
    (str file)))
