(ns clara.explorer.artifacts.manifest-test
  "The manifest's `:history` never holds an exact duplicate: `write-manifest!`
  appends without duplicating, and `shared-manifest/dedupe-history` cleans
  manifests that already hold duplicates.

  What these pin: regenerating a unit repeatedly on one day writes the same
  `{:date :change}` entry every time, so without dedupe the history fills with
  identical entries that bury the ones marking a real change in date."
  (:require
   [clara.explorer.artifacts.manifest :as manifest]
   [clara.explorer.artifacts.shared.manifest :as shared-manifest]
   [clara.explorer.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(defn- create-temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-manifest-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- manifest-opts [dir]
  ;; `:repo-path` points at the temp dir — not a git repo, so no git read — and
  ;; `:dir` wins outright in `store/get-out-dir`, so nothing else is needed.
  {:dir dir :repo "x" :generated-by "manifest-test" :repo-path dir})

(defn- read-manifest [path]
  (edn-io/read-edn-file (io/file path)))

(defn- overwrite-history! [path history]
  (edn-io/write-edn-file! (io/file path)
                          (assoc (read-manifest path) :history history)))

(deftest consecutive-same-day-writes-keep-one-entry-test
  (let [dir (create-temp-dir)]
    (try
      (let [opts (manifest-opts dir)
            path (manifest/write-manifest! opts)]
        (manifest/write-manifest! opts)
        (is (= 1 (count (:history (read-manifest path))))))
      (finally
        (delete-tree dir)))))

(deftest later-date-write-appends-test
  (let [dir (create-temp-dir)]
    (try
      (let [opts (manifest-opts dir)
            path (manifest/write-manifest! opts)
            [entry] (:history (read-manifest path))]
        (overwrite-history! path [(assoc entry :date "2020-01-01")])
        (let [history (:history (read-manifest (manifest/write-manifest! opts)))]
          (is (= 2 (count history)))
          (is (= ["2020-01-01" (:date entry)] (mapv :date history)))))
      (finally
        (delete-tree dir)))))

(deftest same-date-different-change-survives-test
  (let [dir (create-temp-dir)]
    (try
      (let [opts (manifest-opts dir)]
        (manifest/write-manifest! (assoc opts :change "first"))
        (let [history (:history (read-manifest (manifest/write-manifest!
                                                (assoc opts :change "second"))))]
          (is (= ["first" "second"] (mapv :change history)))))
      (finally
        (delete-tree dir)))))

(deftest next-write-dedupes-existing-duplicates-test
  (let [dir (create-temp-dir)]
    (try
      (let [opts (manifest-opts dir)
            path (manifest/write-manifest! (assoc opts :change "gamma"))
            [fresh] (:history (read-manifest path))
            first-entry (assoc fresh :change "alpha")
            second-entry (assoc fresh :change "beta")]
        (overwrite-history! path [first-entry second-entry first-entry])
        (let [history (:history (read-manifest (manifest/write-manifest!
                                                (assoc opts :change "gamma"))))]
          (is (= [first-entry second-entry fresh] history))))
      (finally
        (delete-tree dir)))))

(deftest dedupe-history-test
  (let [a {:date "2026-01-01" :change "one"}
        b {:date "2026-01-02" :change "two"}]
    (testing "removes exact duplicates, keeping the first occurrence"
      (is (= [a b] (:history (shared-manifest/dedupe-history {:history [a b a]})))))
    (testing "is idempotent"
      (let [m {:history [a b a]}]
        (is (= (shared-manifest/dedupe-history m)
               (shared-manifest/dedupe-history (shared-manifest/dedupe-history m))))))
    (testing "a manifest without :history is returned unchanged"
      (let [m {:repo "x"}]
        (is (= m (shared-manifest/dedupe-history m)))
        (is (not (contains? (shared-manifest/dedupe-history m) :history)))))
    (testing "leaves every other key untouched"
      (is (= {:repo "x" :source {:sha "abc"} :history [a]}
             (shared-manifest/dedupe-history {:repo "x"
                                              :source {:sha "abc"}
                                              :history [a a]}))))))
