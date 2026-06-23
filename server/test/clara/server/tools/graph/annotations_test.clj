(ns clara.server.tools.graph.annotations-test
  (:require [clara.server.tools.graph.annotations :as ann]
            [clojure.test :refer [deftest is testing]]))

(deftest test-resolve-annotations--no-sidecar
  (let [production {:ns-name 'user
                    :name "user/my-rule"
                    :props {:clara-rules/insert-types [:TypeA]
                            :clara-rules/notes "Internal note"}}]

    (testing "basic with note"
      (let [resolved (ann/resolve-annotations production {})]
        (is (= [:TypeA] (:insert-types resolved)))
        (is (= :props (get-in resolved [:resolved-annotation-data :clara-rules/insert-types])))
        (is (= :props (get-in resolved [:resolved-annotation-data :clara-rules/notes])))
        (is (= "Internal note" (:notes resolved)))))

    (testing "Non-class types (keywords/symbols)"
      (let [production-2 {:ns-name 'user
                          :name "user/k-rule"
                          :props {:clara-rules/insert-types [:KeywordA 'SymbolB]}}
            resolved (ann/resolve-annotations production-2 {})]
        (is (= [:KeywordA 'SymbolB] (:insert-types resolved)))))

    (testing "Java Class types"
      (let [production-2 {:ns-name 'user
                          :name "user/k-rule"
                          :props {:clara-rules/insert-types [String]}}
            resolved (ann/resolve-annotations production-2 {})]
        (is (= [String] (:insert-types resolved)))))))

(deftest test-resolve-annotations--with-sidecar
  (let [production {:ns-name 'user
                    :name "user/my-rule"
                    :props {:clara-rules/insert-types [:TypeA]
                            :clara-rules/notes "Internal note"}}]
    (testing "Only sidecar declares types"
      (let [production {:name "user/my-rule"
                        :props {}}
            sidecar {"user/my-rule" {:clara-rules/insert-types [:TypeB]
                                     :clara-rules/notes "Sidecar note"}}
            resolved (ann/resolve-annotations production sidecar)]
        (is (= [:TypeB] (:insert-types resolved)))
        (is (= :sidecar (get-in resolved [:resolved-annotation-data :clara-rules/insert-types])))
        (is (= :sidecar (get-in resolved [:resolved-annotation-data :clara-rules/notes])))))

    (testing "Merging Path A and Path B"
      (let [sidecar {"user/my-rule" {:clara-rules/insert-types [:TypeB]
                                     :clara-rules/notes "Sidecar note"}}
            resolved (ann/resolve-annotations production sidecar)]
        (is (= [:TypeA :TypeB] (:insert-types resolved)))
        (is (= :merge (get-in resolved [:resolved-annotation-data :clara-rules/insert-types])))
        (is (= :sidecar (get-in resolved [:resolved-annotation-data :clara-rules/notes])))
        (is (= "Sidecar note" (:notes resolved)))))

    (testing "Replace strategy via clara-rules/merge-props"
      (let [sidecar {"user/my-rule" {:clara-rules/insert-types [:TypeB]
                                     :clara-rules/merge-props {:clara-rules/insert-types :replace}}}
            resolved (ann/resolve-annotations production sidecar)]
        (is (= [:TypeB] (:insert-types resolved)))
        (is (= :sidecar (get-in resolved [:resolved-annotation-data :clara-rules/insert-types])))
        (is (= :props (get-in resolved [:resolved-annotation-data :clara-rules/notes])))
        (is (= "Internal note" (:notes resolved))))))

  (testing "Java Class types"
    (let [production {:ns-name 'user
                      :name "user/my-rule"
                      :props {:clara-rules/insert-types [String]}}
          sidecar {"user/my-rule" {:clara-rules/insert-types [`String]}}
          resolved (ann/resolve-annotations production sidecar)]
      (is (= [String] (:insert-types resolved))))))
