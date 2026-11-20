(ns clara.explorer.artifacts.editor-client-bb-test
  "`bin/editor-client.bb` against the checked-in `rules-annos/` example registry.

  The script is the babashka twin of `clara.explorer.server.client/navigate` over a registry
  selection. It composes the selected units on the fly (`shared.selection` → `shared.compose` →
  `shared.rehydrate`) and answers `shared.navigate` — the same pure namespaces the JVM client
  delegates to — so the two runtimes cannot drift on the navigation body. What that sharing does
  not cover is the bb-side IO and composition wiring: whether the script reads the right parts,
  composes the same analysis the JVM `compose/->composed-analysis` builds, and reaches the same
  producer/consumer closures the `inspect-artifacts.bb` `producers`/`consumers` subcommands
  report. That is what these pin.

  Skipped, loudly, when `bb` is not installed: the script is developer tooling and its absence
  should not fail a build."
  (:require
   [clara.explorer.artifacts.compose :as compose]
   [clara.explorer.artifacts.registry :as registry]
   [clara.explorer.artifacts.rehydrate :as rehydrate]
   [clara.explorer.server.cache :as cache]
   [clara.explorer.server.client :as client]
   ;; Loaded only so fq record/class tokens resolve on the JVM side of the provenance parity
   ;; checks; the composed analysis still comes from the persisted selection on disk.
   [clara.explorer.test.rules.loan-app-rules]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]))

(set! *warn-on-reflection* true)

(def ^:private editor-client-script
  "Resolved off the `server/` dir, which is the cwd the test runner runs in."
  (io/file "bin" "editor-client.bb"))

(def ^:private inspect-artifacts-script
  (io/file "bin" "inspect-artifacts.bb"))

(defn- runnable?
  "Both halves have to be there: `bb` on PATH, and the scripts where the JVM side
  expects them."
  []
  (and (.isFile ^java.io.File editor-client-script)
       (.isFile ^java.io.File inspect-artifacts-script)
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
  (io/file (registry-root) "_compose" "loan-app-plus-disposition"))

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

(def ^:private outcome-record
  "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome")

(def ^:private application-type
  "clara.explorer.test.rules.loan_app_facts.Application")

(def ^:private document-check-type
  "clara.explorer.test.rules.loan_app_facts.DocumentCheck")

(def ^:private app-denied
  "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?")

(defn- jvm-composed
  "The JVM composed + rehydrated analysis over `sel` (default: the two-unit
  selection) — the same value the server's `:registry` mode serves."
  ([]
   (jvm-composed selection))
  ([sel]
   (let [reg (registry/->registry {:root (registry-root)
                                   :units (:units sel)})]
     (rehydrate/rehydrate-analysis
      (compose/->composed-analysis reg (:units sel))))))

(defn- bb-navigate
  "The script's EDN answer for one navigate input over `selection`."
  [input]
  (let [{:keys [exit out err]}
        (shell/sh "bb" (str editor-client-script) (pr-str selection) (pr-str input))]
    (is (zero? exit) (str "editor-client.bb exited " exit ": " err))
    (edn/read-string out)))

(defn- bb-provenance
  "The script's `--provenance` EDN answer for one provenance input over `selection`."
  [sel input]
  (let [{:keys [exit out err]}
        (shell/sh "bb" (str editor-client-script)
                  "--provenance" (pr-str sel) (pr-str input))]
    (is (zero? exit) (str "editor-client.bb --provenance exited " exit ": " err))
    (edn/read-string out)))

(defn- strip-sources
  "`answer` with every production `:source` removed: bb always reports `{:var? false}`, and the
  JVM sources depend on whether the rule namespaces happen to be loaded."
  [answer]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (contains? x :source)) (dissoc x :source) x))
                 answer))

(defn- jvm-provenance
  "The JVM registry-mode answer over `sel` for one provenance input, sources stripped."
  [sel input]
  (client/register! {:state-atom (atom {:rulebase-analysis (jvm-composed sel)})
                     :cache (cache/->cache)})
  (try
    (strip-sources (client/fact-type-provenance input))
    (finally
      (client/register! nil))))

(defn- node-count
  [node]
  (inc (reduce + 0 (map node-count (:children node)))))

(def ^:private caller-ns "clara.explorer.test.rules.loan-app-rules")

(defn- provenance-input
  "A cursor provenance input. `:caller-ns` is ignored by the bb runtime (tokens arrive fq) and
  lets the JVM side `ns-resolve` a fq class token."
  [token direction depth]
  {:node {:kind :cursor :input {:token token :caller-ns caller-ns}}
   :direction direction
   :depth depth
   :ancestors []})

(defn- run-inspect
  "The `inspect-artifacts.bb` stdout for one subcommand over the composed unit."
  [& args]
  (let [{:keys [exit out err]}
        (apply shell/sh "bb" (str inspect-artifacts-script) (str (composed-unit-dir)) args)]
    (is (zero? exit) (str "inspect-artifacts.bb exited " exit ": " err))
    out))

