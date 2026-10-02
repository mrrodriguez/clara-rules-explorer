(ns clara.explorer.artifacts.shared.status-test
  " `unit-status` over temp unit dirs holding hand-written manifests, plus
  stubbed checkout git reads.

  Manifest IO is real (written with `pr-str`, read back through the same path
  the bb report uses); only the *checkout* side is stubbed, since a status
  comparison needs commits the test would otherwise have to manufacture."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clara.explorer.artifacts.shared.git :as git]
   [clara.explorer.artifacts.shared.status :as status]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; fixtures
;; ===========================================================================

(def ^:private today
  (str (java.time.LocalDate/now)))

(defn- make-temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree
  [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- with-temp-root*
  "Run `(f root)` with a fresh temp dir, deleting it afterwards."
  [f]
  (let [root (make-temp-dir "clara-status-test")]
    (try (f root) (finally (delete-tree root)))))

(defn- write-manifest!
  "Write `manifest` as the unit manifest in `dir`, returning `dir`."
  [dir manifest]
  (let [dir-file (io/file dir)]
    (.mkdirs dir-file)
    (spit (io/file dir-file (:manifest layout/artifact-files)) (pr-str manifest))
    (str dir-file)))

(defn- source-manifest
  []
  {:repo "loan-app-ruleset"
   :variant [[:ref "alt"]]
   :generated-by "status-test"
   :created today
   :updated today
   :source {:working-tree-notes ""
            :remote "https://github.com/acme/loan-app-ruleset.git"
            :sha "ac51808deadbeef"
            :sha-short "ac51808"
            :branch "feature-x"
            :working-tree "clean"}
   :analysis-run {:method "status-test"}
   :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
   :history [{:date today :change "test"}]})

(defmacro ^:private with-checkout
  "Stub the checkout git reads: refs resolve via `ref-shas`, the origin is
  `remote`, the tree is clean unless `clean?` is false."
  [{:keys [ref-shas remote clean?] :or {clean? true}} & body]
  `(with-redefs [git/ref-sha (fn [_# ref#] (get ~ref-shas ref#))
                 git/remote-url (fn [_#] ~remote)
                 git/clean-tree? (fn [_#] ~clean?)]
     ~@body))

(def ^:private recorded-sha "ac51808deadbeef")
(def ^:private recorded-remote "https://github.com/acme/loan-app-ruleset.git")

;; ===========================================================================
;; source units
;; ===========================================================================

(deftest source-unit-status-test
  (with-temp-root* (fn [root]
                     (let [dir (write-manifest! (str (io/file root "_variants" "loan-app-ruleset" "ref=alt"))
                                                (source-manifest))]

                       (testing "a checkout at the recorded sha is current"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :current (:verdict result)))
                             (is (= [] (:reasons result)))
                             (is (= :source (:kind result)))
                             (is (= [[:ref "alt"]] (:variant result)))
                             (is (= {:checkout "/co" :ref "HEAD" :sha recorded-sha}
                                    (:compared result))))))

                       (testing "remote spellings that normalize equal compare equal"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha}
                                         :remote "https://github.com/acme/loan-app-ruleset.git/"}
                           (is (= :current (:verdict (status/unit-status {:dir dir :checkout "/co"}))))))

                       (testing "one extra commit is sha-drift"
                         (with-checkout {:ref-shas {"HEAD" "9b1e0f2aaaa"} :remote recorded-remote}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :stale (:verdict result)))
                             (is (= [{:check :sha-drift
                                      :recorded recorded-sha
                                      :current "9b1e0f2aaaa"}]
                                    (:reasons result))))))

                       (testing "a different origin is remote-mismatch, with no sha comparison"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote "https://github.com/other/fork.git"}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :stale (:verdict result)))
                             (is (= [{:check :remote-mismatch
                                      :recorded recorded-remote
                                      :current "https://github.com/other/fork.git"}]
                                    (:reasons result))))))

                       (testing "a unit generated dirty is stale however clean the checkout is"
                         (let [dirty-dir (write-manifest!
                                          (str (io/file root "dirty-unit"))
                                          (assoc-in (source-manifest) [:source :working-tree] "dirty"))]
                           (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote}
                             (let [result (status/unit-status {:dir dirty-dir :checkout "/co"})]
                               (is (= :stale (:verdict result)))
                               (is (= [{:check :generated-dirty :working-tree "dirty"}]
                                      (:reasons result)))))))

                       (testing "a dirty checkout is informational: still current"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote :clean? false}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :current (:verdict result)))
                             (is (= [{:check :checkout-dirty :checkout "/co" :informational true}]
                                    (:reasons result))))))

                       (testing "an unknown ref is unknown, not stale"
                         (with-checkout {:ref-shas {} :remote recorded-remote}
                           (let [result (status/unit-status {:dir dir :checkout "/co" :ref "nope"})]
                             (is (= :unknown (:verdict result)))
                             (is (= [{:check :ref-unresolvable :ref "nope" :checkout "/co"}]
                                    (:reasons result))))))

                       (testing "a checkout that is not a repo is unknown, not a remote-mismatch"
                         (with-checkout {:ref-shas {} :remote nil}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :unknown (:verdict result)))
                             (is (= [{:check :checkout-not-a-repo :checkout "/co"}]
                                    (:reasons result))))))

                       (testing "without a checkout only the checkout-independent checks run"
                         (let [result (status/unit-status {:dir dir})]
                           (is (= :unknown (:verdict result)))
                           (is (= [{:check :sha-not-compared}] (:reasons result)))
                           (is (not (contains? result :compared)))))

                       (testing "--root on a source unit is rejected (it has no sources to locate)"
                         (is (thrown? clojure.lang.ExceptionInfo
                                      (status/unit-status {:dir dir :root "/registry"}))))

                       (testing "an :updated past :max-age-days is age-exceeded"
                         (let [old-dir (write-manifest!
                                        (str (io/file root "old-unit"))
                                        (assoc (source-manifest) :updated "2020-01-01"))
                               result (status/unit-status {:dir old-dir})]
                           (is (= :stale (:verdict result)))
                           (is (= 2 (count (:reasons result))))
                           (is (= :age-exceeded (:check (second (:reasons result)))))
                           (is (= "2020-01-01" (:updated (second (:reasons result)))))))

                       (testing "a missing manifest throws"
                         (is (thrown? clojure.lang.ExceptionInfo
                                      (status/unit-status {:dir (str (io/file root "nope"))}))))))))

;; ===========================================================================
;; composed units
;; ===========================================================================

(defn- minimal-source-manifest
  [repo sha]
  {:repo repo
   :generated-by "status-test"
   :created today
   :updated today
   :source {:working-tree-notes ""
            :remote nil
            :sha sha
            :sha-short (subs sha 0 7)
            :branch nil
            :working-tree "clean"}
   :analysis-run {:method "status-test"}
   :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
   :history []})

(defn- write-sources!
  "Source manifests under `root`: `aaa` at `aaa-sha`,
  `_variants/bbb/ref=x` at `bbb-sha` (nil sha = absent unit)."
  [root aaa-sha bbb-sha]
  (when aaa-sha
    (write-manifest! (str (io/file root "aaa"))
                     (minimal-source-manifest "aaa" aaa-sha)))
  (when bbb-sha
    (write-manifest! (str (io/file root "_variants" "bbb" "ref=x"))
                     (assoc (minimal-source-manifest "bbb" bbb-sha)
                            :variant [[:ref "x"]]))))

(defn- composed-manifest
  []
  {:repo "composed-all"
   :generated-by "status-test"
   :created today
   :updated today
   :source {:working-tree-notes ""
            :remote nil
            :sha "c0mp0sed0000000"
            :sha-short "c0mp0se"
            :branch nil
            :working-tree "clean"}
   :analysis-run {:mode :compose
                  :units [{:repo "aaa" :sha "111aaaa" :created today}
                          {:repo "bbb" :variant [[:ref "x"]] :sha "222bbbb" :created today}]}
   :staleness {:policy "review-when-any-source-sha-drifts"
               :sources {"aaa" {:sha "111aaaa" :created today}
                         "bbb@ref=x" {:sha "222bbbb" :created today}}}
   :history []})

(deftest composed-unit-status-test
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (let [dir (write-manifest! (str (io/file root "composed-all"))
                                                (composed-manifest))]

                       (testing "every source current is current, with the root defaulted"
                         (let [result (status/unit-status {:dir dir})]
                           (is (= :current (:verdict result)))
                           (is (= :aggregate (:kind result)))
                           (is (= :compose (:mode result)))
                           (is (= [] (:reasons result)))
                           (is (= [{:source "aaa" :recorded "111aaaa" :current "111aaaa" :verdict :current}
                                   {:source "bbb@ref=x" :recorded "222bbbb" :current "222bbbb" :verdict :current}]
                                  (:sources result)))))

                       (testing "an explicit :root is honored"
                         (let [result (status/unit-status {:dir dir :root root})]
                           (is (= :current (:verdict result)))))

                       (testing "one drifted source makes the unit stale"
                         (write-sources! root "111aaaa" "9999999")
                         (let [result (status/unit-status {:dir dir})]
                           (is (= :stale (:verdict result)))
                           (is (= [{:check :source-sha-drift :source "bbb@ref=x"
                                    :recorded "222bbbb" :current "9999999"}]
                                  (:reasons result)))
                           (is (= :sha-drift (:verdict (second (:sources result)))))))

                       (testing "a removed source is missing"
                         (write-sources! root "111aaaa" nil)
                         (delete-tree (str (io/file root "_variants" "bbb")))
                         (let [result (status/unit-status {:dir dir})]
                           (is (= :stale (:verdict result)))
                           (is (= [{:check :source-missing :source "bbb@ref=x"}]
                                  (:reasons result)))
                           (is (= :missing (:verdict (second (:sources result)))))
                           (is (nil? (:current (second (:sources result)))))))

                       (testing "--checkout on a composed unit is rejected"
                         (is (thrown? clojure.lang.ExceptionInfo
                                      (status/unit-status {:dir dir :checkout "/co"}))))))))

(testing "a variant composed unit strips _variants/<repo>/<variant path> off the root"
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (let [dir (write-manifest! (str (io/file root "_variants" "composed-all" "ref=alt"))
                                                (assoc (composed-manifest) :variant [[:ref "alt"]]))]
                       (is (= :current (:verdict (status/unit-status {:dir dir}))))))))

(deftest aggregate-without-sources-test
  (with-temp-root* (fn [root]
                     (let [dir (write-manifest!
                                (str (io/file root "host-all"))
                                (assoc (composed-manifest)
                                       :repo "host-all"
                                       :analysis-run {:mode :host-whole}
                                       :staleness {:policy "host-policy"}))]
                       (testing "kind and provenance, no verdict"
                         (let [result (status/unit-status {:dir dir})]
                           (is (= :unknown (:verdict result)))
                           (is (= :aggregate (:kind result)))
                           (is (= :host-whole (:mode result)))
                           (is (= [{:check :aggregate-no-sources}] (:reasons result)))
                           (is (not (contains? result :sources)))))))))

;; ===========================================================================
;; remote normalization
;; ===========================================================================

(deftest normalize-remote-test
  (testing "scheme, user, .git suffix, trailing slash, and scp form collapse"
    (is (= "github.com/acme/x"
           (status/normalize-remote "https://github.com/acme/x.git")))
    (is (= "github.com/acme/x"
           (status/normalize-remote "https://github.com/acme/x.git/")))
    (is (= "github.com/acme/x"
           (status/normalize-remote "git@github.com:acme/x.git")))
    (is (= "github.com/acme/x"
           (status/normalize-remote "ssh://git@github.com/acme/x")))
    (is (= "github.com/acme/x"
           (status/normalize-remote "github.com/acme/x")))))
