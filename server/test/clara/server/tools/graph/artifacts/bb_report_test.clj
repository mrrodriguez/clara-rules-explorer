(ns clara.server.tools.graph.artifacts.bb-report-test
  "`bin/annotations_report.bb` against a real artifact directory this library
  just wrote.

  The script is the main offline reader of these artifacts and it runs under
  babashka, so it cannot `require` the namespaces that produce them. It used to
  cope by restating every filename, the layer fold order, and the whole
  `merged-annotations.edn` decode — four constants and an algorithm agreeing with
  the JVM by coincidence. `clara.server.tools.graph.artifacts.layout` is now the
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
   [clara.server.tools.graph.core :as core]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.flow :as ann]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.artifacts.test-fixtures :as fixtures
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
  to find in `production-index.edn` and `dep-graph.edn`."
  {:rules {"a.ns/full-rule" {:name "a.ns/full-rule" :ns "a.ns"
                             :lhs [{:type "a/one" :constraints "[]"}]
                             :lhs-types ["a/one"] :insert-types ["a/two"]
                             :rhs-form "(insert! x)\n"}
           "a.ns/gap-rule" {:name "a.ns/gap-rule" :ns "a.ns"
                            :lhs-types ["a/two"] :insert-types []}}
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

      (testing "`edges` reads dep-graph.edn and inverts :upstream for the
                :downstream side, which is no longer on disk"
        (let [out (run-report "edges" "a.ns/full-rule")]
          (is (str/includes? out "downstream ( 1 )"))
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

      (testing "`gaps` reads the generated layer directly, so it never touches
                the merge or the analysis"
        (is (str/includes? (run-report "gaps") "a.ns/gap-rule"))))))
