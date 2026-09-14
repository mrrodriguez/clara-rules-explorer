(ns clara.server.tools.graph.kondo-config
  "Building a clj-kondo `:config-dir` for rule sources whose macros the bundled
  config does not cover.

  `clara.server.tools.graph.analyze/->rule-source-analysis` materializes its own
  bundled config — the clara-rules `defrule`/`defquery`/`defhierarchy` hooks —
  and passing `:config-dir` **replaces** that bundle wholesale. So a caller whose
  rule namespaces define rules through macros of their own faces an all-or-nothing
  choice: keep the clara hooks and lose theirs, or the reverse.

  `materialize-config-dir!` is the way out. It rebuilds the bundle from the same
  classpath resources the analyzer reads, drops a set of library clj-kondo
  *exports* in beside it as imports, and writes a root `config.edn` over the top.
  The result is a directory that has both.

  ## Why exports need materializing at all

  A clj-kondo export (`clj-kondo.exports/<org>/<lib>/`) is auto-loaded only
  during a `--dependencies` copy-configs pass. The analyzer calls
  `clj-kondo.core/run!` with an explicit `:config-dir` instead, so no export on
  the classpath is ever in effect. Copying one into `imports/<org>/<lib>/` puts
  it back: clj-kondo merges every `imports/*/*` config and puts each import root
  on the hook classpath, which is what resolves the export's hook namespaces.

  Each piece degrades independently. A missing bundled config costs the clara
  hooks — the same fallback the analyzer already takes. A missing export costs
  that library's hooks, and `:extra-config` is where a caller says what to do
  instead."
  (:require
   [clara.server.tools.graph.classpath :as classpath]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def bundled-config-resource
  "Classpath base of the bundled clj-kondo config (the clara-rules import +
  hooks). The same resource
  `clara.server.tools.graph.analyze/->rule-source-analysis` materializes for
  itself — re-materialized here rather than duplicated, so the clara-rules hooks
  always match the version on the classpath."
  "clara/server/tools/graph/kondo-config")

(defn get-import-dir
  "Where an export resource base lands under `imports/`.

  clj-kondo's export convention is `clj-kondo.exports/<org>/<lib>`, and its
  import convention is `imports/<org>/<lib>` — so the destination is the last two
  segments of the base. A base with fewer than two segments is used whole."
  [resource-base]
  (let [segments (str/split resource-base #"/")]
    (str/join "/" (take-last 2 segments))))

(defn- create-temp-dir!
  ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- copy-import!
  "Copy one clj-kondo export resource base into `tmp-dir`/imports/<org>/<lib>.
  Returns the number of files copied — zero when the export is not on the
  classpath at all, which is a normal answer and the one `:extra-config` may
  want to branch on."
  [tmp-dir resource-base]
  (classpath/copy-resources! resource-base
                             (classpath/get-resource-file-paths resource-base)
                             (io/file tmp-dir "imports" (get-import-dir resource-base))))

(defn get-root-config
  "The root `config.edn` for a materialized dir: `base-config` with
  `extra-config` merged over it, one level deep.

  `extra-config` is a map, or `(fn [{resource-base copied-file-count}] -> map)`
  for a caller whose extra config depends on which exports were actually found —
  a coarse `:lint-as` fallback for an export that is absent, say, which must not
  be in effect when the export is present and the two would disagree about the
  same var.

  Pure: the decision, not the write."
  [base-config extra-config import-counts]
  (let [extra (if (fn? extra-config) (extra-config import-counts) extra-config)]
    (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                (or base-config {})
                (or extra {}))))

(defn materialize-config-dir!
  "Build a clj-kondo `:config-dir` in a fresh temp dir and return its path:

    1. the bundled config (clara-rules `defrule`/`defquery` hooks), copied off
       the classpath — since `:config-dir` replaces that bundle wholesale,
       re-materializing it is what keeps those hooks;
    2. each of `:imports` — a clj-kondo export resource base such as
       `\"clj-kondo.exports/acme/rules\"` — dropped in under
       `imports/<org>/<lib>/`;
    3. a root `config.edn`: the bundled one with `:extra-config` merged over it
       (see `get-root-config`).

  The directory is a plain temp dir and is never cleaned up; a caller that builds
  one per process should hold it in a `delay`, since the result is identical for
  every run on a fixed classpath."
  [{:keys [imports extra-config]}]
  (let [tmp-dir (create-temp-dir! "clara-explorer-kondo")
        manifest (classpath/read-edn-resource (str bundled-config-resource "/manifest.edn"))
        base-config (classpath/read-edn-resource (str bundled-config-resource "/config.edn"))]
    ;; The root config.edn is overlaid last, never copied verbatim.
    (classpath/copy-resources! bundled-config-resource
                               (remove #{"config.edn"} (:files manifest))
                               tmp-dir)
    (let [counts (into {} (map (juxt identity #(copy-import! tmp-dir %))) imports)]
      (spit (io/file tmp-dir "config.edn")
            (pr-str (get-root-config base-config extra-config counts))))
    (.getAbsolutePath tmp-dir)))
