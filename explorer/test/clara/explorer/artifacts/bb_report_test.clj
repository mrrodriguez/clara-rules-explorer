(ns clara.explorer.artifacts.bb-report-test
  "`bin/annotations_report.bb` against a real artifact directory this library
  just wrote.

  The script is the main offline reader of these artifacts and it runs under
  babashka, so it cannot `require` the namespaces that produce them. It used to
  cope by restating every filename, the layer fold order, and the whole
  `merged-annotations.edn` decode — four constants and an algorithm agreeing with
  the JVM by coincidence. `clara.explorer.artifacts.layout` is now the
  one definition both load, so those cannot drift.

  What that sharing does *not* cover is the part that stays the script's own: the
  path it walks to find the shared file, and whether its subcommands still read
  the parts they claim to. That is what these pin. `layers` is the interesting
  one — it is the subcommand that goes through the decode end to end, so its
  output is compared against
  `store/read-merged-annotations` rather than merely
  checked for a non-crash.

  Skipped, loudly, when `bb` is not installed: the script is developer tooling
  and its absence should not fail a build."
  (:require
   [clara.explorer.core :as core]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.explorer.artifacts.flow :as ann]
   [clara.explorer.artifacts.store :as store]
   [clara.explorer.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts* generated-annotations stub-rulebase]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

(def ^:private report-script
  "Resolved off the `server/` dir, which is the cwd the test runner runs in."
  (io/file "bin" "annotations_report.bb"))

(defn- runnable?
  "Both halves have to be there: `bb` on PATH, and the script where the JVM side
  expects it. A missing script is worth saying out loud — it means the script
  moved and the path this test hard-codes went with it."
  []
  (and (.isFile ^java.io.File report-script)
       (try (zero? (:exit (shell/sh "bb" "--version")))
            (catch java.io.IOException _ false))))

(def ^:private analysis
  "Enough of a `RulebaseAnalysis` for `consumers` and `edges` to have something
  to find in `production-index.edn` and `dep-graph.edn`, plus `:fact-types` for
  the hierarchy closure `producers` and `consumers` now resolve against."
  {:rules {"a.ns/full-rule" {:name "a.ns/full-rule" :ns "a.ns"
                             :lhs [{:type "a/one" :constraints "[]"}]
                             :lhs-types [":a/one"] :insert-types [":a/two"]
                             :rhs-form "(insert! x)\n"}
           "a.ns/gap-rule" {:name "a.ns/gap-rule" :ns "a.ns"
                            :lhs-types [":a/two"] :insert-types []}}
   :fact-types {":a/one" {:name ":a/one" :ns nil :ancestors []}
                ":a/two" {:name ":a/two" :ns nil :ancestors []}}
   :dep-graph {"a.ns/full-rule" {:upstream #{}}
               "a.ns/gap-rule" {:upstream #{"a.ns/full-rule"}}}
   :unresolved []})

(defn- run-report
  "The script's stdout for one subcommand, over the test's own artifact dir."
  [& args]
  (let [{:keys [exit out err]}
        (apply shell/sh "bb" (str report-script) (:dir *artifact-opts*) args)]
    (is (zero? exit) (str "annotations_report.bb exited " exit ": " err))
    out))

(defn- write-artifacts! []
  (with-redefs [core/->rulebase-analysis (fn [_ _ _] analysis)]
    (ann/persist! {:layer (store/->generated-layer *artifact-opts* generated-annotations)}
                  (assoc *artifact-opts* :session stub-rulebase))))

(deftest bb-report-reads-what-this-repo-writes-test
  (if-not (runnable?)
    (println "SKIPPING bb-report-test — babashka is not on PATH, or the script moved:"
             (str report-script))
    (do
      (write-artifacts!)

      (testing "`consumers` reads production-index.edn — the scan column file —
                and finds the rule whose :lhs-types hold the type"
        (let [out (run-report "consumers" ":a/one")]
          (is (str/includes? out "a.ns/full-rule"))
          (is (not (str/includes? out "a.ns/gap-rule")))))

      (testing "`producers` reads annotations + fact-types.edn and closes over
                the hierarchy (here: only exact matches, no descendants)"
        (let [out (run-report "producers" ":a/one")]
          (is (str/includes? out "a.ns/full-rule"))
          (is (not (str/includes? out "a.ns/gap-rule")))))

      (testing "`producers` prints what a unique substring type resolved to"
        (let [out (run-report "producers" "one")]
          (is (str/includes? out "Resolved one -> :a/one"))
          (is (str/includes? out "a.ns/full-rule"))))

      (testing "`edges` reads dep-graph.edn and inverts :upstream for the
                :downstream side, which is no longer on disk"
        (let [out (run-report "edges" "a.ns/full-rule")]
          (is (str/includes? out "downstream ( 1 )"))
          (is (str/includes? out "a.ns/gap-rule"))))

      (testing "`edges` prints the fully-qualified name a unique substring
                resolves to, and lists options when ambiguous"
        (let [out (run-report "edges" "full-rule")]
          (is (str/includes? out "Resolved full-rule -> a.ns/full-rule"))
          (is (str/includes? out "a.ns/gap-rule")))
        (let [out (run-report "edges" "rule")]
          (is (str/includes? out "Ambiguous — 2 matches:"))
          (is (str/includes? out "a.ns/full-rule"))
          (is (str/includes? out "a.ns/gap-rule"))))

      (testing "`layers` goes through the whole merged-annotations decode, so its
                per-key origin tally must match what the JVM reader rebuilds from
                the same files"
        (let [merged (store/read-merged-annotations *artifact-opts*)
              expected (frequencies (mapcat vals (vals (:provenance merged))))
              out (run-report "layers")]
          (is (str/includes? out (str "origins across " (count (:provenance merged)) " rules")))
          (doseq [[origin n] expected]
            (is (str/includes? out (format "%5d  %s" n (pr-str origin)))
                (str "origin tally disagrees for " (pr-str origin))))))

      (testing "`rule` resolves a reference into the layer, so a by-reference
                rule still prints its callsite evidence"
        (let [out (run-report "rule" "a.ns/full-rule")]
          (is (str/includes? out "(->fact :a/one x)"))))

      (testing "`summary` counts the whole expanded merge, not just the rules
                stored inline — all four are there, three of them by reference"
        (is (str/includes? (run-report "summary") "rules: 4")))

      (testing "`help` lists the subcommands and their signatures"
        (let [out (run-report "help")]
          (is (str/includes? out "usage: bb annotations_report.bb"))
          (doseq [sub ["summary" "gaps" "types" "producers" "consumers"
                       "hierarchy" "rule" "edges" "curated" "layers" "status" "help"]]
            (is (str/includes? out sub)))
          (is (str/includes? out "<type>"))
          (is (str/includes? out "<fq-name>"))
          (is (str/includes? out "--file auto|agent|merged"))
          (is (str/includes? out "status --checkout PATH"))))

      (testing "`producers` groups a multi-type match into per-type blocks"
        (let [out (run-report "producers" "a/")]
          (is (str/includes? out "Resolved a/ to 2 fact types:"))
          (is (str/includes? out ":a/one"))
          (is (str/includes? out ":a/two"))
          (is (str/includes? out "a.ns/full-rule"))
          (is (str/includes? out "(no matches)"))))

      (testing "`hierarchy` blank-lines between the resolved list and the first type"
        (let [out (run-report "hierarchy" "a/")]
          (is (str/includes? out "  :a/two\n\n:a/one"))))

      (testing "`gaps` reads the generated layer directly, so it never touches
                the merge or the analysis"
        (is (str/includes? (run-report "gaps") "a.ns/gap-rule"))))))

(def ^:private app-approved
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?")

(def ^:private notice-approved
  "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app")

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(deftest bb-report-reads-a-composed-unit-test
  (if-not (runnable?)
    (println "SKIPPING bb-report-composed-test — babashka is not on PATH, or the script moved:"
             (str report-script))
    (let [opts (assoc *artifact-opts*
                      :root (registry-root)
                      :repo "composed/demo"
                      :units [{:repo "loan-app-ruleset"}
                              {:repo "loan-disposition-ruleset"}])
          _ (ann/compose-persist! opts)
          run (fn [& args]
                (let [{:keys [exit out err]}
                      (apply shell/sh "bb" (str report-script) (:dir *artifact-opts*) args)]
                  (is (zero? exit) (str "annotations_report.bb exited " exit ": " err))
                  out))]
      (testing "summary expands the composed merge"
        (is (str/includes? (run "summary") "rules:")))

      (testing "consumers reads the composed production-index"
        (let [out (run "consumers" ":loan-app/application-outcome")]
          (is (str/includes? out notice-approved))))

      (testing "edges reads the composed dep-graph across units"
        (let [out (run "edges" notice-approved)]
          (is (str/includes? out app-approved))))

      (testing "layers reports the flattened standard layers"
        (let [out (run "layers")]
          (is (str/includes? out ":clara.explorer.analyze/generated"))
          (is (str/includes? out ":memory"))))

      (testing "producers closes over descendants and labels them via-descendant"
        (let [out (run "producers" ":loan-app/application-outcome")]
          (is (str/includes? out "3 producer rule(s)"))
          (is (str/includes? out "(0 exact, 3 via descendants)"))
          (is (str/includes? out "via descendant clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"))
          (doseq [p [app-approved
                     "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?"
                     "clara.explorer.test.rules.loan-app-rules/app-outcome-pending?"]]
            (is (str/includes? out p)))))

      (testing "a multi-type match groups via rules under the type they descend from"
        (let [out (run "producers" "loan")]
          (is (str/includes?
               out
               (str ":loan-app/application-outcome\n"
                    "  via descendant clara.explorer.test.rules.loan_app_rules.ApplicationOutcome")))
          (is (str/includes?
               out
               (str "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome\n"
                    "  exact")))))

      (testing "consumers closes over ancestors, labels via-ancestor, and prints :unit"
        (let [out (run "consumers" "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome")]
          (is (str/includes? out "5 consumer rule(s)"))
          (is (str/includes? out "(3 exact, 2 via ancestors)"))
          (is (str/includes? out "via ancestor :loan-app/application-outcome"))
          (doseq [p [notice-approved
                     "clara.explorer.test.rules.loan-outcome-notices/notice-denied-app"]]
            (is (str/includes? out p)))
          (is (str/includes? out "unit: loan-disposition-ruleset"))))

      (testing "hierarchy shows a type's ancestors and descendants"
        (let [out (run "hierarchy" ":loan-app/application-outcome")]
          (is (str/includes? out ":loan-app/application-outcome"))
          (is (str/includes? out "ancestors (0)"))
          (is (str/includes? out "descendants (1)"))
          (is (str/includes? out "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"))))

      (testing "producers agrees with edges on the cross-type link — the regression guard"
        (let [index (store/read-analysis-part :index opts)
              dep-graph (store/read-analysis-part :dep-graph opts)]
          (doseq [notice [notice-approved
                          "clara.explorer.test.rules.loan-outcome-notices/notice-denied-app"]
                  :let [lhs-type (first (:lhs-types (get-in index [:rules notice])))
                        upstream (get-in dep-graph [notice :upstream])
                        out (run "producers" lhs-type)]]
            (is (= ":loan-app/application-outcome" lhs-type))
            (doseq [u upstream]
              (is (str/includes? out u)
                  (str "producers " lhs-type " omits upstream rule " u))))))

      (testing "single source unit: the closure adds rather than rewrites"
        (let [dir (str (io/file (registry-root) "loan-app-ruleset"))
              {:keys [exit out err]}
              (shell/sh "bb" (str report-script) dir
                        "producers" ":loan-app/application-outcome")]
          (is (zero? exit) (str "annotations_report.bb exited " exit ": " err))
          (is (str/includes? out "3 producer rule(s)"))
          (is (str/includes? out "(0 exact, 3 via descendants)")))))))

(def ^:private status-today
  (str (java.time.LocalDate/now)))

(defn- write-status-manifest!
  "Hand-written unit manifest in `dir` — hermetic: no git, no analysis."
  [dir manifest]
  (let [f (io/file dir "rules-inspect-manifest.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str manifest))
    (str dir)))

(defn- status-source-manifest
  []
  {:repo "demo-ruleset"
   :generated-by "bb-report-test"
   :created status-today
   :updated status-today
   :source {:working-tree-notes ""
            :remote "https://example.com/acme/demo-ruleset.git"
            :sha "ac51808deadbeef"
            :sha-short "ac51808"
            :branch "feature-x"
            :working-tree "clean"}
   :analysis-run {:method "bb-report-test"}
   :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
   :history []})

(defn- status-composed-manifest
  []
  {:repo "demo-composed"
   :generated-by "bb-report-test"
   :created status-today
   :updated status-today
   :source {:working-tree-notes ""
            :remote nil
            :sha "c0mp0sed0000000"
            :sha-short "c0mp0se"
            :branch nil
            :working-tree "clean"}
   :analysis-run {:mode :compose
                  :units [{:repo "demo-a" :sha "111aaaa" :created status-today}
                          {:repo "demo-b" :branch "x" :sha "222bbbb" :created status-today}]}
   :staleness {:policy "review-when-any-source-sha-drifts"
               :sources {"demo-a" {:sha "111aaaa" :created status-today}
                         "demo-b@x" {:sha "222bbbb" :created status-today}}}
   :history []})

(defn- run-status
  "The script's stdout for `status` over `dir`, asserting success."
  [dir & args]
  (let [{:keys [exit out err]} (apply shell/sh "bb" (str report-script) dir "status" args)]
    (is (zero? exit) (str "annotations_report.bb exited " exit ": " err))
    out))

(deftest bb-report-status-test
  (if-not (runnable?)
    (println "SKIPPING bb-report-status-test — babashka is not on PATH, or the script moved:"
             (str report-script))
    (let [root (str (io/file (:dir *artifact-opts*) "status-root"))
          unit (write-status-manifest! (str (io/file root "demo-ruleset"))
                                       (status-source-manifest))]
      (testing "text report: kind, source provenance, and a verdict"
        (let [out (run-status unit)]
          (is (str/includes? out "unit       demo-ruleset"))
          (is (str/includes? out "kind       source unit"))
          (is (str/includes? out "ac51808 (feature-x, clean)"))
          (is (str/includes? out "review-when-sha-drifts"))
          (is (str/includes? out "verdict    unknown | sha not compared"))))

      (testing "--edn prints the result map"
        (let [result (edn/read-string (run-status unit "--edn"))]
          (is (= :unknown (:verdict result)))
          (is (= :source (:kind result)))
          (is (= [{:check :sha-not-compared}] (:reasons result)))
          (is (not (contains? result :compared)))))

      (testing "--checkout against a foreign remote is remote-mismatch"
        ;; The runner's own checkout is a real repo whose origin cannot be
        ;; example.com, whatever its branch or tree state — hermetic verdict.
        (let [out (run-status unit "--checkout" (System/getProperty "user.dir"))]
          (is (str/includes? out "verdict    stale | remote-mismatch"))))

      (testing "a composed unit is current, then stale, then missing"
        (let [root (str (io/file (:dir *artifact-opts*) "status-composed"))
              write-src (fn [a b]
                          (when a
                            (write-status-manifest!
                             (str (io/file root "demo-a"))
                             (assoc (status-source-manifest) :repo "demo-a"
                                    :source {:working-tree-notes "" :remote nil
                                             :sha a :sha-short (subs a 0 7)
                                             :branch nil :working-tree "clean"})))
                          (when b
                            (write-status-manifest!
                             (str (io/file root "demo-b" "branches" "x"))
                             (assoc (status-source-manifest) :repo "demo-b" :branch "x"
                                    :source {:working-tree-notes "" :remote nil
                                             :sha b :sha-short (subs b 0 7)
                                             :branch nil :working-tree "clean"}))))
              _ (write-src "111aaaa" "222bbbb")
              dir (write-status-manifest! (str (io/file root "demo-composed"))
                                          (status-composed-manifest))]
          (let [out (run-status dir)]
            (is (str/includes? out "kind       aggregate unit (mode :compose)"))
            (is (str/includes? out "demo-a: current"))
            (is (str/includes? out "demo-b@x: current"))
            (is (str/includes? out "verdict    current")))
          (write-src "111aaaa" "9999999")
          (let [out (run-status dir)]
            (is (str/includes? out "demo-b@x: sha-drift 222bbbb -> 9999999"))
            (is (str/includes? out "verdict    stale")))
          (write-src "111aaaa" nil)
          (let [dir-file (io/file root "demo-b")]
            (doseq [f (reverse (file-seq dir-file))] (io/delete-file f true)))
          (let [out (run-status dir)]
            (is (str/includes? out "demo-b@x: missing"))
            (is (str/includes? out "verdict    stale"))))

        (testing "--checkout on a composed unit fails"
          (let [dir (write-status-manifest! (str (io/file root "demo-composed"))
                                            (status-composed-manifest))
                {:keys [exit out err]} (shell/sh "bb" (str report-script) dir "status"
                                                 "--checkout" (System/getProperty "user.dir"))]
            (is (not (zero? exit)))
            (is (str/includes? (str out err) "one checkout per source"))))))))
