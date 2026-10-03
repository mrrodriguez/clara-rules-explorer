(ns clara.explorer.artifacts.diff-test
  "`clara.explorer.artifacts.diff` over hand-built `read-unit` values —
  one test per change tag — plus the checked-in example: the variant diff is
  empty, and the composed diff separates scope from change.

  The pure tests never touch disk: `unit`/`prod` build the `read-unit` shape
  directly, so each test names exactly the field its tag watches."
  (:require
   [clara.explorer.artifacts.diff :as d]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; builders
;; ===========================================================================

(defn- unit
  [m]
  (merge {:dir "unit"
          :manifest {:repo "r"
                     :source {:sha "aaa" :sha-short "aaa"
                              :branch "b" :working-tree "clean"}
                     :analysis-run {:namespaces ["a.ns"]}}
          :shape #{:nodes}
          :productions {}
          :fact-types {}
          :edges #{}}
         m))

(defn- prod
  [m]
  (merge {:kind :rule :ns "a.ns"
          :lhs-types [] :insert-types [] :retract-types []
          :lhs nil :rhs-form nil :doc nil :props nil
          :resolution {} :unit nil}
         m))

(defn- with-prods
  [u prods]
  (assoc u :productions (into (sorted-map) prods)))

;; ===========================================================================
;; one test per tag
;; ===========================================================================

(deftest production-change-tags-test
  (doseq [[tag before after] [[:kind {:kind :rule} {:kind :query}]
                              [:lhs-types {:lhs-types ["a/T"]} {:lhs-types ["a/T" "a/U"]}]
                              [:lhs {:lhs [{:type "a/T"}]} {:lhs [{:type "a/U"}]}]
                              [:insert-types {:insert-types ["a/T"]} {:insert-types []}]
                              [:retract-types {:retract-types []} {:retract-types ["a/T"]}]
                              [:rhs {:rhs-form "(a)"} {:rhs-form "(b)"}]
                              [:doc {:doc "d1"} {:doc "d2"}]
                              [:props {:props {:a 1}} {:props {:a 2}}]
                              [:resolution {:resolution {:insert :full}} {:resolution {:insert :none}}]
                              [:unit {:unit nil} {:unit "r"}]]]
    (testing (format "tag %s fires when only its field differs" tag)
      (let [b (with-prods (unit {}) {"a.ns/p" (prod before)})
            a (with-prods (unit {}) {"a.ns/p" (prod after)})
            changed (get-in (d/diff b a) [:productions :changed])]
        (is (= {"a.ns/p" #{tag}} changed)))))
  (testing "an identical production reports no tags"
    (let [u (with-prods (unit {}) {"a.ns/p" (prod {:rhs-form "(a)"})})]
      (is (empty? (get-in (d/diff u u) [:productions :changed]))))))

(deftest added-and-removed-test
  (let [b (with-prods (unit {}) {"a.ns/gone" (prod {})})
        a (with-prods (unit {}) {"a.ns/new" (prod {})})
        prods (:productions (d/diff b a))]
    (is (= ["a.ns/new"] (:added prods)))
    (is (= ["a.ns/gone"] (:removed prods)))
    (is (empty? (:changed prods)))))

(deftest scope-separation-test
  (testing "a namespace claimed on one side lands under :scope, not :added"
    (let [b (unit {:manifest {:repo "r"
                              :source {:sha "a" :working-tree "clean"}
                              :analysis-run {:namespaces ["a.ns"]}}})
          a (unit {:manifest {:repo "r"
                              :source {:sha "b" :working-tree "clean"}
                              :analysis-run {:namespaces ["a.ns" "b.ns"]}}
                   :productions (sorted-map "b.ns/p" (prod {:ns "b.ns"}))})
          result (d/diff b a)]
      (is (= {:only-before [] :only-after ["b.ns"]}
             (get-in result [:scope :namespaces])))
      (is (= ["b.ns/p"] (get-in result [:scope :productions])))
      (is (empty? (get-in result [:productions :added])))))
  (testing "a production in a scope-only namespace is not reported changed"
    (let [mk (fn [nses rhs]
               (unit {:manifest {:repo "r"
                                 :source {:sha "a" :working-tree "clean"}
                                 :analysis-run {:namespaces nses}}
                      :productions (sorted-map "b.ns/p" (prod {:ns "b.ns"
                                                               :rhs-form rhs}))}))
          result (d/diff (mk ["a.ns"] "(a)") (mk ["a.ns" "b.ns"] "(b)"))]
      (is (empty? (get-in result [:productions :changed])))
      (is (= ["b.ns/p"] (get-in result [:scope :productions]))))))

(deftest edges-test
  (testing "edge gain and loss"
    (let [b (unit {:edges #{["a.ns/up" "a.ns/down"] ["a.ns/old" "a.ns/down"]}})
          a (unit {:edges #{["a.ns/up" "a.ns/down"] ["a.ns/up" "a.ns/new"]}})
          edges (:edges (d/diff b a))]
      (is (= [["a.ns/up" "a.ns/new"]] (:gained edges)))
      (is (= [["a.ns/old" "a.ns/down"]] (:lost edges)))))
  (testing "an edge touching a scope-only production is reported under :scope"
    (let [b (unit {:edges #{["a.ns/up" "b.ns/down"]}
                   :productions (sorted-map "b.ns/down" (prod {:ns "b.ns"}))
                   :manifest {:repo "r" :source {:working-tree "clean"}
                              :analysis-run {:namespaces ["a.ns" "b.ns"]}}})
          a (unit {:edges #{}
                   :manifest {:repo "r" :source {:working-tree "clean"}
                              :analysis-run {:namespaces ["a.ns"]}}})
          result (d/diff b a)]
      (is (empty? (get-in result [:edges :gained])))
      (is (empty? (get-in result [:edges :lost])))
      (is (= [["a.ns/up" "b.ns/down"]] (get-in result [:scope :edges]))))))

(deftest fact-types-test
  (let [b (unit {:fact-types (sorted-map ":a/gone" {:ancestors #{}}
                                         ":a/both" {:ancestors #{"j/Object"}})})
        a (unit {:fact-types (sorted-map ":a/new" {:ancestors #{}}
                                         ":a/both" {:ancestors #{"j/Object" "j/Serial"}})})
        fts (:fact-types (d/diff b a))]
    (is (= [":a/new"] (:added fts)))
    (is (= [":a/gone"] (:removed fts)))
    (is (= {":a/both" {:added ["j/Serial"] :removed []}} (:changed fts)))))

(deftest shape-skew-refusal-test
  (let [b (unit {:shape #{:nodes}})
        a (unit {:shape #{}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"differing slim shapes"
                          (d/diff b a)))
    (try
      (d/diff b a)
      (is false "diff should have refused")
      (catch clojure.lang.ExceptionInfo e
        (is (= #{:nodes} (:before-shape (ex-data e))))
        (is (= #{} (:after-shape (ex-data e))))))))

(deftest empty-against-itself-test
  (let [u (with-prods (unit {:fact-types (sorted-map ":a/T" {:ancestors #{}})
                             :edges #{["a.ns/up" "a.ns/down"]}})
            {"a.ns/p" (prod {:rhs-form "(a)"})})
        result (d/diff u u)]
    (is (empty? (get-in result [:productions :added])))
    (is (empty? (get-in result [:productions :removed])))
    (is (empty? (get-in result [:productions :changed])))
    (is (empty? (get-in result [:fact-types :added])))
    (is (empty? (get-in result [:fact-types :removed])))
    (is (empty? (get-in result [:fact-types :changed])))
    (is (empty? (get-in result [:edges :gained])))
    (is (empty? (get-in result [:edges :lost])))
    (is (empty? (get-in result [:scope :namespaces :only-before])))
    (is (empty? (get-in result [:scope :namespaces :only-after])))
    (is (str/includes? (d/->text result) "no differences"))))

;; ===========================================================================
;; text + rule detail
;; ===========================================================================

(deftest text-rendering-test
  (testing "provenance lines, count lines, and one line per name with tags"
    (let [b (with-prods (unit {:manifest {:repo "r" :variant [[:ref "a"]]
                                          :source {:sha "aaa1111" :sha-short "aaa1111"
                                                   :branch "main" :working-tree "clean"}
                                          :analysis-run {:namespaces ["a.ns"]}}})
              {"a.ns/old" (prod {})
               "a.ns/p" (prod {:rhs-form "(a)"})})
          a (with-prods (unit {:manifest {:repo "r" :variant [[:ref "b"]]
                                          :source {:sha "bbb2222" :sha-short "bbb2222"
                                                   :branch "main" :working-tree "clean"}
                                          :analysis-run {:namespaces ["a.ns"]}}})
              {"a.ns/new" (prod {})
               "a.ns/p" (prod {:rhs-form "(b)"})})
          text (d/->text (d/diff b a))]
      (is (str/includes? text "before r@ref=a aaa1111 (main, clean)"))
      (is (str/includes? text "after r@ref=b bbb2222 (main, clean)"))
      (is (str/includes? text "productions: 1 added, 1 removed, 1 changed"))
      (is (str/includes? text "+ a.ns/new"))
      (is (str/includes? text "- a.ns/old"))
      (is (str/includes? text "~ a.ns/p [rhs]")))))

(deftest rule-detail-test
  (let [b (with-prods (unit {}) {"a.ns/p" (prod {:rhs-form "(a)" :unit nil})})
        a (with-prods (unit {}) {"a.ns/p" (prod {:rhs-form "(b)" :unit "r"})})]
    (testing "before and after for each changed field, including both :rhs-form texts"
      (let [detail (d/rule-detail b a "a.ns/p")]
        (is (= "a.ns/p" (:name detail)) "exact name resolves")
        (is (= [:rhs :unit] (:tags detail)))
        (is (= "(a)" (get-in detail [:before :rhs-form])))
        (is (= "(b)" (get-in detail [:after :rhs-form])))
        (let [text (d/rule-detail-text detail)]
          (is (str/includes? text "a.ns/p [rhs unit]"))
          (is (str/includes? text "before: \"(a)\""))
          (is (str/includes? text "after:  \"(b)\"")))))
    (testing "substring matching, as the report's `rule` does"
      (is (= "a.ns/p" (:name (d/rule-detail b a "ns/p")))))
    (testing "a production on one side only"
      (let [detail (d/rule-detail (with-prods (unit {}) {}) a "a.ns/p")]
        (is (nil? (:before detail)))
        (is (str/includes? (d/rule-detail-text detail) "(only in after)"))))
    (testing "no match and ambiguous matches throw naming the candidates"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No production matches"
                            (d/rule-detail b a "zzz")))
      (let [two (with-prods (unit {}) {"a.ns/p1" (prod {}) "a.ns/p2" (prod {})})]
        (try
          (d/rule-detail two two "a.ns/p")
          (is false "rule-detail should have refused")
          (catch clojure.lang.ExceptionInfo e
            (is (= ["a.ns/p1" "a.ns/p2"] (:candidates (ex-data e))))))))))

;; ===========================================================================
;; checked-in example
;; ===========================================================================

(defn- registry-root
  []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- example-unit
  [& segments]
  (d/read-unit (str (apply io/file (registry-root) segments))))

(deftest read-productions-agrees-with-read-unit-test
  (let [dir (str (io/file (registry-root) "loan-app-ruleset"))]
    (is (= (:productions (d/read-unit dir))
           (d/read-productions dir))
        "the --rule path reads the same production values as the full path")))

(deftest checked-in-variant-diff-test
  (let [before (example-unit "loan-disposition-ruleset")
        after (example-unit "_variants" "loan-disposition-ruleset" "ref=feature%2Fnew-tax")
        result (d/diff before after)]
    (testing "the same session at a variant address: no differences, both provenance lines"
      (is (empty? (get-in result [:productions :added])))
      (is (empty? (get-in result [:productions :removed])))
      (is (empty? (get-in result [:productions :changed])))
      (is (empty? (get-in result [:fact-types :added])))
      (is (empty? (get-in result [:fact-types :removed])))
      (is (empty? (get-in result [:fact-types :changed])))
      (is (empty? (get-in result [:edges :gained])))
      (is (empty? (get-in result [:edges :lost])))
      (let [text (d/->text result)]
        (is (str/includes? text "before loan-disposition-ruleset "))
        (is (str/includes? text "after loan-disposition-ruleset@ref=feature%2Fnew-tax "))
        (is (str/includes? text "no differences"))))))

(deftest checked-in-composed-diff-test
  (let [before (example-unit "loan-app-ruleset")
        after (example-unit "composed" "loan-app-plus-disposition")
        result (d/diff before after)]
    (testing "the disposition namespace lands under :scope"
      (is (= ["clara.explorer.test.rules.loan-outcome-notices"]
             (get-in result [:scope :namespaces :only-after])))
      (is (= #{"clara.explorer.test.rules.loan-outcome-notices/find-approval-notices"
               "clara.explorer.test.rules.loan-outcome-notices/find-denial-notices"
               "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app"
               "clara.explorer.test.rules.loan-outcome-notices/notice-denied-app"}
             (set (get-in result [:scope :productions])))))
    (testing "shared productions carry :unit attribution on the composed side"
      (is (contains? (get-in result [:productions :changed])
                     "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?"))
      (is (= #{:unit}
             (get-in result [:productions :changed
                             "clara.explorer.test.rules.loan-app-rules/app-outcome-approved?"])))
      (is (empty? (get-in result [:productions :added])))
      (is (empty? (get-in result [:productions :removed]))))))
