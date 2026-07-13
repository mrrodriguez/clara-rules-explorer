(ns clara.server.tools.graph.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [clara.rules :as r]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.rules.loan-doc-rules :as ldr]
            [clara.server.tools.graph.rules.analyze-test-rules :as atr])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            AllGivenDocuments
            AllRequiredDocuments
            DocumentCheckInput
            DocumentCheck]
           [clara.server.tools.graph.rules.loan_doc_rules
            AllIdCardGivenDocuments]
           [clara.server.tools.graph.rules.analyze_test_rules
            LocalDummyRecord]))

;; ---------------------------------------------------------------------------
;; Shared analysis data (computed once, reused across deftest)
;; ---------------------------------------------------------------------------

(def ^:private rules-prefix "clara.server.tools.graph.rules")

(def ^:private loan-doc-annotations
  "Annotations for the loan-doc rule suite (separate rule set from edge cases)."
  (analyze/generate-annotations-from-paths
   {:paths ["test/clara/server/tools/graph/rules/loan_doc_rules.clj"]}))

(def ^:private edge-case-annotations
  "Annotations for the analyze-test-rules suite (all edge-case rules)."
  (analyze/generate-annotations-from-paths
   {:paths ["test/clara/server/tools/graph/rules/analyze_test_rules.clj"]}))

