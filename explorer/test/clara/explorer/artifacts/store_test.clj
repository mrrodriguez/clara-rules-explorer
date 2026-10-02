(ns clara.explorer.artifacts.store-test
  "Path resolution and layer round-tripping.

  The path tests go through an explicit `:dir`; the `:root`/`:repo` branch of
  `get-out-dir` is the same code path with a different base. The layer IO tests
  write into the temp dir `test-fixtures` provides."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.explorer.artifacts.shared.git :as shared-git]
   [clara.explorer.artifacts.store :as store]
   [clara.explorer.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts* generated-annotations read-generated-layer
            write-generated-layer!]]
   [schema.core :as s]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

(defn- with-git
  "Run `(f)` with `shared-git/get-git-info` stubbed to return `git-info` for
  every dir."
  [git-info f]
  (with-redefs [shared-git/get-git-info (fn [_] git-info)]
    (f)))

(deftest out-dir-test
  (testing "an explicit :dir is the base, unchanged — no git read"
    (is (= "/tmp/annos" (store/get-out-dir {:dir "/tmp/annos"})))
    (is (= "/tmp/annos"
           (store/get-out-dir {:dir "/tmp/annos" :variant [[:ref "x"]]}))))
  (testing "without :canonical? the :variant is used as-is (the read path)"
    (is (= "/r/x" (store/get-out-dir {:root "/r" :repo "x"})))
    (is (= "/r/_variants/x/ref=feature%2Fnew-tax"
           (store/get-out-dir {:root "/r" :repo "x"
                               :variant [[:ref "feature/new-tax"]]}))))
  (testing "no output target at all is an error, not a silent cwd write"
    (is (thrown? clojure.lang.ExceptionInfo (store/get-out-dir {})))))

(deftest out-dir-mainline-rule-test
  (testing "canonical + default branch writes the base dir"
    (with-git {:branch "main" :sha-short "abc1234" :default-branch "main"}
      #(is (= "/r/x"
              (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? true})))))
  (testing "canonical + other branch writes under _variants/"
    (with-git {:branch "feature" :sha-short "abc1234" :default-branch "main"}
      #(is (= "/r/_variants/x/ref=feature"
              (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? true})))))
  (testing "non-canonical + default branch writes under _variants/"
    (with-git {:branch "main" :sha-short "abc1234" :default-branch "main"}
      #(is (= "/r/_variants/x/ref=main"
              (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? false})))))
  (testing "a nil :default-branch makes no run mainline"
    (with-git {:branch "main" :sha-short "abc1234" :default-branch nil}
      #(is (= "/r/_variants/x/ref=main"
              (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? true})))))
  (testing "a detached commit with no remote branch falls back to the short sha"
    (with-git {:branch nil :sha-short "abc1234" :default-branch "main"}
      #(is (= "/r/_variants/x/ref=abc1234"
              (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? false})))))
  (testing "host axes prefix the ref, in order"
    (with-git {:branch "feature" :sha-short "abc1234" :default-branch "main"}
      #(is (= "/r/_variants/x/region=eu/tier=gold/ref=feature"
              (store/get-out-dir {:root "/r" :repo "x"
                                  :variant [[:region "eu"] [:tier "gold"]]
                                  :canonical? false})))))
  (testing "a non-git checkout on the write path is refused"
    (with-git nil
      #(is (thrown? clojure.lang.ExceptionInfo
                    (store/get-out-dir {:root "/r" :repo "x" :variant [] :canonical? true}))))))

(deftest out-dir-variant-validation-test
  (testing "bad axis names are refused before any git read"
    (doseq [axis [:ref :Bad-axis :-bad :a_b :region/eu]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/get-out-dir {:root "/r" :repo "x"
                                       :variant [[axis "v"]] :canonical? false}))
          (str "expected a throw for axis " (pr-str axis)))))
  (testing "a blank value is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/get-out-dir {:root "/r" :repo "x"
                                     :variant [[:region ""]] :canonical? false}))))
  (testing "values that encode to . or .. are refused"
    (doseq [v ["." ".."]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/get-out-dir {:root "/r" :repo "x"
                                       :variant [[:region v]] :canonical? false}))))))

(deftest out-dir-repo-validation-test
  (testing "a repo path that cannot round-trip through discovery is refused"
    (doseq [repo ["a=b" "a/../b" "a//b" "a/./b"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/get-out-dir {:root "/r" :repo repo}))
          (str "expected a throw for repo " (pr-str repo)))))
  (testing "an explicit :dir wins, so a bad repo beside it is irrelevant"
    (is (= "/tmp/annos" (store/get-out-dir {:dir "/tmp/annos" :repo "a=b"})))))

(deftest artifact-path-test
  (testing "every artifact resolves under the variant dir when one is set"
    (let [opts {:root "/r" :repo "x" :variant [[:ref "spike"]]}]
      (doseq [k (keys store/artifact-files)]
        (is (= (str (io/file "/r/_variants/x/ref=spike" (get store/artifact-files k)))
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
