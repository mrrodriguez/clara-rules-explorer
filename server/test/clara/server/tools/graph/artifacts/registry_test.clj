(ns clara.server.tools.graph.artifacts.registry-test
  "Tests for the artifact registry: discovery over a root, per-unit reads, and
  the compatibility report.  Units are hand-written into a temp root — no
  ruleset, session, or classpath beyond the artifact library."
  (:require
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

;; ---------------------------------------------------------------------------
;; temp root
;; ---------------------------------------------------------------------------

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-registry-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- with-temp-root [f]
  (let [root (temp-dir)]
    (try (f root)
         (finally (delete-tree root)))))

;; ---------------------------------------------------------------------------
;; unit writers
;; ---------------------------------------------------------------------------

(defn- manifest
  [opts]
  {:repo (:repo opts)
   :generated-by "registry-test"
   :created "2025-01-01"
   :analysis-run {:layer-ids store/layer-artifacts}
   :history [{:date "2025-01-01" :change "test"}]})

(defn- write-manifest! [dir repo]
  (let [file (io/file dir (:manifest layout/artifact-files))]
    (io/make-parents file)
    (edn-io/write-edn-file! file (manifest {:repo repo}))
    (str file)))

(defn- write-analysis!
  "Write a minimal slim analysis split into the six part files. `dropped` is the
  unit's slim `:dropped` shape, the value `compatibility-report` compares."
  [dir dropped]
  (store/write-analysis-parts!
   {:dir (str dir)}
   {:rules {}
    :queries {}
    :fact-types {}
    :dep-graph {}
    :unresolved []
    :slim {:written-by "clara.server.tools.graph.artifacts.slim"
           :dropped dropped
           :references "test"
           :unknown-fact-types #{}
           :recover {}}}))

(defn- write-unit!
  "A complete minimal unit: manifest + analysis parts with the given slim shape."
  [root repo dropped]
  (let [dir (io/file root repo)]
    (write-manifest! dir repo)
    (write-analysis! dir dropped)
    (str dir)))

;; ---------------------------------------------------------------------------
;; discovery
;; ---------------------------------------------------------------------------

(deftest discover-finds-units-by-manifest
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      (write-unit! root "b" #{:nodes :id})
      ;; a dir without a manifest is not a unit
      (let [not-a-unit (io/file root "c" "sub")]
        (.mkdirs not-a-unit)
        (edn-io/write-edn-file! (io/file not-a-unit "other.edn") {:x 1}))
      ;; mainline + a branch variant
      (write-unit! root "d" #{:nodes :id})
      (write-unit! (io/file root "d" "branches") "feature-x" #{:nodes :id})

      (let [reg (registry/discover {:root root})
            refs (registry/units reg)]
        (is (= #{"a" "b" "d" "d@feature-x"}
               (set (map registry/unit-key refs))))
        (testing "branch variants are refs with a :branch label"
          (is (= {:repo "d" :branch "feature-x"}
                 (first (filter #(= "feature-x" (:branch %)) refs)))))
        (testing "mainline units carry no :branch"
          (is (= [{:repo "a"} {:repo "b"} {:repo "d"}]
                 (remove :branch refs))
              "a mainline unit ref has no :branch key"))))))

(deftest ->registry-takes-explicit-units
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      (let [reg (registry/->registry {:root root
                                      :units [{:repo "a"}
                                              {:repo "missing"}]})]
        (is (= ["a" "missing"] (mapv registry/unit-key (registry/units reg))))
        (is (nil? (registry/read-manifest reg {:repo "missing"}))
            "an absent unit reads as nil rather than throwing")))))

(deftest same-registry-ignores-the-memo-cache-test
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      (let [a (registry/->registry {:root root :units [{:repo "a"}]})
            b (registry/->registry {:root root :units [{:repo "a"}]})]
        (is (not= a b)
            "the memoization atom makes the record identity-bearing, not a pure value")
        (is (registry/same-registry? a b)
            "same-registry? compares root + units and ignores the cache atom")))))

;; ---------------------------------------------------------------------------
;; reads
;; ---------------------------------------------------------------------------

(deftest read-analysis-round-trips-parts
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      (let [reg (registry/discover {:root root})
            analysis (registry/read-analysis reg {:repo "a"})]
        (is (= #{:nodes :id} (get-in analysis [:slim :dropped])))
        (is (= {} (:rules analysis)))
        (is (= {} (:fact-types analysis)))
        (is (= [] (:unresolved analysis)))))))

(deftest read-annotations-expands-layer-references
  (with-temp-root
    (fn [root]
      (let [dir (io/file root "a")]
        (write-manifest! dir "a")
        (store/write-layer! :auto {:dir (str dir)}
                            (store/->generated-layer {:generated-by "registry-test"}
                                                     {"a.ns/rule" {:clara-rules/insert-types [:a/one]}}))
        (let [merged (store/->merged-annotations {:dir (str dir)})
              layers (store/->layer-annotations-stack {:dir (str dir)})]
          (store/write-merged-annotations! {:dir (str dir)} merged layers))

        (let [reg (registry/discover {:root root})]
          (is (= (store/read-merged-annotations {:dir (str dir)})
                 (registry/read-annotations reg {:repo "a"}))))))))

;; ---------------------------------------------------------------------------
;; compatibility
;; ---------------------------------------------------------------------------

(deftest compatibility-report-names-shape-skew
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      (write-unit! root "b" #{:nodes :id})
      (write-unit! root "c" #{:nodes :id :lhs-form})

      (let [reg (registry/discover {:root root})
            report (registry/compatibility-report reg)]
        (is (false? (:compatible? report)))
        (is (= #{:nodes :id} (:majority-shape report)))
        (is (= [{:repo "c"}] (:shape-mismatch report)))
        (is (= #{:nodes :id :lhs-form}
               (get (:dropped-key-sets report) "c")))

        (testing "a selection that shares one shape is compatible"
          (let [subset (registry/compatibility-report reg [{:repo "a"} {:repo "b"}])]
            (is (true? (:compatible? subset)))
            (is (= [] (:shape-mismatch subset)))))

        (testing "assert-compatible! throws over the skew"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"differing slim shapes"
                                (registry/assert-compatible! reg (registry/units reg)))))))))

(deftest compatibility-report-lists-missing-artifacts
  (with-temp-root
    (fn [root]
      (write-unit! root "a" #{:nodes :id})
      ;; a unit with only a manifest, nothing else written
      (write-manifest! (io/file root "b") "b")

      (let [reg (registry/discover {:root root})
            report (registry/compatibility-report reg)]
        (is (contains? (:missing-artifacts report) "b")
            "the manifest-only unit reports missing artifacts")
        (is (not (contains? (get (:missing-artifacts report) "a") :manifest))
            "the complete unit keeps the manifest out of its missing set")
        (is (contains? (get (:missing-artifacts report) "b") :rulebase-analysis)
            "the manifest-only unit is missing the analysis directory")))))
