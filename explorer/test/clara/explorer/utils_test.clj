(ns clara.explorer.utils-test
  (:require [clara.explorer.utils :as utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest test-canonicalize-minted-names--patterns
  (testing "positional #() args keep their positional index"
    (is (= 'p2__0# (-> "p2__999#" symbol utils/canonicalize-minted-names)))
    (is (= 'p1__0# (-> "p1__7#" symbol utils/canonicalize-minted-names))))

  (testing "#() rest arg"
    (is (= 'rest__0# (-> "rest__42#" symbol utils/canonicalize-minted-names))))

  (testing "syntax-quote auto-gensym keeps its user-written prefix"
    (is (= 'foo__0__auto__ (-> "foo__42__auto__" symbol utils/canonicalize-minted-names))))

  (testing "(gensym) default"
    (is (= 'G__0 (-> "G__42" symbol utils/canonicalize-minted-names))))

  (testing "one shared ordinal counter across all shapes"
    (is (= '(p1__0# rest__1# foo__2__auto__ G__3)
           (-> [(symbol "p1__9#")
                (symbol "rest__9#")
                (symbol "foo__9__auto__")
                (symbol "G__9")]
               utils/canonicalize-minted-names)))))

(deftest test-canonicalize-minted-names--digest-suffix
  (testing "a digest-suffixed local canonicalizes to prefix + zero-padded ordinal"
    (is (= 'monthly-income00000000000000000000000000000000
           (-> "monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f"
               symbol
               utils/canonicalize-minted-names))))

  (testing "a prefix ending in a hex letter keeps its last letter"
    (is (= 'pay-rate00000000000000000000000000000000
           (-> "pay-rate5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f"
               symbol
               utils/canonicalize-minted-names))))

  (testing "31 hex characters do not match"
    ;; The prefix ends in `X`, not a hex digit, so the last 32 characters
    ;; cannot all be hex. (With a prefix ending in a hex letter the run
    ;; would extend left and still match — that is the anchored shape.)
    (let [sym (-> "pay-rateX5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4" symbol)]
      (is (identical? sym (utils/canonicalize-minted-names sym)))))

  (testing "a bare 32-hex name with no prefix does not match"
    (let [sym (-> "5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f" symbol)]
      (is (identical? sym (utils/canonicalize-minted-names sym)))))

  (testing "uppercase hex does not match"
    (let [sym (-> "monthly-income5F0C1A7E9B2D4C3A8E6F1B0D9C7A2E4F" symbol)]
      (is (identical? sym (utils/canonicalize-minted-names sym)))))

  (testing "qualified symbols are left alone"
    (let [sym 'some.ns/monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f]
      (is (identical? sym (utils/canonicalize-minted-names sym)))))

  (testing "binding and use rename consistently"
    (is (= '(let [monthly-income00000000000000000000000000000000 (var monthly-income)]
              monthly-income00000000000000000000000000000000)
           (utils/canonicalize-minted-names
            '(let [monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f (var monthly-income)]
               monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f)))))

  (testing "two distinct digests get distinct ordinals"
    (is (= '[monthly-income00000000000000000000000000000000
             monthly-income00000000000000000000000000000001]
           (utils/canonicalize-minted-names
            '[monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f
              monthly-income0b9e8d7c6a5f4e3d2c1b0a9f8e7d6c5b]))))

  (testing "a mix with reader gensyms shares one counter"
    (is (= '[p1__0# monthly-income00000000000000000000000000000001]
           (utils/canonicalize-minted-names
            '[p1__9# monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f]))))

  (testing "canonicalization is idempotent in value"
    (let [once (utils/canonicalize-minted-names
                '[monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f
                  monthly-income0b9e8d7c6a5f4e3d2c1b0a9f8e7d6c5b])]
      (is (= once (utils/canonicalize-minted-names once))))))

(deftest test-canonicalize-minted-names--read-forms
  (testing "positional #() args"
    (is (= '(fn* [p1__0# p2__1#] (do p1__0# p2__1#))
           (-> "#(do %1 %2)" read-string utils/canonicalize-minted-names))))

  (testing "rest arg in #()"
    (is (= '(fn* [& rest__0#] (apply + rest__0#))
           (-> "#(apply + %&)" read-string utils/canonicalize-minted-names))))

  (testing "nested #() inside a fn"
    (is (= '(fn [x] ((fn* [p1__0#] (inc p1__0#)) x))
           (-> "(fn [x] (#(inc %) x))" read-string utils/canonicalize-minted-names))))

  (testing "two #() literals in one form get distinct ordinals"
    (let [out (-> "(do #(inc %) #(dec %))" read-string utils/canonicalize-minted-names)]
      (is (-> out pr-str (str/includes? "p1__0#")))
      (is (-> out pr-str (str/includes? "p1__1#")))))

  (testing "canonicalization is idempotent in value"
    (let [once (-> "#(do %1 %2)" read-string utils/canonicalize-minted-names)]
      (is (= once (utils/canonicalize-minted-names once))))))

(deftest test-canonicalize-minted-names--no-gensym-forms
  (testing "a form with no gensyms is returned identical"
    (let [form '(a [b c] {:d e})]
      (is (identical? form (utils/canonicalize-minted-names form)))))

  (testing "non-gensym symbols are untouched, metadata included"
    (let [sym (with-meta 'plain-symbol {:line 7})
          out (utils/canonicalize-minted-names sym)]
      (is (identical? sym out))
      (is (= {:line 7} (meta out)))))

  (testing "namespaced symbols matching a shape in name only are left alone"
    (let [sym 'some.ns/G__42]
      (is (identical? sym (utils/canonicalize-minted-names sym))))))

(deftest test-canonicalize-minted-names--metadata
  (testing "metadata on a renamed symbol is preserved"
    (let [sym (-> "p1__999#" symbol (with-meta {:line 3 :column 4}))
          out (utils/canonicalize-minted-names [sym])]
      (is (= 'p1__0# (first out)))
      (is (= {:line 3 :column 4} (-> out first meta))))))
