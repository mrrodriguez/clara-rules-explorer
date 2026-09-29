(ns clara.explorer.artifacts.editor-client-bb-test
  "`bin/editor_client.bb` against the checked-in `rules-annos/` example registry.

  The script is the babashka twin of `clara.explorer.server.client/navigate` over a registry
  selection. It composes the selected units on the fly (`shared.selection` → `shared.compose` →
  `shared.rehydrate`) and answers `shared.navigate` — the same pure namespaces the JVM client
  delegates to — so the two runtimes cannot drift on the navigation body. What that sharing does
  not cover is the bb-side IO and composition wiring: whether the script reads the right parts,
  composes the same analysis the JVM `compose/->composed-analysis` builds, and reaches the same
  producer/consumer closures the `annotations_report.bb` `producers`/`consumers` subcommands
  report. That is what these pin.

  Skipped, loudly, when `bb` is not installed: the script is developer tooling and its absence
  should not fail a build."
  (:require
   [clara.explorer.artifacts.compose :as compose]
   [clara.explorer.artifacts.registry :as registry]
   [clara.explorer.artifacts.rehydrate :as rehydrate]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def ^:private editor-client-script
  "Resolved off the `server/` dir, which is the cwd the test runner runs in."
  (io/file "bin" "editor_client.bb"))

(def ^:private report-script
  (io/file "bin" "annotations_report.bb"))

(defn- runnable?
  "Both halves have to be there: `bb` on PATH, and the scripts where the JVM side
  expects them."
  []
  (and (.isFile ^java.io.File editor-client-script)
       (.isFile ^java.io.File report-script)
       (try (zero? (:exit (shell/sh "bb" "--version")))
            (catch java.io.IOException _ false))))

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- composed-unit-dir []
  (io/file (registry-root) "composed" "loan-app-plus-disposition"))

(def ^:private selection
  {:root (registry-root)
   :units [{:repo "loan-app-ruleset"}
           {:repo "loan-disposition-ruleset"}]})

(def ^:private app-approved
  "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?")

(def ^:private notice-approved
  "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app")

(def ^:private keyword-outcome
  ":loan-app/application-outcome")

(defn- jvm-composed
  "The JVM composed + rehydrated analysis over the two checked-in source units —
  the same value the server's `:registry` mode serves."
  []
  (let [reg (registry/->registry {:root (registry-root)
                                  :units (:units selection)})]
    (rehydrate/rehydrate-analysis
     (compose/->composed-analysis reg (:units selection)))))

(defn- bb-navigate
  "The script's EDN answer for one navigate input over `selection`."
  [input]
  (let [{:keys [exit out err]}
        (shell/sh "bb" (str editor-client-script) (pr-str selection) (pr-str input))]
    (is (zero? exit) (str "editor_client.bb exited " exit ": " err))
    (edn/read-string out)))

(defn- run-report
  "The `annotations_report.bb` stdout for one subcommand over the composed unit."
  [& args]
  (let [{:keys [exit out err]}
        (apply shell/sh "bb" (str report-script) (str (composed-unit-dir)) args)]
    (is (zero? exit) (str "annotations_report.bb exited " exit ": " err))
    out))

(deftest editor-client-lists-unit-repos-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [{:keys [exit out err]}
          (shell/sh "bb" (str editor-client-script) "--list-units" (registry-root))]
      (is (zero? exit) (str "editor_client.bb --list-units exited " exit ": " err))
      (is (= ["composed/loan-app-plus-disposition"
              "loan-app-ruleset"
              "loan-disposition-ruleset"
              "loan-disposition-ruleset@alt"]
             (edn/read-string out))))))

(deftest editor-client-composes-multi-unit-selection-and-matches-rehydrate-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [composed (jvm-composed)
          jvm-producers (set (map :name (get-in composed [:fact-types keyword-outcome
                                                          :inserted-by-rules])))
          jvm-consumers (set (map :name (concat (get-in composed [:fact-types keyword-outcome
                                                                  :used-by-rules])
                                                (get-in composed [:fact-types keyword-outcome
                                                                  :used-by-queries]))))
          producer-answer (bb-navigate {:production nil :side :lhs :token keyword-outcome})
          consumer-answer (bb-navigate {:production nil :side :rhs :token keyword-outcome})]
      (testing "the bb producer closure agrees with the JVM rehydrated composition"
        (is (not (contains? producer-answer :error)) producer-answer)
        (is (= jvm-producers (set (map :name (:targets producer-answer))))))
      (testing "the bb consumer closure agrees with the JVM rehydrated composition"
        (is (not (contains? consumer-answer :error)) consumer-answer)
        (is (= jvm-consumers (set (map :name (:targets consumer-answer))))))
      (testing "the cross-unit producer edge is visible from the downstream unit"
        (let [scoped (bb-navigate {:production notice-approved
                                   :side :lhs
                                   :token keyword-outcome})]
          (is (not (contains? scoped :error)) scoped)
          (is (contains? (set (map :name (:targets scoped))) app-approved))))
      (testing "the bb producer answer agrees with the `producers` subcommand"
        (let [out (run-report "producers" keyword-outcome)]
          (doseq [p (map :name (:targets producer-answer))]
            (is (str/includes? out p)))))
      (testing "the bb consumer answer agrees with the `consumers` subcommand"
        (let [out (run-report "consumers" keyword-outcome)]
          (doseq [c (map :name (:targets consumer-answer))]
            (is (str/includes? out c))))))))

(deftest editor-client-single-unit-resolves-record-ctor-token-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [single-selection {:root (registry-root)
                            :units [{:repo "loan-app-ruleset"}]}
          ctor-token "clara.explorer.test.rules.loan_app_rules/->ApplicationOutcome"
          input {:production app-approved
                 :side :rhs
                 :token ctor-token}
          {:keys [exit out err]}
          (shell/sh "bb" (str editor-client-script)
                    (pr-str single-selection) (pr-str input))
          answer (edn/read-string out)]
      (is (zero? exit) (str "editor_client.bb exited " exit ": " err))
      (testing "a record-ctor token normalizes to its class type and finds consumers"
        (is (not (contains? answer :error)) answer)
        (is (= "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"
               (:type answer)))
        (is (contains? (set (map :name (:targets answer)))
                       "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?"))))))

(deftest editor-client-branch-unit-matches-mainline-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [mainline {:root (registry-root) :units [{:repo "loan-disposition-ruleset"}]}
          branch {:root (registry-root)
                  :units [{:repo "loan-disposition-ruleset" :branch "alt"}]}
          input {:production nil :side :rhs :token keyword-outcome}
          run (fn [selection]
                (let [{:keys [exit out err]}
                      (shell/sh "bb" (str editor-client-script)
                                (pr-str selection) (pr-str input))]
                  (is (zero? exit) (str "editor_client.bb exited " exit ": " err))
                  (edn/read-string out)))]
      (testing "a branch variant is read from <repo>/branches/<label> and answers like the mainline"
        (let [mainline-answer (run mainline)
              branch-answer (run branch)]
          (is (not (contains? branch-answer :error)) branch-answer)
          (is (= (set (map :name (:targets mainline-answer)))
                 (set (map :name (:targets branch-answer))))))))))
