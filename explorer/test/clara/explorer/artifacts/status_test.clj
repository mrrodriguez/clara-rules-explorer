(ns clara.explorer.artifacts.status-test
  " `unit-status` over temp unit dirs holding hand-written manifests, plus
  stubbed checkout git reads.

  Manifest IO is real (written with `pr-str`, read back through the same path
  the bb `status` subcommand uses); only the *checkout* side is stubbed, since a status
  comparison needs commits the test would otherwise have to manufacture."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clara.explorer.artifacts.git :as git]
   [clara.explorer.artifacts.status :as status]
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
  `remote`, the tree is clean unless `clean?` is false, and the checkout is on
  `branch` (nil = detached). The branch defaults to `feature-x`, the branch the
  fixtures' source units record, so the ordinary case is an attached checkout on
  the unit's own branch and a detached one is what a test asks for. Each of
  `ref-shas`, `remote`, `clean?`, and `branch` may instead be a function of the
  checkout dir, so one test can stub several checkouts at once."
  [{:keys [ref-shas remote clean? branch] :or {clean? true branch "feature-x"}} & body]
  `(let [per-dir# (fn [v# dir#] (if (fn? v#) (v# dir#) v#))]
     (with-redefs [git/ref-sha (fn [dir# ref#] (get (per-dir# ~ref-shas dir#) ref#))
                   git/get-attached-branch (fn [dir#] (per-dir# ~branch dir#))
                   git/remote-url (fn [dir#] (per-dir# ~remote dir#))
                   git/clean-tree? (fn [dir#] (per-dir# ~clean? dir#))]
       ~@body)))

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

                       (testing "a checkout on another branch says nothing about the unit"
                         (with-checkout {:ref-shas {"HEAD" "9b1e0f2aaaa" "main" "9b1e0f2aaaa"}
                                         :remote recorded-remote
                                         :branch "main"}
                           (let [result (status/unit-status {:dir dir :checkout "/co"})]
                             (is (= :unknown (:verdict result)))
                             (is (= [{:check :checkout-off-ref
                                      :recorded "feature-x"
                                      :current "main"}]
                                    (:reasons result))))

                           (testing "an explicit ref names the commit to compare"
                             (let [result (status/unit-status {:dir dir :checkout "/co" :ref "main"})]
                               (is (= :stale (:verdict result)))
                               (is (= [{:check :sha-drift
                                        :recorded recorded-sha
                                        :current "9b1e0f2aaaa"}]
                                      (:reasons result)))))))

                       (testing "a checkout on the unit's own branch compares shas"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote
                                         :branch "feature-x"}
                           (is (= :current (:verdict (status/unit-status {:dir dir :checkout "/co"}))))))

                       (testing "a detached checkout has no branch to compare, so the sha decides"
                         (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote
                                         :branch nil}
                           (is (= :current (:verdict (status/unit-status {:dir dir :checkout "/co"}))))))

                       (testing "a unit whose commit is on no branch compares the sha too"
                         (let [no-branch-dir (write-manifest!
                                              (str (io/file root "detached-unit"))
                                              (assoc-in (source-manifest) [:source :branch] nil))]
                           (with-checkout {:ref-shas {"HEAD" recorded-sha} :remote recorded-remote
                                           :branch "main"}
                             (is (= :current (:verdict (status/unit-status {:dir no-branch-dir :checkout "/co"})))))))

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
  "A source manifest for `repo` at `sha`, analyzed on `branch` (nil when its
  commit belongs to no branch)."
  ([repo sha] (minimal-source-manifest repo sha nil))
  ([repo sha branch]
   {:repo repo
    :generated-by "status-test"
    :created today
    :updated today
    :source {:working-tree-notes ""
             :remote nil
             :sha sha
             :sha-short (subs sha 0 7)
             :branch branch
             :working-tree "clean"}
    :analysis-run {:method "status-test"}
    :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
    :history []}))

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

(defn- with-composed-unit*
  "Run `(f {:root :dir :checkouts})` with a fresh root holding
  `composed-manifest`'s two source units at the shas it records, plus the
  composed unit itself. Fresh per case, so a case that rewrites or deletes a
  source cannot change what a later case sees."
  [f]
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (f {:root root
                         :dir (write-manifest! (str (io/file root "composed-all"))
                                               (composed-manifest))
                         :checkouts {"aaa" "/co-aaa" "bbb" "/co-bbb"}}))))

(deftest composed-unit-status-test
  (testing "the composition's record holds, but with no checkout the unit's own
            status is unknown"
    (with-composed-unit*
      (fn [{:keys [dir]}]
        (let [result (status/unit-status {:dir dir})]
          (is (= :unknown (:verdict result)))
          (is (= :aggregate (:kind result)))
          (is (= :compose (:mode result)))
          (is (= [{:check :unit-unknown :unit-key "aaa"
                   :reasons [{:check :sha-not-compared}]}
                  {:check :unit-unknown :unit-key "bbb@ref=x"
                   :reasons [{:check :sha-not-compared}]}]
                 (:reasons result)))
          (is (= ["aaa" "bbb@ref=x"] (mapv :unit-key (:sources result))))
          (is (= [{:recorded "111aaaa" :unit-sha "111aaaa" :verdict :current}
                  {:recorded "222bbbb" :unit-sha "222bbbb" :verdict :current}]
                 (mapv :composition (:sources result))))
          (is (= [:unknown :unknown] (mapv :verdict (:sources result))))
          (is (every? #(= #{:unit-key :composition :unit-status :verdict} (set (keys %)))
                      (:sources result))
              "a source entry carries the composition's record and the unit's report,
              and nothing else — no stray :reasons")
          (is (= :source (get-in result [:sources 0 :unit-status :kind])))))))

  (testing "an explicit :root is honored"
    (with-composed-unit*
      (fn [{:keys [root dir]}]
        (let [result (status/unit-status {:dir dir :root root})]
          (is (= :unknown (:verdict result)))
          (is (= "111aaaa" (get-in result [:sources 0 :composition :unit-sha])))))))

  (testing "the composition's recorded sha drifted: composition stale, reasons
            keyed by unit-key"
    (with-composed-unit*
      (fn [{:keys [root dir]}]
        (write-sources! root "111aaaa" "9999999")
        (let [result (status/unit-status {:dir dir})]
          (is (= :stale (:verdict result)))
          (is (= [{:check :unit-unknown :unit-key "aaa"
                   :reasons [{:check :sha-not-compared}]}
                  {:check :source-sha-drift :unit-key "bbb@ref=x"
                   :recorded "222bbbb" :current "9999999"}
                  {:check :unit-unknown :unit-key "bbb@ref=x"
                   :reasons [{:check :sha-not-compared}]}]
                 (:reasons result)))
          (is (= [:unknown :stale] (mapv :verdict (:sources result))))
          (is (= {:recorded "222bbbb" :unit-sha "9999999" :verdict :stale}
                 (get-in result [:sources 1 :composition])))))))

  (testing "a removed source is missing, with no unit report of its own"
    (with-composed-unit*
      (fn [{:keys [root dir]}]
        (delete-tree (str (io/file root "_variants" "bbb")))
        (let [result (status/unit-status {:dir dir})]
          (is (= :stale (:verdict result)))
          (is (= [{:check :unit-unknown :unit-key "aaa"
                   :reasons [{:check :sha-not-compared}]}
                  {:check :source-missing :unit-key "bbb@ref=x"}]
                 (:reasons result)))
          (is (= {:recorded "222bbbb" :unit-sha nil :verdict :stale}
                 (get-in result [:sources 1 :composition])))
          (is (= :stale (get-in result [:sources 1 :verdict])))
          (is (not (contains? (get-in result [:sources 1]) :unit-status)))))))

  (testing "--checkout on a composed unit is rejected"
    (with-composed-unit*
      (fn [{:keys [dir]}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (status/unit-status {:dir dir :checkout "/co"})))))))

(deftest variant-composed-unit-status-test
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (let [dir (write-manifest! (str (io/file root "_variants" "composed-all" "ref=alt"))
                                                (assoc (composed-manifest) :variant [[:ref "alt"]]))
                           result (status/unit-status {:dir dir})]
                       (testing "the root is stripped through the variant path too"
                         (is (= :unknown (:verdict result)))
                         (is (= "222bbbb" (get-in result [:sources 1 :composition :unit-sha]))))))))

(defn- ->checkout-ref-shas
  "`git/ref-sha` stub for two checkouts: `/co-aaa` at `aaa` and `/co-bbb` at
  `bbb`, so each source of `composed-manifest` is compared against its own
  repo's checkout."
  [aaa bbb]
  (fn [dir]
    (get {"/co-aaa" {"HEAD" aaa}
          "/co-bbb" {"HEAD" bbb}}
         dir)))

(deftest composed-source-status-test
  (testing "both comparisons hold for every source: current"
    (with-composed-unit*
      (fn [{:keys [dir checkouts]}]
        (with-checkout {:ref-shas (->checkout-ref-shas "111aaaa" "222bbbb")}
          (let [result (status/unit-status {:dir dir :source-checkouts checkouts})]
            (is (= :current (:verdict result)))
            (is (= [] (:reasons result)))
            (is (= [:current :current] (mapv :verdict (:sources result))))
            (is (= {:checkout "/co-aaa" :ref "HEAD" :sha "111aaaa"}
                   (get-in result [:sources 0 :unit-status :compared]))))))))

  (testing "the composition's record holds, the unit's own status is stale: the
            unit is behind its checkout"
    (with-composed-unit*
      (fn [{:keys [dir checkouts]}]
        (with-checkout {:ref-shas (->checkout-ref-shas "999aaa" "222bbbb")}
          (let [result (status/unit-status {:dir dir :source-checkouts checkouts})]
            (is (= :stale (:verdict result)))
            (is (= [{:check :unit-stale :unit-key "aaa"
                     :reasons [{:check :sha-drift
                                :recorded "111aaaa" :current "999aaa"}]}]
                   (:reasons result)))
            (is (= [:stale :current] (mapv :verdict (:sources result))))
            (is (= {:recorded "111aaaa" :unit-sha "111aaaa" :verdict :current}
                   (get-in result [:sources 0 :composition]))))))))

  (testing "the composition's recorded sha drifted, the unit's own status holds:
            the composition is stale"
    (with-composed-unit*
      (fn [{:keys [root dir checkouts]}]
        (write-sources! root "111aaaa" "9999999")
        (with-checkout {:ref-shas (->checkout-ref-shas "111aaaa" "9999999")}
          (let [result (status/unit-status {:dir dir :source-checkouts checkouts})]
            (is (= :stale (:verdict result)))
            (is (= [{:check :source-sha-drift :unit-key "bbb@ref=x"
                     :recorded "222bbbb" :current "9999999"}]
                   (:reasons result)))
            (is (= [:current :stale] (mapv :verdict (:sources result)))))))))

  (testing "both comparisons broken for one source: the composition and the unit
            each report"
    (with-composed-unit*
      (fn [{:keys [root dir checkouts]}]
        (write-sources! root "111aaaa" "9999999")
        (with-checkout {:ref-shas (->checkout-ref-shas "111aaaa" "222bbbb")}
          (let [result (status/unit-status {:dir dir :source-checkouts checkouts})]
            (is (= :stale (:verdict result)))
            (is (= [{:check :source-sha-drift :unit-key "bbb@ref=x"
                     :recorded "222bbbb" :current "9999999"}
                    {:check :unit-stale :unit-key "bbb@ref=x"
                     :reasons [{:check :sha-drift
                                :recorded "9999999" :current "222bbbb"}]}]
                   (:reasons result)))
            (is (= {:recorded "222bbbb" :unit-sha "9999999" :verdict :stale}
                   (get-in result [:sources 1 :composition])))
            (is (= :stale (get-in result [:sources 1 :verdict]))))))))

  (testing "a source only some repos have a checkout for reads unknown"
    (with-composed-unit*
      (fn [{:keys [dir]}]
        (with-checkout {:ref-shas (->checkout-ref-shas "111aaaa" "222bbbb")}
          (let [result (status/unit-status {:dir dir :source-checkouts {"aaa" "/co-aaa"}})]
            (is (= :unknown (:verdict result)))
            (is (= [:current :unknown] (mapv :verdict (:sources result))))
            (is (= [{:check :unit-unknown :unit-key "bbb@ref=x"
                     :reasons [{:check :sha-not-compared}]}]
                   (:reasons result))))))))

  (testing "a source checkout on another branch is unknown, not sha-drift"
    (with-composed-unit*
      (fn [{:keys [root dir]}]
        (write-manifest! (str (io/file root "aaa"))
                         (minimal-source-manifest "aaa" "111aaaa" "main"))
        (with-checkout {:ref-shas (fn [dir] (get {"/co-aaa" {"HEAD" "999aaa"}} dir))
                        :branch "feature-x"}
          (let [result (status/unit-status {:dir dir :source-checkouts {"aaa" "/co-aaa"}})]
            (is (= :unknown (get-in result [:sources 0 :verdict])))
            (is (= [{:check :unit-unknown :unit-key "aaa"
                     :reasons [{:check :checkout-off-ref
                                :recorded "main" :current "feature-x"}]}]
                   (filterv #(= "aaa" (:unit-key %)) (:reasons result))))
            (is (= :unknown (get-in result [:sources 0 :unit-status :verdict]))))))))

  (testing ":source-status-fn decides a source whose sha still matches"
    (with-composed-unit*
      (fn [{:keys [dir]}]
        (let [report (fn [{:keys [unit-key]}]
                       {:verdict (if (= unit-key "aaa") :stale :current)
                        :reasons (if (= unit-key "aaa")
                                   [{:check :host-drift}]
                                   [])})
              result (status/unit-status {:dir dir :source-status-fn report})]
          (is (= :stale (:verdict result)))
          (is (= [{:check :unit-stale :unit-key "aaa"
                   :reasons [{:check :host-drift}]}]
                 (:reasons result)))
          (is (= [:stale :current] (mapv :verdict (:sources result))))))))

  (testing "a report function that returns nothing is unknown, not current"
    (doseq [[label report] [["nil" (constantly nil)]
                            ["no :verdict" (constantly {:reasons []})]]]
      (with-composed-unit*
        (fn [{:keys [dir]}]
          (let [result (status/unit-status {:dir dir :source-status-fn report})]
            (is (= :unknown (:verdict result)) label)
            (is (= [{:check :unit-unknown :unit-key "aaa"
                     :reasons [{:check :unit-report-missing :unit-key "aaa"}]}
                    {:check :unit-unknown :unit-key "bbb@ref=x"
                     :reasons [{:check :unit-report-missing :unit-key "bbb@ref=x"}]}]
                   (:reasons result))
                label)
            (is (every? #(not (contains? % :unit-status)) (:sources result)) label))))))

  (testing ":source-status-fn is not called for a missing source"
    (with-composed-unit*
      (fn [{:keys [root dir]}]
        (delete-tree (str (io/file root "_variants" "bbb")))
        (let [calls (atom [])
              report (fn [{:keys [unit-key]}]
                       (swap! calls conj unit-key)
                       {:verdict :current :reasons []})
              result (status/unit-status {:dir dir :source-status-fn report})]
          (is (= ["aaa"] @calls))
          (is (= [{:check :source-missing :unit-key "bbb@ref=x"}]
                 (:reasons result)))
          (is (not (contains? (get-in result [:sources 1]) :unit-status)))))))

  (testing ":source-checkouts and :source-status-fn together throw"
    (with-composed-unit*
      (fn [{:keys [dir checkouts]}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (status/unit-status {:dir dir
                                          :source-checkouts checkouts
                                          :source-status-fn (constantly nil)}))))))

  (testing ":source-checkouts and :source-status-fn on a source unit are rejected"
    (with-composed-unit*
      (fn [{:keys [root checkouts]}]
        (doseq [args [{:source-checkouts checkouts}
                      {:source-status-fn (constantly nil)}]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (status/unit-status (assoc args :dir (str (io/file root "aaa")))))))))))

(deftest composition-as-a-source-test
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (let [inner-sha "1nner0000000"
                           _ (write-manifest! (str (io/file root "_compose" "inner"))
                                              (-> (composed-manifest)
                                                  (assoc :repo "_compose/inner")
                                                  (assoc-in [:source :sha] inner-sha)))
                           outer-dir (write-manifest!
                                      (str (io/file root "_compose" "outer"))
                                      (-> (composed-manifest)
                                          (assoc :repo "_compose/outer")
                                          (assoc :analysis-run
                                                 {:mode :compose
                                                  :units [{:repo "_compose/inner"
                                                           :sha inner-sha
                                                           :created today}]})
                                          (assoc :staleness
                                                 {:policy "review-when-any-source-sha-drifts"
                                                  :sources {"_compose/inner"
                                                            {:sha inner-sha :created today}}})))
                           result (status/unit-status {:dir outer-dir})
                           entry (first (:sources result))]
                       (is (= "_compose/inner" (:unit-key entry)))
                       (is (= {:recorded inner-sha :unit-sha inner-sha :verdict :current}
                              (:composition entry)))
                       (testing "its report nests whole, sources and all"
                         (is (= :aggregate (get-in entry [:unit-status :kind])))
                         (is (= :compose (get-in entry [:unit-status :mode])))
                         (is (= :unknown (get-in entry [:unit-status :verdict])))
                         (is (= ["aaa" "bbb@ref=x"]
                                (mapv :unit-key (get-in entry [:unit-status :sources])))))
                       (is (= :unknown (:verdict entry)))
                       (is (= [{:check :unit-unknown :unit-key "_compose/inner"
                                :reasons [{:check :unit-unknown :unit-key "aaa"
                                           :reasons [{:check :sha-not-compared}]}
                                          {:check :unit-unknown :unit-key "bbb@ref=x"
                                           :reasons [{:check :sha-not-compared}]}]}]
                              (:reasons result)))
                       (testing "the same checkouts reach a composition's own sources"
                         (with-checkout {:ref-shas (->checkout-ref-shas "111aaaa" "222bbbb")}
                           (let [checked (status/unit-status
                                          {:dir outer-dir
                                           :source-checkouts {"aaa" "/co-aaa"
                                                              "bbb" "/co-bbb"}})]
                             (is (= :current (:verdict checked)))
                             (is (= :current (get-in checked [:sources 0 :unit-status :verdict])))
                             (is (= [:current :current]
                                    (mapv :verdict
                                          (get-in checked [:sources 0 :unit-status :sources])))))))))))

(deftest composed-unit-cycle-test
  (with-temp-root* (fn [root]
                     (write-sources! root "111aaaa" "222bbbb")
                     (let [a-sha "a0a0a0a0"
                           b-sha "b0b0b0b0"
                           compose-of (fn [repo sha other-repo other-sha]
                                        (-> (composed-manifest)
                                            (assoc :repo repo)
                                            (assoc-in [:source :sha] sha)
                                            (assoc :analysis-run
                                                   {:mode :compose
                                                    :units [{:repo other-repo :sha other-sha}]})
                                            (assoc :staleness
                                                   {:policy "review-when-any-source-sha-drifts"
                                                    :sources {other-repo {:sha other-sha}}})))
                           a-dir (write-manifest! (str (io/file root "_compose" "a"))
                                                  (compose-of "_compose/a" a-sha "_compose/b" b-sha))]
                       (write-manifest! (str (io/file root "_compose" "b"))
                                        (compose-of "_compose/b" b-sha "_compose/a" a-sha))

                       (testing "a composition that reaches itself is reported, not recursed into"
                         (let [result (status/unit-status {:dir a-dir})]
                           (is (= :unknown (:verdict result)))
                           (is (= :current (get-in result [:sources 0 :composition :verdict])))
                           (is (= [{:check :unit-unknown :unit-key "_compose/b"
                                    :reasons [{:check :unit-unknown :unit-key "_compose/a"
                                               :reasons [{:check :source-cycle
                                                          :unit-key "_compose/a"}]}]}]
                                  (:reasons result)))))))))

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
