(ns clara.explorer.utils-test
  (:require [clara.explorer.utils :as utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest test-canonicalize-gensyms--patterns
  (testing "positional #() args keep their positional index"
    (is (= 'p2__0# (-> "p2__999#" symbol utils/canonicalize-gensyms)))
    (is (= 'p1__0# (-> "p1__7#" symbol utils/canonicalize-gensyms))))

  (testing "#() rest arg"
    (is (= 'rest__0# (-> "rest__42#" symbol utils/canonicalize-gensyms))))

  (testing "syntax-quote auto-gensym keeps its user-written prefix"
    (is (= 'foo__0__auto__ (-> "foo__42__auto__" symbol utils/canonicalize-gensyms))))

  (testing "(gensym) default"
    (is (= 'G__0 (-> "G__42" symbol utils/canonicalize-gensyms))))

  (testing "one shared ordinal counter across all shapes"
    (is (= '(p1__0# rest__1# foo__2__auto__ G__3)
           (-> [(symbol "p1__9#")
                (symbol "rest__9#")
                (symbol "foo__9__auto__")
                (symbol "G__9")]
               utils/canonicalize-gensyms)))))

(deftest test-canonicalize-gensyms--read-forms
  (testing "positional #() args"
    (is (= '(fn* [p1__0# p2__1#] (do p1__0# p2__1#))
           (-> "#(do %1 %2)" read-string utils/canonicalize-gensyms))))

  (testing "rest arg in #()"
    (is (= '(fn* [& rest__0#] (apply + rest__0#))
           (-> "#(apply + %&)" read-string utils/canonicalize-gensyms))))

  (testing "nested #() inside a fn"
    (is (= '(fn [x] ((fn* [p1__0#] (inc p1__0#)) x))
           (-> "(fn [x] (#(inc %) x))" read-string utils/canonicalize-gensyms))))

  (testing "two #() literals in one form get distinct ordinals"
    (let [out (-> "(do #(inc %) #(dec %))" read-string utils/canonicalize-gensyms)]
      (is (-> out pr-str (str/includes? "p1__0#")))
      (is (-> out pr-str (str/includes? "p1__1#")))))

  (testing "canonicalization is idempotent in value"
    (let [once (-> "#(do %1 %2)" read-string utils/canonicalize-gensyms)]
      (is (= once (utils/canonicalize-gensyms once))))))

(deftest test-canonicalize-gensyms--no-gensym-forms
  (testing "a form with no gensyms is returned identical"
    (let [form '(a [b c] {:d e})]
      (is (identical? form (utils/canonicalize-gensyms form)))))

  (testing "non-gensym symbols are untouched, metadata included"
    (let [sym (with-meta 'plain-symbol {:line 7})
          out (utils/canonicalize-gensyms sym)]
      (is (identical? sym out))
      (is (= {:line 7} (meta out)))))

  (testing "namespaced symbols matching a shape in name only are left alone"
    (let [sym 'some.ns/G__42]
      (is (identical? sym (utils/canonicalize-gensyms sym))))))

(deftest test-canonicalize-gensyms--metadata
  (testing "metadata on a renamed symbol is preserved"
    (let [sym (-> "p1__999#" symbol (with-meta {:line 3 :column 4}))
          out (utils/canonicalize-gensyms [sym])]
      (is (= 'p1__0# (first out)))
      (is (= {:line 3 :column 4} (-> out first meta))))))
