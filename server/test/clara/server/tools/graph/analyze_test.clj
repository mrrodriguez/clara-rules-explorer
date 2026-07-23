(ns clara.server.tools.graph.analyze-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clara.rules :as r]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.analyze.rhs :as rhs]
            [clara.server.tools.graph.memory :as memory]
            [clara.server.tools.graph.rules.loan-doc-rules :as ldr]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.analyze-test-rules :as atr])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            AllGivenDocuments
            AllRequiredDocuments
            DocumentCheckInput
            DocumentCheck]
           [clara.server.tools.graph.rules.loan_doc_rules
            AllIdCardGivenDocuments
            StaleDocumentNotice]
           [clara.server.tools.graph.rules.analyze_test_rules
            LocalDummyRecord]))

;; ---------------------------------------------------------------------------
;; Shared session fixtures (computed once, reused across deftests)
;;
;; The session is the source of truth: analyze-session-rules synthesizes
;; per-namespace sources (real source + one snippet def per rule RHS) and
;; prunes hook-emitted defrule/defquery constructs. generate-annotations-from-analysis
;; defaults its rules filter to the session's rules (productions with an :rhs).
;; ---------------------------------------------------------------------------

(def ^:private rules-prefix "clara.server.tools.graph.rules")

(def ^:private loan-doc-session
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules))

(def ^:private loan-doc-analysis
  (analyze/analyze-session-rules
   {:session-or-rulebase loan-doc-session
    :include-ns-prefixes [rules-prefix]}))

(def ^:private loan-doc-annotations
  "Annotations for the loan-doc rule suite (separate rule set from edge cases)."
  (analyze/generate-annotations-from-analysis
   {:analysis loan-doc-analysis
    :session-or-rulebase loan-doc-session}))

(def ^:private edge-case-session
  (r/mk-session 'clara.server.tools.graph.rules.analyze-test-rules))

(def ^:private edge-case-analysis
  (analyze/analyze-session-rules
   {:session-or-rulebase edge-case-session
    :include-ns-prefixes [rules-prefix]}))

(def ^:private edge-case-annotations
  "Annotations for the analyze-test-rules suite (all edge-case rules)."
  (analyze/generate-annotations-from-analysis
   {:analysis edge-case-analysis
    :session-or-rulebase edge-case-session}))

(def ^:private edge-case-annotations-filtered
  "Annotations for same rules but with a rules-filter that only keeps side-effect-only."
  (analyze/generate-annotations-from-analysis
   {:analysis edge-case-analysis
    :session-or-rulebase edge-case-session
    :rules-filter [`atr/rule-side-effect-only]}))

;; ---------------------------------------------------------------------------
;; Static insert types (record constructors traced through RHS and helpers)
;; ---------------------------------------------------------------------------

(deftest test-static-insert-types
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
             (:clara-rules/insert-types (get ann `ldr/app-has-all-required-docs))))))

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

      ;; Rule H2: insert! with varargs
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-varargs))))

      ;; Rule H4: complex nested doseq loop
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-complex-rhs-nested))))

      ;; Rule H5: insert-all! with collection literal
      (is (= [`LocalDummyRecord]
             (:clara-rules/insert-types (get ann `atr/rule-insert-all-collection))))

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

;; ---------------------------------------------------------------------------
;; Dynamic insert callsites — runtime resolution chain (analyze.rhs)
;; ---------------------------------------------------------------------------

(defn- resolved-detection
  "Expected dynamic-detection map for a single resolved callsite."
  [ns-sym filename source-str token]
  {:callsites [{:source-str source-str
                :ns-name-sym ns-sym
                :filename filename
                :status :resolved
                :resolved-types [token]}]
   :resolution :full})

(defn- unresolved-detection
  "Expected dynamic-detection map for a single unresolved callsite."
  [ns-sym filename source-str]
  {:callsites [{:source-str source-str
                :ns-name-sym ns-sym
                :filename filename
                :status :unresolved}]
   :resolution :none})