(deftest editor-client-dispatch-usage-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (do
      (testing "no arguments: the unflagged usage, non-zero exit"
        (let [{:keys [exit err]} (shell/sh "bb" (str editor-client-script))]
          (is (not (zero? exit)))
          (is (str/includes? err "usage: bb editor-client.bb '<selection-edn>' '<navigate-input-edn>'")
              err)))

      (testing "--list-units without a root: its own usage, non-zero exit"
        (let [{:keys [exit err]} (shell/sh "bb" (str editor-client-script) "--list-units")]
          (is (not (zero? exit)))
          (is (str/includes? err "usage: bb editor-client.bb --list-units '<registry-root>'")
              err)))

      (testing "--provenance without its input: its own usage, non-zero exit"
        (let [{:keys [exit err]} (shell/sh "bb" (str editor-client-script)
                                           "--provenance" (pr-str selection))]
          (is (not (zero? exit)))
          (is (str/includes? err "usage: bb editor-client.bb --provenance")
              err))))))

(deftest editor-client-lists-unit-repos-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [{:keys [exit out err]}
          (shell/sh "bb" (str editor-client-script) "--list-units" (registry-root))]
      (is (zero? exit) (str "editor-client.bb --list-units exited " exit ": " err))
      (is (= [{:key "_compose/loan-app-plus-disposition"
               :entry "{:repo \"_compose/loan-app-plus-disposition\"}"}
              {:key "loan-app-ruleset"
               :entry "{:repo \"loan-app-ruleset\"}"}
              {:key "loan-disposition-ruleset"
               :entry "{:repo \"loan-disposition-ruleset\"}"}
              {:key "loan-disposition-ruleset@ref=feature%2Fnew-tax"
               :entry "{:repo \"loan-disposition-ruleset\", :variant [[:ref \"feature/new-tax\"]]}"}]
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
        (let [out (run-inspect "producers" keyword-outcome)]
          (doseq [p (map :name (:targets producer-answer))]
            (is (str/includes? out p)))))
      (testing "the bb consumer answer agrees with the `consumers` subcommand"
        (let [out (run-inspect "consumers" keyword-outcome)]
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
      (is (zero? exit) (str "editor-client.bb exited " exit ": " err))
      (testing "a record-ctor token normalizes to its class type and finds consumers"
        (is (not (contains? answer :error)) answer)
        (is (= "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"
               (:type answer)))
        (is (contains? (set (map :name (:targets answer)))
                       "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?"))))))

(deftest editor-client-variant-unit-matches-mainline-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [mainline {:root (registry-root) :units [{:repo "loan-disposition-ruleset"}]}
          variant {:root (registry-root)
                   :units [{:repo "loan-disposition-ruleset" :variant [[:ref "feature/new-tax"]]}]}
          input {:production nil :side :rhs :token keyword-outcome}
          run (fn [selection]
                (let [{:keys [exit out err]}
                      (shell/sh "bb" (str editor-client-script)
                                (pr-str selection) (pr-str input))]
                  (is (zero? exit) (str "editor-client.bb exited " exit ": " err))
                  (edn/read-string out)))]
      (testing "a variant unit is read from _variants/<repo>/<variant path> and answers like the mainline"
        (let [mainline-answer (run mainline)
              variant-answer (run variant)]
          (is (not (contains? variant-answer :error)) variant-answer)
          (is (= (set (map :name (:targets mainline-answer)))
                 (set (map :name (:targets variant-answer))))))))))

;; ---------------------------------------------------------------------------
;; Fact type provenance parity (B1–B3)
;; ---------------------------------------------------------------------------

(deftest editor-client-provenance-matches-jvm-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [inputs [{:label "R1 producer on the keyword"
                   :input (provenance-input keyword-outcome :producer 0)}
                  {:label "R2 consumer on the record"
                   :input (provenance-input outcome-record :consumer 0)}
                  {:label "R3 external fact type"
                   :input (provenance-input application-type :producer 0)}
                  {:label "R4 retract-only production one level down"
                   :input (provenance-input document-check-type :consumer 1)}
                  {:label "R5 production root and its self-loop"
                   :input {:node {:kind :production :name app-denied}
                           :direction :producer
                           :depth 0
                           :ancestors []}}]]
      (doseq [{:keys [label input]} inputs]
        (testing label
          (is (= (jvm-provenance selection input)
                 (strip-sources (bb-provenance selection input)))))))))

(deftest editor-client-provenance-variant-unit-matches-mainline-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [mainline {:root (registry-root)
                    :units [{:repo "loan-disposition-ruleset"}]}
          variant {:root (registry-root)
                   :units [{:repo "loan-disposition-ruleset"
                            :variant [[:ref "feature/new-tax"]]}]}
          input (provenance-input keyword-outcome :consumer 0)
          production-names (fn [sel]
                             (->> (bb-provenance sel input)
                                  (get-in [:node :children])
                                  (map :name)
                                  set))]
      (testing "a variant unit is read from _variants/<repo>/<variant path> and answers like the mainline"
        (is (= (production-names mainline) (production-names variant)))))))

(deftest editor-client-provenance-full-depth-guard-test
  (if-not (runnable?)
    (println "SKIPPING editor-client-bb-test — babashka is not on PATH, or a script moved")
    (let [input (provenance-input outcome-record :consumer :full)
          bb-answer (strip-sources (bb-provenance selection input))
          jvm-answer (jvm-provenance selection input)]
      (testing "the full consumer tree terminates and matches the JVM node count"
        (is (not (contains? bb-answer :error)) bb-answer)
        (is (pos? (node-count (:node bb-answer))))
        (is (= (node-count (:node jvm-answer)) (node-count (:node bb-answer))))))))
