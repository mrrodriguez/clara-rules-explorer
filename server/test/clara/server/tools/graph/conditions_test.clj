(ns clara.server.tools.graph.conditions-test
  (:require [clara.rules.accumulators :as acc]
            [clara.server.tools.graph.conditions :as conditions]
            [clara.server.tools.graph.rules.loan-app-facts]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            GivenDocument]))

(use-fixtures :once st/validate-schemas)

(def prod-ns 'clara.server.tools.graph.rules.loan-doc-rules)
(def my-all (acc/all))

(deftest test-normalize-lhs
  (let [raw [[:and {:type Application :constraints []}
              [:or {:type GivenDocument :constraints []}
               {:type GivenDocument :constraints []}]]]
        normalized (conditions/normalize-lhs raw)]
    (testing "groups become homogeneous maps"
      (is (= :and (get-in normalized [0 :condition-type])))
      (is (= :or (get-in normalized [0 :children 1 :condition-type]))))
    (testing "raw forms are retained for the binding walk"
      (is (= raw (conditions/get-raw-lhs normalized)))))

  (testing "accumulator :from subtrees are normalized and raw is retained"
    (let [raw [{:accumulator '(clara.rules.accumulators/all)
                :from [:not {:type GivenDocument :constraints []}]
                :result-binding :?docs}]
          normalized (conditions/normalize-lhs raw)]
      (is (= :not (get-in normalized [0 :from :condition-type])))
      (is (= raw (conditions/get-raw-lhs normalized))))))

(deftest test-accumulator-info
  (testing "inline accumulator with a non-nil initial-value"
    (is (= {:form '(clara.rules.accumulators/all)
            :some-initial-value? true}
           (conditions/accumulator-info '(clara.rules.accumulators/all) prod-ns))))

  (testing "inline accumulator with a nil initial-value"
    (is (= {:form '(clara.rules.accumulators/min :temperature)
            :some-initial-value? false}
           (conditions/accumulator-info '(clara.rules.accumulators/min :temperature) prod-ns))))

  (testing "a var holding an accumulator"
    (is (= {:form 'clara.server.tools.graph.conditions-test/my-all
            :some-initial-value? true}
           (conditions/accumulator-info 'clara.server.tools.graph.conditions-test/my-all prod-ns))))

  (testing "unevaluable accumulator forms throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Failed to evaluate accumulator form"
                          (conditions/accumulator-info '(this-does-not-exist) prod-ns)))))

(deftest test-analyze-lhs-bindings
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             {:accumulator '(clara.rules.accumulators/all)
              :from {:type GivenDocument
                     :constraints '[(= ?app-id app-id)]}
              :result-binding :?docs}
             {:type :extracted-doc-meta
              :constraints []
              :fact-binding :?extract-doc-meta}]
        records (conditions/analyze-lhs-bindings lhs nil)
        by-condition (zipmap (map :condition records) records)]

    (testing "the walk visits every sorted condition"
      (is (= [{:type Application
               :constraints '[(= ?app-id app-id)]}
              {:type :extracted-doc-meta
               :constraints []
               :fact-binding :?extract-doc-meta}
              {:accumulator '(clara.rules.accumulators/all)
               :from {:type GivenDocument
                      :constraints '[(= ?app-id app-id)]}
               :result-binding :?docs}]
             (mapv :condition records))))

    (testing "first condition introduces ?app-id"
      (let [r (first records)]
        (is (= #{:?app-id} (:new-bindings r)))
        (is (= #{} (:binding-keys r)))
        (is (= #{:?app-id} (:used-bindings r)))))

    (testing "accumulator joins on ?app-id and introduces nothing new"
      (let [r (get by-condition
                   {:accumulator '(clara.rules.accumulators/all)
                    :from {:type GivenDocument
                           :constraints '[(= ?app-id app-id)]}
                    :result-binding :?docs})]
        (is (= #{:?app-id} (:binding-keys r)))
        (is (= #{} (:new-bindings r)))
        (is (= :?docs (:result-binding r)))))))

(deftest test-augment-lhs
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             {:accumulator '(clara.rules.accumulators/all)
              :from {:type GivenDocument
                     :constraints '[(= ?app-id app-id)]}
              :result-binding :?docs}
             {:type :extracted-doc-meta
              :constraints []
              :fact-binding :?extract-doc-meta}]
        augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})]

    (testing "leaf conditions are augmented with a nested bindings summary"
      (is (= {:binding-keys []
              :new-bindings [:?app-id]}
             (:bindings (first augmented)))))

    (testing "non-accumulator leaf contents are preserved"
      (is (= Application (:type (first augmented))))
      (is (= '[(= ?app-id app-id)] (:constraints (first augmented)))))

    (testing "accumulator conditions carry accumulator info and binding info"
      (let [acc-entry (second augmented)]
        (is (= {:form '(clara.rules.accumulators/all)
                :some-initial-value? true}
               (:accumulator acc-entry)))
        (is (= {:binding-keys [:?app-id]
                :new-bindings []}
               (:bindings acc-entry)))
        (is (= :?docs (:result-binding acc-entry)))))

    (testing "fact-binding leaf is augmented too"
      (let [fact-entry (nth augmented 2)]
        (is (= {:binding-keys []
                :new-bindings []}
               (:bindings fact-entry)))))))

(deftest test-analyze-lhs-bindings--unsatisfiable
  (testing "an unsatisfiable LHS throws rather than looping"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not previously bound"
                          (conditions/analyze-lhs-bindings
                           [{:constraints '[(> ?n 0)]}]
                           nil)))))

(deftest test-augment-lhs--compound-negation-unaugmented
  (testing "compound negation groups are deferred explicitly (left untouched)"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:not [:and {:type Application
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})
          not-entry (second augmented)
          and-entry (first (:children not-entry))]
      (is (= :not (:condition-type not-entry)))
      (is (= :and (:condition-type and-entry)))
      (is (every? #(not (contains? % :bindings)) (:children and-entry))
          "compound-negation leaves must not be augmented"))))

(deftest test-analyze-lhs-bindings--exists-deterministic
  (testing "repeated analyses of the same :exists LHS are stable"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:exists {:type GivenDocument
                         :constraints '[(= ?app-id app-id)]}]]
          r1 (conditions/analyze-lhs-bindings lhs nil)
          r2 (conditions/analyze-lhs-bindings lhs nil)]
      (is (= (mapv :condition r1) (mapv :condition r2)))
      (let [exists-record (last r1)]
        (is (= :?__exists__1__0 (:result-binding exists-record)))
        (is (= :?__exists__1__0 (:result-binding (last r2))))))))

(deftest test-augment-lhs--not-group
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             [:not {:type Application
                    :constraints '[(= ?app-id app-id)]}]]
        augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})
        not-entry (second augmented)
        not-leaf (first (:children not-entry))]
    (testing "group entries are normalized maps"
      (is (= :not (:condition-type not-entry)))
      (is (map? not-leaf)))

    (testing "the nested leaf inside :not is augmented"
      (is (= {:binding-keys [:?app-id]
              :new-bindings []}
             (:bindings not-leaf))))))