(deftest test-dynamic-insert-types-detected
  (let [ann edge-case-annotations
        ns-sym 'clara.server.tools.graph.rules.analyze-test-rules
        filename "clara/server/tools/graph/rules/analyze_test_rules.clj"]

    (testing "Java constructor syntax variants → resolved and promoted to insert-types"
      (doseq [[rule-sym source-str]
              [[`atr/rule-java-constructor-dot "(DocumentCheck. ?app-id :pass \"dot-style\" nil nil)"]
               [`atr/rule-java-constructor-new "(new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass \"new-style\" nil nil)"]
               [`atr/rule-java-constructor-fq-dot "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. ?app-id :pass \"fq-dot-style\" nil nil)"]
               [`atr/rule-java-constructor-short-new "(new DocumentCheck ?app-id :pass \"short-new-style\" nil nil)"]
               [`atr/rule-java-constructor-short-modern "(DocumentCheck/new ?app-id :pass \"short-modern\" nil nil)"]
               [`atr/rule-java-constructor-fq-modern "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass \"fq-modern\" nil nil)"]]]
        (is (= (resolved-detection ns-sym filename source-str `DocumentCheck)
               (:clara-rules/dynamic-insert-types-detected (get ann rule-sym)))
            (str rule-sym " callsite resolves via the ctor chain"))
        (is (= [`DocumentCheck] (:clara-rules/insert-types (get ann rule-sym)))
            (str rule-sym " resolved type is promoted to :insert-types"))))

    (testing "Java constructor inside a helper fn → callsite at the helper, resolved"
      (is (= (resolved-detection ns-sym filename
                                 "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass \"helper-insert\" nil nil)"
                                 `DocumentCheck)
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-helper-does-insert))))
      (is (= [`DocumentCheck] (:clara-rules/insert-types (get ann `atr/rule-helper-does-insert)))))

    (testing "Let-bound constructor local → traced to its init form and resolved"
      (let [a (get ann `atr/rule-let-bound-ctor)]
        (is (= (resolved-detection ns-sym filename "dc" `DocumentCheck)
               (:clara-rules/dynamic-insert-types-detected a))
            "the callsite arg is the local symbol; resolution traces the binding's init form")
        (is (= [`DocumentCheck] (:clara-rules/insert-types a)))))

    (testing "Helper call args are NOT automatically resolved (caller's business)"
      (is (= (unresolved-detection ns-sym filename "(make-java-document-check-nested ?app-id)")
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-nested-java-helper-call))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-nested-java-helper-call)))))

    (testing "with-meta map fact → unresolved (fact-type-fn honoring is the caller's business)"
      (is (= (unresolved-detection ns-sym filename
                                   "(with-meta {:app-id ?app-id, :status :pass} {:type :custom-map-type})")
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-metadata-map-fact))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-metadata-map-fact)))))

    (testing "constructor-NAMED helper (->fact) → unresolved (derived class does not load)"
      (is (= (unresolved-detection ns-sym filename
                                   "(->fact :custom-fact-type {:app-id ?app-id, :status :pass})")
             (:clara-rules/dynamic-insert-types-detected (get ann `atr/rule-fact-builder-call))))
      (is (nil? (:clara-rules/insert-types (get ann `atr/rule-fact-builder-call)))))

    (testing "mixed varargs → :partial aggregate; resolved args still promoted"
      (let [a (get ann `atr/rule-insert-mixed-varargs)
            dyn (:clara-rules/dynamic-insert-types-detected a)]
        (is (= :partial (:resolution dyn)))
        (is (= 2 (count (:callsites dyn))))
        (is (= #{:resolved :unresolved} (set (map :status (:callsites dyn)))))
        (is (= [`DocumentCheck] (:clara-rules/insert-types a))
            "only the ctor arg's type is promoted")))))

;; ---------------------------------------------------------------------------
;; Retract types
;; ---------------------------------------------------------------------------

