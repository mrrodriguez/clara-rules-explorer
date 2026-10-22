(ns clara.explorer.analyze.kondo-test
  "Unit tests for the bracket scanner (`clara.explorer.analyze.kondo/scan-brackets`),
   which local-binding classification depends on to tell direct bindings,
   destructuring, and fn parameters apart."
  (:require [clojure.test :refer [deftest is testing]]
            [clara.explorer.analyze.kondo :as kondo]))

(def ^:private scan-brackets #'kondo/scan-brackets)

(deftest test-scan-brackets-basic
  (testing "open brackets stack outermost-first"
    (is (= {:stack [\( \[ \{] :past-depth2-close? false}
           (scan-brackets "([{")))))

(deftest test-scan-brackets-strings
  (testing "brackets inside strings are skipped"
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x \"()[]{}\"")))))

(deftest test-scan-brackets-char-literals
  (testing "single-char and named char literals are skipped"
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x \\(")))
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x \\newline")))))

(deftest test-scan-brackets-comments
  (testing "brackets inside line comments are skipped"
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x ; )]}\n y")))))

(deftest test-scan-brackets-regex
  (testing "brackets inside regex literals are skipped"
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x #\"(a[b\"")))))

(deftest test-scan-brackets-discard
  (testing "#_ discarded forms contribute net-zero"
    (is (= {:stack [\( \[] :past-depth2-close? false}
           (scan-brackets "(let [x #_ #_ (a) (b) y")))))

(deftest test-scan-brackets-depth2-close
  (testing "a depth-2 `[` closing marks the header/body boundary (single-arity params done)"
    (is (= {:stack [\( \( \[] :past-depth2-close? true}
           (scan-brackets "(defn f [] (with-open [r"))))
  (testing "a multi-arity arity list `(` at depth 2 does not set the flag"
    (is (= {:stack [\( \( \[] :past-depth2-close? false}
           (scan-brackets "(defn f ([x]) ([y")))))
