(ns clara.server.tools.graph.artifacts.store-test
  "Path resolution and layer round-tripping.

  The path tests go through an explicit `:dir`; the `:root`/`:repo` branch of
  `get-out-dir` is the same code path with a different base. The layer IO tests
  write into the temp dir `test-fixtures` provides."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts* generated-annotations read-generated-layer
            write-generated-layer!]]
   [schema.core :as s]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

(deftest out-dir-test
  (testing "an explicit :dir is the base, unchanged"
    (is (= "/tmp/annos" (store/get-out-dir {:dir "/tmp/annos"}))))
  (testing "a blank/absent branch leaves the base alone — mainline is the default"
    (is (= "/tmp/annos" (store/get-out-dir {:dir "/tmp/annos" :branch nil})))
    (is (= "/tmp/annos" (store/get-out-dir {:dir "/tmp/annos" :branch "  "}))))
  (testing "a branch nests one level down, under branches/"
    (is (= "/tmp/annos/branches/vc-income-refactor"
           (store/get-out-dir {:dir "/tmp/annos" :branch "vc-income-refactor"}))))
  (testing "the label is trimmed, and slashes nest the way the name reads"
    (is (= "/tmp/annos/branches/feature/vc-income"
           (store/get-out-dir {:dir "/tmp/annos" :branch " feature/vc-income "}))))
  (testing "no branch can climb out of the base dir"
    (doseq [bad ["../elsewhere" "a/../../b" "." "a//b" "/absolute"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/get-out-dir {:dir "/tmp/annos" :branch bad}))
          (str "expected a throw for " (pr-str bad)))))
  (testing "no output target at all is an error, not a silent cwd write"
    (is (thrown? clojure.lang.ExceptionInfo (store/get-out-dir {})))))

(deftest artifact-path-test
  (testing "every artifact resolves under the branch dir when one is set"
    (let [opts {:dir "/tmp/annos" :branch "spike"}]
      (doseq [k (keys store/artifact-files)]
        (is (= (str (io/file "/tmp/annos/branches/spike" (get store/artifact-files k)))
               (store/get-artifact-path k opts))))))
  (testing "an unknown artifact key is rejected by the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match schema"
                          (store/get-artifact-path :nope {:dir "/tmp/annos"}))))
  (testing "…and by the runtime guard underneath it, which is what a caller meets
            in production, where fn validation is off. It names the keys that do
            exist, which the schema rejection cannot"
    (s/without-fn-validation
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown artifact"
                           (store/get-artifact-path :nope {:dir "/tmp/annos"}))))))

;; ---------------------------------------------------------------------------
;; layer IO
;; ---------------------------------------------------------------------------

(deftest layer-round-trip-test
  (write-generated-layer!)
  (let [layer (read-generated-layer)]
    (testing "the artifact is a Layer, and reading forces the id its role implies"
      (is (= store/generated-layer-id (:id layer)))
      (is (= (set (keys generated-annotations)) (set (keys (:annotations layer))))))
    (testing "every callsite got an id on the way in — the handle curation needs"
      (is (every? :callsite-id
                  (mapcat (comp :callsites :clara-rules/dynamic-insert-types-detected)
                          (vals (:annotations layer))))))
    (testing "ids are stable across a rewrite: they hash the callsite's own basis,
              so re-generating unchanged source re-derives the same handles"
      (store/write-layer! :auto *artifact-opts*
                          (store/->generated-layer *artifact-opts* generated-annotations))
      (is (= layer (read-generated-layer))))))

(deftest read-layer-reports-a-mislabelled-file-test
  ;; `layer-round-trip-test` cannot show any of this: it reads back a file this
  ;; repo wrote, whose id already matches its role. The overlay is the one file
  ;; edited by hand, so it is the one whose id can disagree.
  (let [path (store/get-artifact-path :agent *artifact-opts*)
        write! (fn [m] (io/make-parents path) (spit path (pr-str m)))
        annotations {"a.ns/r" {:clara-rules/insert-types [:a/x]}}]
    (testing "an overlay copied from auto-gen-annotations.edn is not quietly relabelled"
      (write-generated-layer!)
      (write! {:id store/generated-layer-id :annotations annotations})
      (is (= store/generated-layer-id (:id (store/read-layer :agent *artifact-opts*)))
          "forcing the role's id would file analyzer output under :provenance :agent")
      (is (thrown-with-msg? Exception #"(?i)duplicate layer"
                            (store/->merged-annotations *artifact-opts*))
          "the fold is where it surfaces, naming the collision"))
    (testing "a file omitting :id fails as a Layer rather than reading as one"
      (write! {:annotations annotations})
      (is (thrown? Exception (store/read-layer :agent *artifact-opts*))
          "no writer here can emit one — every layer goes through ann.merge/->layer"))
    (testing ":source defaults to the path, and a file stating its own keeps it"
      (write! {:id store/agent-layer-id :annotations annotations})
      (is (= path (:source (store/read-layer :agent *artifact-opts*))))
      (write! {:id store/agent-layer-id
               :source "hand-written during a curation pass"
               :annotations annotations})
      (is (= "hand-written during a curation pass"
             (:source (store/read-layer :agent *artifact-opts*)))))))

(deftest read-layer-is-nil-when-absent-test
  (is (nil? (store/read-layer :agent *artifact-opts*)))
  (is (nil? (store/read-merged-annotations *artifact-opts*))))
