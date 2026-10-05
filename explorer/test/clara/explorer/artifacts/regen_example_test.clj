(ns clara.explorer.artifacts.regen-example-test
  "Golden test for the checked-in persistence examples under
   `example/example-out-dir`.

   Regenerates the whole artifact registry into a temp dir via
  `clara.explorer.artifacts.regen-example/generate-example-artifacts!` and asserts the
  checked-in copy is reproduced: every `clara.explorer.artifacts.flow/persist!` artifact,
  and each ruleset's provenance manifest apart from the fields that are environment rather than
  generation, eg. run dates and git state.

   The comparison is exact bytes: minted names (auto-gensyms, digest-suffixed
   locals) are canonicalized at emission, so a regeneration from any JVM (a
   fresh `make regen-artifacts`, the test runner, a REPL) yields identical
   bytes."
  (:require
   [clara.explorer.artifacts.regen-example :as example]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(defn- create-temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-example-golden"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- checked-in-dir []
  (let [first-repo (:repo (first example/example-rulesets))]
    (-> (io/resource (format "%s/%s/auto-gen-annotations.edn"
                             example/example-registry-base
                             first-repo))
        .getPath
        io/file
        .getParentFile
        .getParentFile
        .getPath)))

(defn- get-snapshot
  "Every regular file under `dir`, as a map of its path relative to `dir` to its
  exact bytes — the same shape the byte-stability test in
  `clara.explorer.artifacts.flow-test` uses, so a run can be compared
  file-by-file and a mismatch names the file."
  [dir]
  (into (sorted-map)
        (comp (filter #(.isFile ^java.io.File %))
              (map (fn [^java.io.File f]
                     [(subs (.getPath f) (count (.getPath (io/file dir))))
                      (slurp f)])))
        (file-seq (io/file dir))))

(defn- dissoc-manifests
  "Manifest files are compared field-wise below, not byte-for-byte, so drop
   every `rules-inspect-manifest.edn` path (one per ruleset bundle)."
  [snapshot]
  (into (sorted-map)
        (remove (fn [[path _]]
                  (str/ends-with? path "/rules-inspect-manifest.edn")))
        snapshot))

(def ^:private manifest-volatile-keys
  "The manifest fields that record *when* and *where* a run happened, not what
  it generated: run dates and the git state of the checkout it was generated
  from."
  #{:created :updated :history :source})

(defn- normalize-manifest [m]
  (let [m (apply dissoc m manifest-volatile-keys)
        strip-source-state (fn [unit] (dissoc unit :sha :created))]
    (cond-> m
      (contains? m :analysis-run)
      (update :analysis-run
              (fn [analysis-run]
                (if-let [units (:units analysis-run)]
                  (assoc analysis-run :units (mapv strip-source-state units))
                  analysis-run)))

      (contains? m :staleness)
      (update :staleness
              (fn [staleness]
                (if-let [sources (:sources staleness)]
                  (assoc staleness :sources (update-vals sources strip-source-state))
                  staleness))))))

(defn- read-manifest [dir]
  (edn/read-string (slurp (io/file dir "rules-inspect-manifest.edn"))))

(deftest regenerated-artifacts-match-checked-in-example-test
  (let [tmp (create-temp-dir)
        repos example/example-repos
        source-repos (mapv :repo example/example-rulesets)]
    (try
      (let [result (example/generate-example-artifacts! tmp)
            expected (get-snapshot (checked-in-dir))
            actual (get-snapshot tmp)
            expected-files (dissoc-manifests expected)
            actual-files (dissoc-manifests actual)]
        (testing "the checked-in registry is non-empty"
          (is (seq expected-files)
              (format "no checked-in artifacts found under %s"
                      example/example-registry-base)))
        (testing "every checked-in bundle is present"
          (doseq [repo repos]
            (is (some #(str/starts-with? % (str "/" repo "/"))
                      (keys expected-files))
                (str repo " bundle is checked in"))))
        (testing "generation returns the source rulesets and composed unit"
          (is (= (set source-repos)
                 (set (map :repo (:rulesets result)))))
          (is (= example/composed-example-repo
                 (get-in result [:composed :repo]))))
        (testing "the single-ns disposition ruleset has no memory layer"
          (let [disposition (first (filter #(= "loan-disposition-ruleset" (:repo %))
                                           (:rulesets result)))]
            (is (zero? (:memory-rule-count disposition))
                "loan-disposition-ruleset is unfired and has no memory-derived rules")))
        (testing "regeneration produces the same set of files"
          (is (= (set (keys expected-files)) (set (keys actual-files)))))
        (testing "every flow/persist! artifact is reproduced byte-for-byte"
          (doseq [path (keys expected-files)]
            (is (= (get expected-files path) (get actual-files path))
                (str path " is not reproduced by regeneration"))))
        (testing "every provenance manifest matches apart from its environment fields"
          (doseq [repo repos]
            (is (= (normalize-manifest (read-manifest (io/file (checked-in-dir) repo)))
                   (normalize-manifest (read-manifest (io/file tmp repo))))
                (str repo " manifest is reproduced")))))
      (finally
        (delete-tree tmp)))))