(deftest test-retract-types
  (let [ann edge-case-annotations
        ns-sym 'clara.server.tools.graph.rules.analyze-test-rules
        filename "clara/server/tools/graph/rules/analyze_test_rules.clj"]

    (testing "Static retract types — record constructors"
      ;; Rule H3: retract! with varargs
      (let [h3 (get ann `atr/rule-retract-varargs)]
        (is (some? h3))
        (is (= [`LocalDummyRecord] (:clara-rules/retract-types h3)))
        (is (nil? (:clara-rules/dynamic-retract-types-detected h3))
            "No dynamic-retract-types when statically resolved")))

    (testing "Dynamic retract types — Java constructors resolve and promote"
      ;; Rule I1: short Class. constructor
      (is (= (resolved-detection ns-sym filename
                                 "(DocumentCheck. ?app-id :pass \"dot-retract\" nil nil)"
                                 `DocumentCheck)
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-dot))))
      (is (= [`DocumentCheck] (:clara-rules/retract-types (get ann `atr/rule-retract-java-dot))))

      ;; Rule I2: new Class constructor
      (is (= (resolved-detection ns-sym filename
                                 "(new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass \"new-retract\" nil nil)"
                                 `DocumentCheck)
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-new))))
      (is (= [`DocumentCheck] (:clara-rules/retract-types (get ann `atr/rule-retract-java-new))))

      ;; Rule I3: modern Class/new constructor
      (is (= (resolved-detection ns-sym filename
                                 "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass \"modern-retract\" nil nil)"
                                 `DocumentCheck)
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-java-modern))))
      (is (= [`DocumentCheck] (:clara-rules/retract-types (get ann `atr/rule-retract-java-modern)))))

    (testing "Dynamic retract types — metadata map facts and helpers"
      ;; Rule I4: with-meta map fact (retract) — unresolved
      (is (= (unresolved-detection ns-sym filename
                                   "(with-meta {:app-id ?app-id, :status :pass} {:type :custom-retract-type})")
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-metadata-map))))
      (is (nil? (:clara-rules/retract-types (get ann `atr/rule-retract-metadata-map))))

      ;; Rule I5: helper that does Java constructor + retract — resolved at the helper
      (is (= (resolved-detection ns-sym filename
                                 "(clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass \"helper-retract\" nil nil)"
                                 `DocumentCheck)
             (:clara-rules/dynamic-retract-types-detected (get ann `atr/rule-retract-helper-call))))
      (is (= [`DocumentCheck] (:clara-rules/retract-types (get ann `atr/rule-retract-helper-call)))))))

;; ---------------------------------------------------------------------------
;; Macro-emitted rules: the session sees rules kondo hooks never could
;; ---------------------------------------------------------------------------