(def ^:private edge-case-annotations-filtered
  "Annotations for same rules but with a rules-filter that only keeps side-effect-only."
  (analyze/generate-annotations-from-paths
   {:paths ["test/clara/server/tools/graph/rules/analyze_test_rules.clj"]
    :rules-filter [`atr/rule-side-effect-only]}))

;; ---------------------------------------------------------------------------
;; generate-annotations-from-paths
;; ---------------------------------------------------------------------------

(deftest test-generate-annotations-from-paths--static-insert-types
  (testing "Loan-doc rules: Clojure record insert types resolved statically"
    (let [ann loan-doc-annotations]
      (is (some? (get ann `ldr/collect-app-id-card-given-docs)))
      (is (= [`AllIdCardGivenDocuments]
             (:clara-rules/insert-types (get ann `ldr/collect-app-id-card-given-docs))))

      (is (some? (get ann `ldr/collect-app-given-docs)))
      (is (= [`AllGivenDocuments]
             (:clara-rules/insert-types (get ann `ldr/collect-app-given-docs))))

      (is (some? (get ann `ldr/collect-app-req-docs)))
      (is (= [`AllRequiredDocuments]
             (:clara-rules/insert-types (get ann `ldr/collect-app-req-docs))))

      (is (some? (get ann `ldr/collect-app-doc-check-input)))
      (is (= [`DocumentCheckInput]
             (:clara-rules/insert-types (get ann `ldr/collect-app-doc-check-input))))

      (is (some? (get ann `ldr/app-has-all-required-docs)))
      (is (= [`DocumentCheck]
             (:clara-rules/insert-types (get ann `ldr/app-has-all-required-docs))))

      (is (nil? (get ann `ldr/collect-all-missing-required-docs))
          "Should not list rules that do not insert/retract facts")))

  (testing "Edge cases: Clojure record constructors and helper tracing"
    (let [ann edge-case-annotations]

      ;; Rule A: standard Clojure record constructor
      (let [a (get ann `atr/rule-record-constructor)]
        (is (some? a))
        (is (= [`LocalDummyRecord] (:clara-rules/insert-types a)))
        (is (nil? (:clara-rules/dynamic-insert-types-detected a))
            "No dynamic-insert-types when statically resolved"))

      ;; Rule D: tracing through helper → record constructor
      (is (= [`DocumentCheck]
             (:clara-rules/insert-types (get ann `atr/rule-nested-helper-call))))

      ;; Rule H1: insert-all! with collection of constructed records
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-all-collection))))

      ;; Rule H2: insert! with varargs
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-varargs))))

      ;; Rule H4: complex nested doseq loop
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-complex-rhs-nested))))

      ;; Rule H7: insert-all! with collection built by helper
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-all-helper))))

      ;; Rule H8: insert-all! heterogeneous — only LocalDummyRecord static, Java ctor deferred
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-all-heterogeneous))))

      ;; Rule H9: insert-unconditional!
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-unconditional))))

      ;; Rule H10: insert-all-unconditional!
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-all-unconditional)))))))

(deftest test-generate-annotations-from-paths--dynamic-insert-types-detected
  (let [ann edge-case-annotations
        ns-sym 'clara.server.tools.graph.rules.analyze-test-rules
        filename "test/clara/server/tools/graph/rules/analyze_test_rules.clj"]

    (testing "Java constructor syntax variants → dynamic callsites"
      ;; Rule B: short Class. constructor
      (is (= {:callsites [{:source-str "(DocumentCheck. ?app-id :pass \"dot-style\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-dot))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-dot))))

      ;; Rule C: new Class constructor
      (is (= {:callsites [{:source-str "(new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass \"new-style\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-new))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-new))))

      ;; Rule B2: fully-qualified Class. constructor
      (is (= {:callsites [{:source-str "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. ?app-id :pass \"fq-dot-style\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-fq-dot))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-fq-dot))))

      ;; Rule C2: short-name new Class constructor
      (is (= {:callsites [{:source-str "(new DocumentCheck ?app-id :pass \"short-new-style\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-short-new))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-short-new))))

      ;; Rule F1: modern Class/new via short name
      (is (= {:callsites [{:source-str "(DocumentCheck/new ?app-id :pass \"short-modern\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-short-modern))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-short-modern))))

      ;; Rule F2: modern Class/new via fully-qualified name
      (is (= {:callsites [{:source-str "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass \"fq-modern\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-java-constructor-fq-modern))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-java-constructor-fq-modern)))))

    (testing "Java constructor through helper functions → dynamic callsites"
      ;; Rule G: nested helper calling Java constructor
      (is (= {:callsites [{:source-str "(make-java-document-check-nested ?app-id)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-nested-java-helper-call))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-nested-java-helper-call))))

      ;; Rule H5: helper that does Java constructor + insert
      (is (= {:callsites [{:source-str "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass \"helper-insert\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-helper-does-insert))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-helper-does-insert)))))

    (testing "Metadata map facts and custom fact builders → dynamic callsites"
      ;; Rule E: with-meta map fact
      (is (= {:callsites [{:source-str "(with-meta {:app-id ?app-id, :status :pass} {:type :custom-map-type})"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-metadata-map-fact))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-metadata-map-fact))))

      ;; Rule E2: custom ->fact builder (not a real record constructor)
      (is (= {:callsites [{:source-str "(->fact :custom-fact-type {:app-id ?app-id, :status :pass})"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-fact-builder-call))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-fact-builder-call)))))))

(deftest test-generate-annotations-from-paths--retract-types
  (let [ann edge-case-annotations
        ns-sym 'clara.server.tools.graph.rules.analyze-test-rules
        filename "test/clara/server/tools/graph/rules/analyze_test_rules.clj"]

    (testing "Static retract types — Clojure record varargs"
      (let [h3 (get ann `atr/rule-retract-varargs)]
        (is (some? h3))
        (is (= [`LocalDummyRecord] (:clara-rules/retract-types h3)))
        (is (nil? (:clara-rules/dynamic-retract-types-detected h3))
            "No dynamic-retract-types when statically resolved")))

    (testing "Dynamic retract types — Java constructors"
      ;; Rule I1: short Class. constructor
      (is (= {:callsites [{:source-str "(DocumentCheck. ?app-id :pass \"dot-retract\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-dot))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-java-dot))))

      ;; Rule I2: new Class constructor
      (is (= {:callsites [{:source-str "(new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass \"new-retract\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-new))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-java-new))))

      ;; Rule I3: modern Class/new constructor
      (is (= {:callsites [{:source-str "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass \"modern-retract\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-modern))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-java-modern)))))

    (testing "Dynamic retract types — metadata map facts and helpers"
      ;; Rule I4: with-meta map fact (retract)
      (is (= {:callsites [{:source-str "(with-meta {:app-id ?app-id, :status :pass} {:type :custom-retract-type})"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-metadata-map))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-metadata-map))))

      ;; Rule I5: helper that does Java constructor + retract
      (is (= {:callsites [{:source-str "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass \"helper-retract\" nil nil)"
                           :ns-name-sym ns-sym :filename filename}]}
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-helper-call))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-helper-call)))))))

(deftest test-generate-annotations-from-paths--no-output-types-and-filter
  (let [ann edge-case-annotations
        ann-f edge-case-annotations-filtered]

    (testing "Rule with no insert/retract → nil in annotations"
      (is (nil? (get ann `atr/rule-side-effect-only))
          "Should not produce an entry when unfiltered"))

    (testing "Filter keeps non-inserting rule, marks it with :no-output-types"
      (let [h6 (get ann-f `atr/rule-side-effect-only)]
        (is (some? h6)
            "Should produce an entry when listed in rules-filter")
        (is (true? (:clara-rules/no-output-types h6))
            "Should mark rules with no outputs as :no-output-types true")))))

;; ---------------------------------------------------------------------------
;; Utility fns
;; ---------------------------------------------------------------------------

(deftest test-ns->resource-base
  (is (= "clara/server/tools/graph/rules/analyze_test_rules"
         (analyze/ns->resource-base 'clara.server.tools.graph.rules.analyze-test-rules))))

(deftest test-find-ns-resource
  (is (some? (analyze/find-ns-resource 'clara.server.tools.graph.rules.analyze-test-rules)))
  (is (nil? (analyze/find-ns-resource 'non-existent-ns.fake))))

;; ---------------------------------------------------------------------------
;; build-analysis-from-namespaces
;; ---------------------------------------------------------------------------

(deftest test-build-analysis-from-namespaces--custom-cache
  (let [cache (atom {})
        merged (analyze/build-analysis-from-namespaces
                {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                 :include-ns-prefixes [rules-prefix]
                 :cache-atom cache})]
    (is (contains? @cache 'clara.server.tools.graph.rules.analyze-test-rules))
    (is (contains? @cache 'clara.server.tools.graph.rules.loan-app-facts)
        "Dependencies transitively analyzed and cached")
    (let [var-defs (set (map :name (:var-definitions merged)))]
      (is (contains? var-defs 'make-document-check))
      (is (contains? var-defs 'rule-record-constructor)))))

(deftest test-build-analysis-from-namespaces--global-cache-lifecycle
  (analyze/clear-global-analysis-cache!)
  (analyze/build-analysis-from-namespaces
   {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
    :include-ns-prefixes [rules-prefix]})
  (is (not-empty @@#'analyze/global-analysis-cache))
  (analyze/clear-global-analysis-cache!)
  (is (empty? @@#'analyze/global-analysis-cache)))

(deftest test-build-analysis-from-namespaces--in-memory
  (let [source "(ns my.dynamic.rules
                  (:require [clara.rules :as r]))

                (r/defrule dynamic-rule
                  =>
                  (r/insert! (with-meta {:id 1} {:type :dynamic-fact-type})))"
        analysis (analyze/build-analysis-from-namespaces
                  {:starting-namespaces ['my.dynamic.rules]
                   :in-memory-sources {'my.dynamic.rules source}})
        annotations (analyze/generate-annotations-from-analysis
                     {:analysis analysis
                      :in-memory-sources {'my.dynamic.rules source}})]
    (is (some? (get annotations 'my.dynamic.rules/dynamic-rule)))
    (is (nil? (get-in annotations ['my.dynamic.rules/dynamic-rule :clara-rules/insert-types])))
    (is (= {:callsites [{:source-str "(with-meta {:id 1} {:type :dynamic-fact-type})"
                         :ns-name-sym 'my.dynamic.rules
                         :filename "my/dynamic/rules.clj"}]}
           (get-in annotations
                   ['my.dynamic.rules/dynamic-rule :clara-rules/dynamic-insert-types-detected])))))

;; ---------------------------------------------------------------------------
;; generate-annotations-from-analysis (production-style pipeline)
;; ---------------------------------------------------------------------------

(deftest test-generate-annotations-from-analysis--from-merged-analysis
  (let [merged-analysis (analyze/build-analysis-from-namespaces
                         {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                          :include-ns-prefixes [rules-prefix]})
        annotations (analyze/generate-annotations-from-analysis
                     {:analysis merged-analysis})]
    (is (some? (get annotations `atr/rule-record-constructor)))
    (is (= [`LocalDummyRecord]
           (get-in annotations [`atr/rule-record-constructor :clara-rules/insert-types])))))

;; ---------------------------------------------------------------------------
;; analyze-session-rules (high-level API)
;; ---------------------------------------------------------------------------

(deftest test-analyze-session-rules
  (let [session (r/mk-session 'clara.server.tools.graph.rules.analyze-test-rules)
        analysis (analyze/analyze-session-rules
                  {:session-or-rulebase session
                   :include-ns-prefixes [rules-prefix]})
        rule-names (analyze/extract-session-rule-names session)
        annotations (analyze/generate-annotations-from-analysis
                     {:analysis analysis :rules-filter rule-names})]
    (is (some? (get annotations `atr/rule-record-constructor)))
    (is (= [`LocalDummyRecord]
           (get-in annotations [`atr/rule-record-constructor :clara-rules/insert-types])))
    (is (some? (get annotations `atr/rule-insert-varargs)))
    (is (= [`LocalDummyRecord]
           (get-in annotations [`atr/rule-insert-varargs :clara-rules/insert-types])))))
