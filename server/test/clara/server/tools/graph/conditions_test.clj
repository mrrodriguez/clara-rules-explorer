(ns clara.server.tools.graph.conditions-test
  (:require [clara.rules.accumulators :as acc]
            [clara.server.tools.graph.conditions :as conditions]
            [clara.server.tools.graph.rules.loan-app-facts]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.test :refer [deftest is testing]])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            GivenDocument]))

(def prod-ns 'clara.server.tools.graph.rules.loan-doc-rules)
(def my-all (acc/all))

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
        (is (= #{} (:join-bindings r)))
        (is (= #{:?app-id} (:used-bindings r)))))

    (testing "accumulator joins on ?app-id and introduces nothing new"
      (let [r (get by-condition
                   {:accumulator '(clara.rules.accumulators/all)
                    :from {:type GivenDocument
                           :constraints '[(= ?app-id app-id)]}
                    :result-binding :?docs})]
        (is (= #{:?app-id} (:join-bindings r)))
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
        augmented (conditions/augment-lhs lhs {:prod-ns prod-ns :env nil})]

    (testing "leaf conditions are augmented with sorted binding vectors"
      (is (= [:?app-id] (:new-bindings (first augmented))))
      (is (= [] (:binding-keys (first augmented))))
      (is (= [:?app-id] (:used-bindings (first augmented))))
      (is (= [] (:ancestor-bindings (first augmented))))
      (is (= [:?app-id] (:all-bindings (first augmented)))))

    (testing "non-accumulator leaf contents are preserved"
      (is (= Application (:type (first augmented))))
      (is (= '[(= ?app-id app-id)] (:constraints (first augmented)))))

    (testing "accumulator conditions carry accumulator info and binding info"
      (let [acc-entry (second augmented)]
        (is (= {:form '(clara.rules.accumulators/all)
                :some-initial-value? true}
               (:accumulator acc-entry)))
        (is (= [:?app-id] (:binding-keys acc-entry)))
        (is (= [] (:new-bindings acc-entry)))
        (is (= :?docs (:result-binding acc-entry)))))

    (testing "fact-binding leaf is augmented too"
      (let [fact-entry (nth augmented 2)]
        (is (= [:?extract-doc-meta] (:used-bindings fact-entry)))
        (is (= [] (:binding-keys fact-entry)))
        (is (= [] (:new-bindings fact-entry)))))))

(deftest test-augment-lhs--not-group
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             [:not {:type Application
                    :constraints '[(= ?app-id app-id)]}]]
        augmented (conditions/augment-lhs lhs {:prod-ns prod-ns :env nil})
        not-entry (second augmented)
        not-leaf (second not-entry)]
    (testing "group vectors stay vectors"
      (is (= :not (first not-entry)))
      (is (map? not-leaf)))

    (testing "the nested leaf inside :not is augmented"
      (is (= [:?app-id] (:binding-keys not-leaf)))
      (is (= [] (:new-bindings not-leaf))))))
