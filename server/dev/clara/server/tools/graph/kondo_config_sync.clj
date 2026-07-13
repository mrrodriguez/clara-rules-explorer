(ns clara.server.tools.graph.kondo-config-sync
  "Maintenance helper: replicate the clj-kondo `imports` config into the
   classpath `resources` tree so the analyzer can load the clara-rules hooks as
   a standalone tool, regardless of the JVM working directory.

   IMPORTANT: `.clj-kondo/imports/clara/rules` is NOT a hand-written, manually
   copied config. clj-kondo generates it from the clara-rules dependency's
   *exported* clj-kondo config via its `--copy-configs --dependencies`
   mechanism. This helper's only job is to mirror that generated tree onto the
   classpath resources path that the analyzer materializes at runtime (see
   `clara.server.tools.graph.analyze`).

   Our own override files (config.edn, hooks/strip_lhs.clj_kondo) live alongside
   the synced imports in the resources tree. They are maintained by us, not
   synced. See EXPLORER-OVERRIDE markers in this file and the hook file.

   Typical maintenance flow when the clara-rules dependency changes:

     ;; 1. Let clj-kondo regenerate .clj-kondo/imports from the dependencies:
     clojure -M:lint --copy-configs --dependencies --lint \"$(clojure -Spath)\"

     ;; 2. Mirror the generated import onto the bundled resources path:
     clojure -X:sync-kondo-config

     ;; 3. (CI) Verify the bundled resources are not stale:
     clojure -X:sync-kondo-config clara.server.tools.graph.kondo-config-sync/check"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.nio.file Files]))

(def ^:private default-source-dir
  "clj-kondo's own config tree (populated by --copy-configs --dependencies)."
  ".clj-kondo")

(def ^:private default-resources-base
  "Classpath resource base the analyzer materializes from. Kept in sync with
   `clara.server.tools.graph.analyze`'s `bundled-kondo-config-resource`."
  "resources/clara/server/tools/graph/kondo-config")

(def ^:private bundled-imports
  "Import subtrees (relative to the source dir) the analyzer needs. Only the
   clara-rules hooks are required to expand `defrule`/`defquery` etc."
  ["imports/clara/rules"])

;; ═══════════════════════════════════════════════════════════════════════════════
;; EXPLORER-OVERRIDE: files we maintain alongside the synced imports.
;; These override or extend the clara-rules config (e.g. LHS-stripping hook).
;; They are NOT synced from clara-rules — they live in the resources directory
;; directly and are part of the manifest so they get materialized at runtime.
;; ═══════════════════════════════════════════════════════════════════════════════

(def ^:private override-files
  "Override files (relative to resources-base) maintained by us, not synced."
  ["config.edn"
   "hooks/strip_lhs.clj_kondo"])

(defn- rel-path [^java.io.File root ^java.io.File f]
  (str (.relativize (.toPath root) (.toPath f))))

(defn- delete-recursively! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File child (reverse (file-seq f))]
      (.delete child))))

(defn- source-files
  "Returns a sorted vector of relative paths (relative to `src`) for every file
   under the bundled import subtrees. Throws if a subtree is missing."
  [^java.io.File src]
  (vec
   (sort
    (mapcat
     (fn [subtree]
       (let [sub (io/file src subtree)]
         (when-not (.exists sub)
           (throw (ex-info (str "Expected clj-kondo import not found: " sub
                                ". Run clj-kondo --copy-configs --dependencies first.")
                           {:missing (str sub)})))
         (into []
               (comp (filter #(.isFile ^java.io.File %))
                     (map #(rel-path src %)))
               (file-seq sub))))
     bundled-imports))))

(defn- expected-layout
  "Computes the full {rel-path bytes} map the resources dir should contain,
   including the top-level config.edn, override files, and the manifest."
  [^java.io.File src]
  (let [import-rels (source-files src)
        base (into {"config.edn" (.getBytes "{:hooks {:analyze-call {clara.rules/defrule hooks.strip-lhs/analyze-defrule-macro}}}\n")}
                   (map (fn [rel] [rel (Files/readAllBytes (.toPath (io/file src rel)))]))
                   import-rels)
        ;; Add override file contents from the resources dir (if they exist)
        dest-root (io/file default-resources-base)
        base (reduce (fn [m rel]
                       (let [f (io/file dest-root rel)]
                         (if (.exists f)
                           (assoc m rel (Files/readAllBytes (.toPath f)))
                           m)))
                     base
                     override-files)
        all-files (vec (sort (conj (keys base) "manifest.edn")))
        manifest {:files all-files}
        manifest-bytes (.getBytes (with-out-str (pp/pprint manifest)))]
    (assoc base "manifest.edn" manifest-bytes)))

(defn sync!
  "Mirrors the clj-kondo import tree into the bundled resources path,
   including our override files (config.edn, hooks/strip_lhs.clj_kondo).

   Options (all optional):
     :source-dir     - clj-kondo config dir to read from (default \".clj-kondo\")
     :resources-base - resources dir to write to (default under resources/clara/...)"
  ([] (sync! {}))
  ([{:keys [source-dir resources-base]
     :or {source-dir default-source-dir
          resources-base default-resources-base}}]
   (let [src (io/file source-dir)
         dest-root (io/file resources-base)
         layout (expected-layout src)]
     (delete-recursively! dest-root)
     (doseq [[rel ^bytes content] layout]
       (let [dest (io/file dest-root rel)]
         (io/make-parents dest)
         (with-open [out (io/output-stream dest)]
           (.write out content))))
     (println (format "Synced %d file(s) to %s" (count layout) resources-base))
     (doseq [rel (sort (keys layout))]
       (println "  " rel))
     {:synced (count layout) :resources-base resources-base})))

(defn check
  "Verifies the bundled resources match the current clj-kondo import tree
   plus our override files. Throws (non-zero exit under -X) if stale."
  ([] (check {}))
  ([{:keys [source-dir resources-base]
     :or {source-dir default-source-dir
          resources-base default-resources-base}}]
   (let [src (io/file source-dir)
         dest-root (io/file resources-base)
         expected (expected-layout src)
         actual-files (into #{}
                            (comp (filter #(.isFile ^java.io.File %))
                                  (map #(rel-path dest-root %)))
                            (file-seq dest-root))
         missing (remove actual-files (keys expected))
         extra (remove (set (keys expected)) actual-files)
         changed (for [[rel ^bytes content] expected
                       :when (actual-files rel)
                       :let [on-disk (Files/readAllBytes (.toPath (io/file dest-root rel)))]
                       :when (not (java.util.Arrays/equals content ^bytes on-disk))]
                   rel)
         drift (concat (map #(str "missing: " %) missing)
                       (map #(str "extra:   " %) extra)
                       (map #(str "changed: " %) changed))]
     (if (seq drift)
       (do (println "Bundled clj-kondo config is STALE. Run: clojure -X:sync-kondo-config")
           (doseq [d drift] (println "  " d))
           (throw (ex-info "Bundled clj-kondo config is out of sync" {:drift (vec drift)})))
       (do (println "Bundled clj-kondo config is up to date.")
           {:ok true})))))
