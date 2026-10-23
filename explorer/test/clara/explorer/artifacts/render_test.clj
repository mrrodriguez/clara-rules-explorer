(ns clara.explorer.artifacts.render-test
  "`clara.explorer.artifacts.render` — reading the persisted LHS code fields
  back into the Clojure data they name.  Each public fn gets one test per
  branch: the read failures that return the string, the type names that decode
  or become tagged literals, and the condition walk over every node kind."
  (:require
   [clara.explorer.artifacts.render :as render]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(deftest read-code-text-test
  (testing "a regex constraint round-trips to a Pattern with the source's escapes"
    (let [v (render/read-code-text "[\n(re-find #\"^A-\\d+\" sku)\n]")]
      (is (vector? v))
      (is (= 1 (count v)))
      (let [form (first v)]
        (is (= 're-find (first form)))
        (is (instance? java.util.regex.Pattern (second form)))
        (is (= "^A-\\d+" (str (second form)))))))

  (testing "an empty persisted constraint vector reads as an empty vector"
    (is (= [] (render/read-code-text "[\n]"))))

  (testing "#= throws instead of evaluating, and returns the string"
    (is (= "#=(+ 1 2)" (render/read-code-text "#=(+ 1 2)"))))

  (testing "unreadable text returns the string"
    (is (= "[" (render/read-code-text "["))))

  (testing "trailing text after one form returns the string"
    (is (= "[1] trailing" (render/read-code-text "[1] trailing"))))

  (testing "an unknown tag reads as a tagged literal, not a throw"
    (is (instance? clojure.lang.TaggedLiteral
                   (render/read-code-text "#foo/bar 1"))))

  (testing "a non-string passes through unchanged"
    (is (= [:already :data] (render/read-code-text [:already :data])))))

(deftest decode-type-name-test
  (testing "a keyword type name decodes to the keyword"
    (is (= :order/line (render/decode-type-name ":order/line"))))
  (testing "a string type name decodes to the string"
    (is (= "line" (render/decode-type-name "\"line\""))))
  (testing "a tuple type name decodes to the vector"
    (is (= [:order/line :v2] (render/decode-type-name "[:order/line :v2]"))))
  (testing "a class or var-resolved symbol name decodes to the symbol"
    (is (= 'shop.model.Line (render/decode-type-name "shop.model.Line"))))
  (testing "an unresolved symbol name becomes a tagged literal"
    (is (= "#clara.explorer/type-name \"symbol[line]\""
           (pr-str (render/decode-type-name "symbol[line]")))))
  (testing "an array class name becomes a tagged literal"
    (is (= "#clara.explorer/type-name \"[Ljava.lang.String;\""
           (pr-str (render/decode-type-name "[Ljava.lang.String;")))))
  (testing "an unresolved symbol and a string type with the same text print differently"
    (is (not= (pr-str (render/decode-type-name "symbol[line]"))
              (pr-str (render/decode-type-name "\"symbol[line]\"")))))
  (testing "a non-string passes through unchanged"
    (is (= :order/line (render/decode-type-name :order/line)))))

(deftest ->display-lhs-test
  (testing "a :not group renders its children's :type and :constraints"
    (let [out (render/->display-lhs
               [{:condition-type :not
                 :children [{:type ":a/one"
                             :constraints "[(= ?x this)]"
                             :bindings {:binding-keys [] :new-bindings [:?x]}}]
                 :bindings {:binding-keys [] :new-bindings []}}])]
      (is (= :not (get-in out [0 :condition-type])))
      (is (= :a/one (get-in out [0 :children 0 :type])))
      (is (= '[(= ?x this)] (get-in out [0 :children 0 :constraints])))
      (is (= {:binding-keys [] :new-bindings []} (get-in out [0 :bindings])))))

  (testing "an accumulator renders :form and its :from subtree"
    (let [out (render/->display-lhs
               [{:accumulator {:form "(clara.rules.accumulators/all)"
                               :some-initial-value? true}
                 :from {:type ":a/one" :constraints "[(= ?x this)]"}
                 :result-binding :?xs
                 :bindings {:binding-keys [] :new-bindings [:?x]}}])]
      (is (= '(clara.rules.accumulators/all) (get-in out [0 :accumulator :form])))
      (is (true? (get-in out [0 :accumulator :some-initial-value?])))
      (is (= :a/one (get-in out [0 :from :type])))
      (is (= '[(= ?x this)] (get-in out [0 :from :constraints])))
      (is (= :?xs (get-in out [0 :result-binding])))))

  (testing "a :test condition has no :type, so only :constraints is read"
    (let [out (render/->display-lhs
               [{:constraints "[(= ?x ?y)]"
                 :bindings {:binding-keys [:?x] :new-bindings [:?y]}}])]
      (is (nil? (:type (first out))))
      (is (= '[(= ?x ?y)] (:constraints (first out))))
      (is (= {:binding-keys [:?x] :new-bindings [:?y]} (:bindings (first out))))))

  (testing "an empty :lhs renders to an empty vector"
    (is (= [] (render/->display-lhs [])))))