(deftest test-extract-doc-meta-rule-captured
  (testing "def-fact-fn-emitted rule appears as a captured dynamic callsite"
    (let [dyn (:clara-rules/dynamic-insert-types-detected
               (get loan-doc-annotations `ldr/extract-doc-meta-rule))]
      (is (some? dyn)
          "macro-emitted rule must be visible via the session (kondo hooks never see it)")
      (is (= :none (:resolution dyn))
          "the var-as-fact pattern is never automatically resolved (caller-guided)")
      (is (= 1 (count (:callsites dyn))))
      (is (nil? (:clara-rules/insert-types (get loan-doc-annotations `ldr/extract-doc-meta-rule))))
      (let [{:keys [source-str ns-name-sym filename status]}
            (first (:callsites dyn))]
        (is (re-matches #"resolved__\d+__auto__" source-str)
            "arg is the macro's gensym'd local; assert the shape, never the exact gensym")
        (is (= :unresolved status))
        (is (= 'clara.server.tools.graph.rules.loan-doc-rules ns-name-sym))
        (is (= "clara/server/tools/graph/rules/loan_doc_rules.clj" filename)))))

  (testing "loan-doc dynamic rules: helpers unresolved, direct Java ctor resolved"
    (let [ann loan-doc-annotations
          ns-sym 'clara.server.tools.graph.rules.loan-doc-rules
          filename "clara/server/tools/graph/rules/loan_doc_rules.clj"]
      (is (= (unresolved-detection ns-sym filename "(build-compliance-review ?app-id)")
             (:clara-rules/dynamic-insert-types-detected (get ann `ldr/dynamic-insert-compliance-review))))
      (is (= (unresolved-detection ns-sym filename "(build-compliance-via-metadata ?app-id)")
             (:clara-rules/dynamic-insert-types-detected (get ann `ldr/dynamic-insert-compliance-metadata))))
      (is (= (unresolved-detection ns-sym filename "(build-audit-trail-entry ?app-id :doc-check-passed)")
             (:clara-rules/dynamic-insert-types-detected (get ann `ldr/dynamic-insert-audit-trail))))
      (is (= (resolved-detection ns-sym filename
                                 "(StaleDocumentNotice. ?app-id :paystub \"no-longer-needed\")"
                                 `StaleDocumentNotice)
             (:clara-rules/dynamic-retract-types-detected (get ann `ldr/dynamic-retract-stale-notice))))
      (is (= [`StaleDocumentNotice]
             (:clara-rules/retract-types (get ann `ldr/dynamic-retract-stale-notice)))))))

;; ---------------------------------------------------------------------------
;; :callsite-resolver-fn — the caller escape hatch
;; ---------------------------------------------------------------------------

(deftest test-callsite-resolver-fn
  (testing "resolver resolves the var-as-fact callsite after locals tracing"
    (let [resolver-calls (atom [])
          resolver (fn [call-ctx]
                     (swap! resolver-calls conj call-ctx)
                     (let [{:keys [arg-form ns-name-sym]} call-ctx]
                       (when (and (seq? arg-form)
                                  (= 'var (first arg-form))
                                  (symbol? (second arg-form)))
                         (when-let [v (ns-resolve (the-ns ns-name-sym) (second arg-form))]
                           (when-let [t (:type (meta v))]
                             {:resolved-types [t]})))))
          ann (analyze/generate-annotations-from-analysis
               {:analysis loan-doc-analysis
                :session-or-rulebase loan-doc-session
                :callsite-resolver-fn resolver})
          a (get ann `ldr/extract-doc-meta-rule)
          dyn (:clara-rules/dynamic-insert-types-detected a)]
      (is (= :full (:resolution dyn)))
      (is (= [:extract-doc-meta] (:clara-rules/insert-types a))
          "resolver-provided fact type is promoted (arbitrary token shapes pass through)")
      (let [{:keys [source-str status resolved-types]} (first (:callsites dyn))]
        (is (= :resolved status))
        (is (= [:extract-doc-meta] resolved-types))
        (is (re-matches #"resolved__\d+__auto__" source-str)
            "the callsite still shows the literal boundary arg (the gensym local)"))

      (testing "resolver receives the full context, with locals traced"
        (let [extract-call (first (filter #(= 'clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule
                                              (some-> % :rule :name symbol))
                                          @resolver-calls))]
          (is (some? extract-call) "resolver saw the extract-doc-meta-rule callsite")
          (is (= '(var extract-doc-meta) (:arg-form extract-call))
              "arg-form is the traced init form, not the gensym local")
          (is (= :insert (:direction extract-call)))
          (is (= 'clara.rules/insert! (:boundary-fn extract-call)))
          (is (= 'clara.server.tools.graph.rules.loan-doc-rules (:ns-name-sym extract-call)))
          (is (= "clara/server/tools/graph/rules/loan_doc_rules.clj" (:filename extract-call)))
          (is (= "clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule"
                 (:name (:rule extract-call)))
              "the full production is handed over")
          (is (some? (:rhs (:rule extract-call))))
          (is (some? (:lhs (:rule extract-call))))))))

  (testing "throwing resolver degrades to unresolved capture"
    (let [ann (analyze/generate-annotations-from-analysis
               {:analysis loan-doc-analysis
                :session-or-rulebase loan-doc-session
                :callsite-resolver-fn (fn [_] (throw (ex-info "boom" {})))})]
      (is (= loan-doc-annotations ann)
          "a resolver that always throws yields the same annotations as no resolver"))))

;; ---------------------------------------------------------------------------
;; :fact-type-spec-fn — var-alias chains (caller-guided var-as-fact discovery)
;; ---------------------------------------------------------------------------

(deftest test-lhs-var-bindings
  (testing "fact conditions: :fact-binding pairs with the condition's type"
    (is (= [{:binding '?t :fact-type :widget-transform}]
           (rhs/lhs-var-bindings [{:type :widget-transform :constraints [] :fact-binding :?t}]))))
  (testing "accumulator conditions: :result-binding pairs with the :from subtree's types"
    (is (= [{:binding '?ts :fact-type :widget-transform}]
           (rhs/lhs-var-bindings [{:accumulator 'some-acc
                                   :from {:type :widget-transform}
                                   :result-binding :?ts}]))))
  (testing "nested and/or compounds are walked"
    (is (= [{:binding '?x :fact-type :a} {:binding '?y :fact-type :c}]
           (rhs/lhs-var-bindings ['(:and {:type :a :fact-binding :?x}
                                         (:or {:type :b} {:type :c :fact-binding :?y}))]))))
  (testing "unbound and test conditions contribute nothing"
    (is (= [] (rhs/lhs-var-bindings [{:type :a} {:constraints []}])))))

(deftest test-fact-type-spec-fn
  (let [spec-fn (fn [t]
                  (when (= t :widget-transform)
                    {:aliases-var `atr/widget-transform}))
        aliased-callsite {:source-str "(->fact :widget-output {:app-id app-id})"
                          :ns-name-sym 'clara.server.tools.graph.rules.analyze-test-rules
                          :filename "clara/server/tools/graph/rules/analyze_test_rules.clj"}]

    (testing "without a spec fn, nothing alias-derived appears"
      (is (true? (:clara-rules/no-output-types
                  (get edge-case-annotations `atr/rule-consume-widget-transform)))
          "the consumer's own RHS has no boundary calls; the var-fact's chain stays invisible")
      (is (nil? (:clara-rules/dynamic-insert-types-detected
                 (get edge-case-annotations `atr/rule-consume-widget-transform)))))

    (testing "with a spec fn, the aliased var's chain attaches to the consuming rule"
      (let [ann (analyze/generate-annotations-from-analysis
                 {:analysis edge-case-analysis
                  :session-or-rulebase edge-case-session
                  :fact-type-spec-fn spec-fn})
            dyn (:clara-rules/dynamic-insert-types-detected
                 (get ann `atr/rule-consume-widget-transform))]
        (is (= :none (:resolution dyn))
            "alias-discovered callsites bypass the ctor chain — never automatically resolved")
        (is (= [(assoc aliased-callsite
                       :status :unresolved
                       :fact-type :widget-transform
                       :fact-type-spec {:aliases-var `atr/widget-transform})]
               (:callsites dyn)))
        (is (nil? (:clara-rules/insert-types (get ann `atr/rule-consume-widget-transform))))

        (testing "the producing side gains no alias context"
          (let [callsites (:callsites (:clara-rules/dynamic-insert-types-detected
                                       (get ann `atr/rule-insert-widget-transform)))]
            (is (some #(= "(var widget-transform)" (:source-str %)) callsites))
            (is (every? #(and (not (contains? % :fact-type))
                              (not (contains? % :fact-type-spec)))
                        callsites)
                "plain var references in the RHS explore the var's chain (pre-existing
                 reachability), but only alias-derived callsites carry the context")))))

    (testing "the resolver receives the alias context; resolved types promote"
      (let [resolver-calls (atom [])
            resolver (fn [call-ctx]
                       (swap! resolver-calls conj call-ctx)
                       (when (= :widget-transform (:fact-type call-ctx))
                         {:resolved-types [:widget-output]}))
            ann (analyze/generate-annotations-from-analysis
                 {:analysis edge-case-analysis
                  :session-or-rulebase edge-case-session
                  :fact-type-spec-fn spec-fn
                  :callsite-resolver-fn resolver})
            dyn (:clara-rules/dynamic-insert-types-detected
                 (get ann `atr/rule-consume-widget-transform))]
        (is (= :full (:resolution dyn)))
        (is (= [(assoc aliased-callsite
                       :status :resolved
                       :resolved-types [:widget-output]
                       :fact-type :widget-transform
                       :fact-type-spec {:aliases-var `atr/widget-transform})]
               (:callsites dyn)))
        (is (= [:widget-output]
               (:clara-rules/insert-types (get ann `atr/rule-consume-widget-transform)))
            "resolver-resolved alias callsites are promoted")
        (testing "non-alias callsites carry no alias context keys"
          (let [producer-call (first (filter #(= "(var widget-transform)" (:source-str %))
                                             @resolver-calls))]
            (is (some? producer-call) "the resolver still sees the producing side's callsite")
            (is (not (contains? producer-call :fact-type)))
            (is (not (contains? producer-call :fact-type-spec)))))))

    (testing "a throwing spec fn degrades to no alias derivation"
      (is (= edge-case-annotations
             (analyze/generate-annotations-from-analysis
              {:analysis edge-case-analysis
               :session-or-rulebase edge-case-session
               :fact-type-spec-fn (fn [_] (throw (ex-info "boom" {})))}))))))

;; ---------------------------------------------------------------------------
;; Queries, no-output rules, and machinery exclusion
;; ---------------------------------------------------------------------------

(deftest test-queries-produce-no-annotations
  (testing "queries are not rules — the default session rules-filter excludes them"
    (is (nil? (get loan-doc-annotations `ldr/find-document-check))
        "find-document-check is a defquery; it must not appear in rule annotations")))

(deftest test-no-output-types-and-filter
  (let [ann edge-case-annotations
        ann-f edge-case-annotations-filtered]

    (testing "Rule with no insert/retract → marked :no-output-types under the session filter"
      (is (true? (:clara-rules/no-output-types (get ann `atr/rule-side-effect-only))))
      (is (true? (:clara-rules/no-output-types (get loan-doc-annotations `ldr/collect-all-missing-required-docs)))))

    (testing "Explicit rules-filter narrows the annotated set"
      (is (= [`atr/rule-side-effect-only] (keys ann-f)))
      (is (true? (:clara-rules/no-output-types (get ann-f `atr/rule-side-effect-only)))))))

(deftest test-generate-annotations--excludes-insert-retract-machinery
  (testing "clara.rules insert!/retract! fns (and their non-! wrappers) never leak
            in as their own empty annotation entries"
    (let [ann edge-case-annotations
          ;; The full set that used to leak: every insert/retract fn the analyzer
          ;; recognizes, plus the non-! wrappers that reach them transitively.
          machinery '#{clara.rules/insert clara.rules/insert!
                       clara.rules/insert-unconditional clara.rules/insert-unconditional!
                       clara.rules/insert-all clara.rules/insert-all!
                       clara.rules/insert-all-unconditional!
                       clara.rules/retract clara.rules/retract!
                       clara.rules.engine/insert-facts!
                       clara.rules.engine/rhs-retract-facts!}]
      (is (empty? (filter machinery (keys ann)))
          "insert/retract machinery fns must not appear as annotation keys")
      (is (empty? (filter (fn [[_ v]] (and (map? v) (empty? v))) ann))
          "no entry should be an empty {} annotation")
      (is (every? #(= "clara.server.tools.graph.rules.analyze-test-rules" (namespace %))
                  (keys ann))
          "session-filtered annotations contain only the session's rule vars"))))

;; ---------------------------------------------------------------------------
;; Prune-and-replace evidence
;; ---------------------------------------------------------------------------

(deftest test-prune-and-replace--no-duplicates
  (testing "each rule has exactly one var-definition, named after the production"
    (doseq [[ns-sym analysis rule-names]
            [['clara.server.tools.graph.rules.loan-doc-rules
              loan-doc-analysis
              '#{collect-doc-meta collect-app-id-card-given-docs collect-app-given-docs
                 collect-app-req-docs collect-app-doc-check-input app-has-all-required-docs
                 collect-all-missing-required-docs dynamic-insert-compliance-review
                 dynamic-insert-compliance-metadata dynamic-retract-stale-notice
                 dynamic-insert-audit-trail extract-doc-meta-rule}]
             ['clara.server.tools.graph.rules.analyze-test-rules
              edge-case-analysis
              '#{rule-record-constructor rule-side-effect-only rule-retract-varargs}]]
            :let [defs (filter #(= ns-sym (:ns %)) (:var-definitions analysis))
                  def-counts (frequencies (map :name defs))]]
      (is (every? (fn [[_ n]] (= 1 n)) def-counts)
          (str "no duplicate var-definitions in " ns-sym))
      (is (every? #(= 1 (get def-counts %)) rule-names)
          "every production has exactly one def (snippet region is authoritative)")
      (is (not-any? #(str/starts-with? (str %) "__clara_explorer_rule_")
                    (map :name defs))
          "snippet tags are renamed to production names, never leaked")))

  (testing "query constructs produced by the bundled clara-rules hooks are pruned"
    (let [defs (filter #(= 'clara.server.tools.graph.rules.loan-doc-rules (:ns %))
                       (:var-definitions loan-doc-analysis))
          def-names (set (map :name defs))]
      (is (not (contains? def-names 'find-document-check))
          "defquery hook output is pruned from the source region; queries get no snippet"))))

;; ---------------------------------------------------------------------------
;; Config robustness
;; ---------------------------------------------------------------------------

(deftest test-config-parity--empty-config
  (testing "explicitly empty :config-dir yields identical annotations
            (prune is a no-op; the snippets carry everything)"
    (let [analysis-no-config
          (analyze/analyze-session-rules
           {:session-or-rulebase loan-doc-session
            :include-ns-prefixes [rules-prefix]
            :config-dir "test-resources/clara/server/tools/graph/empty-kondo-config"})
          annotations-no-config
          (analyze/generate-annotations-from-analysis
           {:analysis analysis-no-config
            :session-or-rulebase loan-doc-session})]
      (is (= loan-doc-annotations annotations-no-config)))))

;; ---------------------------------------------------------------------------
;; Session-scoped cache
;; ---------------------------------------------------------------------------

(deftest test-analyze-session-rules--cache-scoping
  (testing "explicit :cache-atom is populated; default runs use a fresh cache per call"
    (let [cache (atom {})]
      (analyze/analyze-session-rules
       {:session-or-rulebase loan-doc-session
        :include-ns-prefixes [rules-prefix]
        :cache-atom cache})
      (is (contains? @cache 'clara.server.tools.graph.rules.loan-doc-rules))
      (is (contains? @cache 'clara.server.tools.graph.rules.loan-app-facts)
          "dependencies transitively analyzed and cached"))
    (is (= loan-doc-annotations
           (analyze/generate-annotations-from-analysis
            {:analysis (analyze/analyze-session-rules
                        {:session-or-rulebase loan-doc-session
                         :include-ns-prefixes [rules-prefix]})
             :session-or-rulebase loan-doc-session}))
        "sequential runs with the default session-scoped cache produce identical annotations")))

;; ---------------------------------------------------------------------------
;; Reconstructed ns form (no source on the classpath)
;; ---------------------------------------------------------------------------

(deftest test-analyze-session-rules--reconstructed-ns-fallback
  (testing "eval'd namespace (no classpath source): reconstructed ns form still yields annotations"
    (let [ns-sym 'fake.eval-rules]
      (create-ns ns-sym)
      (binding [*ns* (the-ns ns-sym)]
        (eval '(clojure.core/require '[clara.rules :as r]))
        (eval '(r/defrule fake-eval-rule
                 [java.lang.Object]
                 =>
                 (r/insert! {:fake true}))))
      (let [session (r/mk-session ns-sym)
            annotations (analyze/generate-annotations-from-analysis
                         {:analysis (analyze/analyze-session-rules
                                     {:session-or-rulebase session
                                      :include-ns-prefixes ["fake."]})
                          :session-or-rulebase session})]
        (is (= {:callsites [{:source-str "{:fake true}"
                             :ns-name-sym ns-sym
                             :filename "fake/eval_rules.clj"
                             :status :unresolved}]
                :resolution :none}
               (:clara-rules/dynamic-insert-types-detected
                (get annotations 'fake.eval-rules/fake-eval-rule)))
            "literal args are captured, not classified — classification defers to the caller")
        (is (nil? (:clara-rules/insert-types (get annotations 'fake.eval-rules/fake-eval-rule)))
            "rule from a source-less namespace is analyzed via the reconstructed ns form")))))

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

;; ---------------------------------------------------------------------------
;; generate-annotations-from-analysis (caller-built analysis + required session)
;; ---------------------------------------------------------------------------

(deftest test-generate-annotations-from-analysis--from-merged-analysis
  (testing "works on a caller-built analysis (no analyze-session-rules synthesis)"
    (let [merged-analysis (analyze/build-analysis-from-namespaces
                           {:starting-namespaces ['clara.server.tools.graph.rules.analyze-test-rules]
                            :include-ns-prefixes [rules-prefix]})
          annotations (analyze/generate-annotations-from-analysis
                       {:analysis merged-analysis
                        :session-or-rulebase edge-case-session})]
      (is (some? (get annotations `atr/rule-record-constructor)))
      (is (= [`LocalDummyRecord]
             (get-in annotations [`atr/rule-record-constructor :clara-rules/insert-types])))))
  (testing ":session-or-rulebase is required"
    (is (thrown? clojure.lang.ExceptionInfo
                 (analyze/generate-annotations-from-analysis
                  {:analysis {}})))))

;; ---------------------------------------------------------------------------
;; add-auto-detected-annotations
;; ---------------------------------------------------------------------------

(deftest test-add-auto-detected-annotations--base-case
  (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                  'clara.server.tools.graph.rules.loan-app-rules)
                    (r/insert (laf/map->Application {:app-id "app-1"})
                              (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                              (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                    (r/fire-rules))
        snapshot (memory/session-snapshot session)
        enriched (analyze/add-auto-detected-annotations snapshot {})]
    (testing "Detects fact types from working memory"
      (let [crd (get enriched "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")]
        (is (some? (:clara-rules/dynamic-insert-types-detected crd)))
        (is (= {:fact-instance-derived-types
                ["clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"]
                :resolution :partial}
               (:clara-rules/dynamic-insert-types-detected crd))))
      ;; No insert-types added (that is enrich-annotations-from-session's job)
      (is (nil? (:clara-rules/insert-types
                 (get enriched "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")))))

    (testing "Does not add dynamic detection when annotation already covers the type"
      (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                      'clara.server.tools.graph.rules.loan-app-rules)
                        (r/insert (laf/map->Application {:app-id "app-1"})
                                  (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                  (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                        (r/fire-rules))
            snapshot (memory/session-snapshot session)
            existing-annos
            {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
             {:clara-rules/insert-types
              ['clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments]}}
            enriched (analyze/add-auto-detected-annotations snapshot existing-annos)
            crd (get enriched "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")]
        (is (nil? (:clara-rules/dynamic-insert-types-detected crd))
            "Should NOT add dynamic detection when annotation already has the type")))))

;; ---------------------------------------------------------------------------
;; enrich-annotations-from-session
;; ---------------------------------------------------------------------------

(deftest test-enrich-annotations-from-session--base-case
  (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                  'clara.server.tools.graph.rules.loan-app-rules)
                    (r/insert (laf/map->Application {:app-id "app-1"})
                              (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                              (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                    (r/fire-rules))
        fe      (analyze/enrich-annotations-from-session session {})]
    (testing "Adds insert-types and dynamic detection for rules with session-derived facts"
      (let [crd (get fe "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")]
        (is (= ["clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"]
               (:clara-rules/insert-types crd))
            "Should add the fact type to insert-types")
        (is (= {:fact-instance-derived-types
                ["clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments"]
                :resolution :partial}
               (:clara-rules/dynamic-insert-types-detected crd))
            "Should add dynamic detection")))

    (testing "Does NOT add dynamic detection for rules whose types are already in :props"
      (let [aop (get fe "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending")]
        (is (nil? (:clara-rules/dynamic-insert-types-detected aop))
            "app-outcome-pending has ApplicationOutcome in its :props")
        ;; insert-types should NOT include the session-derived type (props covers it)
        (is (nil? (:clara-rules/insert-types aop))
            "Should not add insert-types when :props already covers it")))))

(deftest test-enrich-annotations-from-session--preserves-callsites
  (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                  'clara.server.tools.graph.rules.loan-app-rules)
                    (r/insert (laf/map->Application {:app-id "app-1"})
                              (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                              (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                    (r/fire-rules))
        existing-annos
        {"clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs"
         {:clara-rules/insert-types
          ['clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments]
          :clara-rules/dynamic-insert-types-detected
          {:callsites [{:source-str "(->fact ...)"
                        :ns-name-sym 'some.ns
                        :filename "some/ns.clj"}]
           :resolution :full}}}
        fe  (analyze/enrich-annotations-from-session session existing-annos)
        crd (get fe "clara.server.tools.graph.rules.loan-doc-rules/collect-app-req-docs")]
    (testing "Preserves pre-existing :callsites when no new types detected"
      (let [dyn (:clara-rules/dynamic-insert-types-detected crd)]
        (is (some? dyn))
        (is (contains? dyn :callsites)
            "Should preserve :callsites from original annotations")
        (is (not (contains? dyn :fact-instance-derived-types))
            "Should NOT add :fact-instance-derived-types (types already known)")
        (is (= :full (:resolution dyn))
            "Should keep original :resolution")))))

(deftest test-enrich-annotations-from-session--dedup-against-props
  (testing "Session-derived types already in :props are not flagged as dynamic"
    (let [session (-> (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                    'clara.server.tools.graph.rules.loan-app-rules)
                      (r/insert (laf/map->Application {:app-id "app-1"})
                                (laf/map->RequiredDocument {:app-id "app-1" :doc-type :id-card})
                                (laf/map->GivenDocument {:app-id "app-1" :doc-type :id-card}))
                      (r/fire-rules))
          fe      (analyze/enrich-annotations-from-session session {})
          aop     (get fe "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending")]
      (is (nil? (:clara-rules/dynamic-insert-types-detected aop))
          "app-outcome-pending declares ApplicationOutcome in its :props")
      (is (nil? (:clara-rules/insert-types aop))
          "No insert-types added since :props already covers them"))))
