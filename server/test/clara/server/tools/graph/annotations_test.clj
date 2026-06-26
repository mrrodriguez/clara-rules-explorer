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

(deftest test-merge-annotations
  (let [rule-a 'my.ns/rule-a
        rule-b 'my.ns/rule-b
        rule-c 'my.ns/rule-c]

    (testing "rules only in annos1 are kept unchanged"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]
                            :clara-rules/notes "note-a"}}
            merged (ann/merge-annotations annos1 {})]
        (is (= {:clara-rules/insert-types [:TypeA]
                :clara-rules/notes "note-a"}
               (get merged rule-a)))))

    (testing "rules only in annos2 are added"
      (let [annos2 {rule-a {:clara-rules/insert-types [:TypeA]}}
            merged (ann/merge-annotations {} annos2)]
        (is (= {:clara-rules/insert-types [:TypeA]}
               (get merged rule-a)))))

    (testing "both maps empty"
      (is (= {} (ann/merge-annotations {} {}))))

    (testing "string keys are normalized to symbols"
      (let [annos1-str {"my.ns/rule-a" {:clara-rules/insert-types [:TypeA]}}
            annos2     {rule-a         {:clara-rules/notes "from-annos2"}}
            merged (ann/merge-annotations annos1-str annos2)]
        (is (= {:clara-rules/insert-types [:TypeA]
                :clara-rules/notes "from-annos2"}
               (get merged rule-a))
            "string key in annos1 matches symbol key in annos2 after normalization")))

    (testing "types are concatenated with default :merge strategy"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeB]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeA :TypeB]
               (:clara-rules/insert-types (get merged rule-a)))
            "both type vectors concatenated, annos1 first")))

    (testing "duplicate types are deduped during merge"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA :TypeB]}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeB :TypeC]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeA :TypeB :TypeC]
               (:clara-rules/insert-types (get merged rule-a))))))

    (testing ":replace strategy uses annos2 types only"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeB]
                            :clara-rules/merge-props {:clara-rules/insert-types :replace}}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeB]
               (:clara-rules/insert-types (get merged rule-a)))
            "annos2 types replace annos1 types")))

    (testing ":replace strategy for retract-types"
      (let [annos1 {rule-a {:clara-rules/retract-types [:TypeA]}}
            annos2 {rule-a {:clara-rules/retract-types [:TypeB]
                            :clara-rules/merge-props {:clara-rules/retract-types :replace}}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeB]
               (:clara-rules/retract-types (get merged rule-a))))))

    (testing "merge strategies are independent per field"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]
                            :clara-rules/retract-types [:RetA]}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeB]
                            :clara-rules/retract-types [:RetB]
                            :clara-rules/merge-props {:clara-rules/insert-types :replace
                                                      :clara-rules/retract-types :merge}}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeB]
               (:clara-rules/insert-types (get merged rule-a)))
            "insert-types replaced by annos2")
        (is (= [:RetA :RetB]
               (:clara-rules/retract-types (get merged rule-a)))
            "retract-types concatenated")))

    (testing "no-output-types: annos2 wins when both declare"
      (let [annos1 {rule-a {:clara-rules/no-output-types false}}
            annos2 {rule-a {:clara-rules/no-output-types true}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (true? (:clara-rules/no-output-types (get merged rule-a))))))

    (testing "no-output-types: annos2 wins, annos1 didn't declare"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]}}
            annos2 {rule-a {:clara-rules/no-output-types true}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (true? (:clara-rules/no-output-types (get merged rule-a))))))

    (testing "no-output-types: annos1 kept when annos2 doesn't declare"
      (let [annos1 {rule-a {:clara-rules/no-output-types true}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeA]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (true? (:clara-rules/no-output-types (get merged rule-a))))))

    (testing "notes: annos2 wins when both declare"
      (let [annos1 {rule-a {:clara-rules/notes "annos1-note"}}
            annos2 {rule-a {:clara-rules/notes "annos2-note"}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= "annos2-note" (:clara-rules/notes (get merged rule-a))))))

    (testing "notes: annos1 kept when annos2 doesn't declare"
      (let [annos1 {rule-a {:clara-rules/notes "annos1-note"}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeA]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= "annos1-note" (:clara-rules/notes (get merged rule-a))))))

    (testing "dynamic-insert-types-detected from annos2 flows through when annos1 lacks it"
      (let [annos1 {rule-a {}}
            annos2 {rule-a {:clara-rules/dynamic-insert-types-detected true}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (true? (:clara-rules/dynamic-insert-types-detected (get merged rule-a))))))

    (testing "dynamic-insert-types-detected: annos2 controls — annos2's value wins"
      (let [annos1 {rule-a {:clara-rules/dynamic-insert-types-detected false}}
            annos2 {rule-a {:clara-rules/dynamic-insert-types-detected true}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (true? (:clara-rules/dynamic-insert-types-detected (get merged rule-a))))))

    (testing "dynamic-insert-types-detected: omitted when annos2 doesn't declare it"
      (let [annos1 {rule-a {:clara-rules/dynamic-insert-types-detected true}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeA]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (nil? (:clara-rules/dynamic-insert-types-detected (get merged rule-a))))))

    (testing "multiple rules with different merge outcomes"
      (let [annos1 {rule-a {:clara-rules/insert-types [:TypeA]}
                    rule-b {:clara-rules/retract-types [:RetB1]}}
            annos2 {rule-a {:clara-rules/insert-types [:TypeA2]}
                    rule-b {:clara-rules/retract-types [:RetB2]}
                    rule-c {:clara-rules/insert-types [:TypeC]}}
            merged (ann/merge-annotations annos1 annos2)]
        (is (= [:TypeA :TypeA2]
               (:clara-rules/insert-types (get merged rule-a)))
            "rule-a: types concatenated")
        (is (= [:RetB1 :RetB2]
               (:clara-rules/retract-types (get merged rule-b)))
            "rule-b: retract types concatenated")
        (is (= {:clara-rules/insert-types [:TypeC]}
               (get merged rule-c))
            "rule-c: new rule from annos2 added")))))


