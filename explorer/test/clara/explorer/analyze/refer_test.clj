(ns clara.explorer.analyze.refer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clara.explorer.analyze.refer :as refer]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

;; ---------------------------------------------------------------------------
;; Loaded target namespaces with distinct publics (mimics clara.rules vs a
;; facts namespace), and consumer namespaces that refer both wholesale in
;; each require order.
;; ---------------------------------------------------------------------------

(def ^:private a-sym 'fake.refer.a)
(def ^:private b-sym 'fake.refer.b)

(create-ns a-sym)
(binding [*ns* (the-ns a-sym)]
  (eval '(do (clojure.core/ns fake.refer.a)
             (defn insert! [x] x))))

(create-ns b-sym)
(binding [*ns* (the-ns b-sym)]
  (eval '(do (clojure.core/ns fake.refer.b)
             (defn ->fact [t m] m))))

(def ^:private from-ab 'fake.refer.consumer-ab)
(create-ns from-ab)
(binding [*ns* (the-ns from-ab)]
  (eval '(clojure.core/ns fake.refer.consumer-ab
           (:require [fake.refer.a :refer :all]
                     [fake.refer.b :refer :all]))))

(def ^:private from-ba 'fake.refer.consumer-ba)
(create-ns from-ba)
(binding [*ns* (the-ns from-ba)]
  (eval '(clojure.core/ns fake.refer.consumer-ba
           (:require [fake.refer.b :refer :all]
                     [fake.refer.a :refer :all]))))

(deftest test-reattributes-to-second-refer-all-ns
  (testing "a name only in the second :refer :all ns is rewritten (defect table row 1)"
    (is (= [{:from from-ab :to b-sym :name '->fact}]
           (refer/re-attribute-refer-all-usages
            [{:from from-ab :to a-sym :name '->fact}])))))

(deftest test-reattributes-to-second-refer-all-ns-reversed
  (testing "reversed require order: the name only in the second ns is rewritten (row 2)"
    (is (= [{:from from-ba :to a-sym :name 'insert!}]
           (refer/re-attribute-refer-all-usages
            [{:from from-ba :to b-sym :name 'insert!}])))))

(deftest test-leaves-correct-attribution-alone
  (testing "a name the reported :to namespace actually provides stays put"
    (is (= [{:from from-ab :to a-sym :name 'insert!}]
           (refer/re-attribute-refer-all-usages
            [{:from from-ab :to a-sym :name 'insert!}])))))

(deftest test-leaves-unknown-namespace-token-alone
  (testing "keyword :to (unknown-namespace token) is untouched"
    (is (= [{:from from-ab :to :clj-kondo/unknown-namespace :name '->fact}]
           (refer/re-attribute-refer-all-usages
            [{:from from-ab :to :clj-kondo/unknown-namespace :name '->fact}])))))

(deftest test-leaves-unresolvable-alone
  (testing "a name no refer-all'd namespace provides is untouched"
    (is (= [{:from from-ab :to a-sym :name 'no-such-name}]
           (refer/re-attribute-refer-all-usages
            [{:from from-ab :to a-sym :name 'no-such-name}])))))

(deftest test-leaves-unloaded-from-ns-alone
  (testing "a :from namespace with no live ns passes through untouched"
    (is (= [{:from 'fake.refer.not-loaded :to a-sym :name '->fact}]
           (refer/re-attribute-refer-all-usages
            [{:from 'fake.refer.not-loaded :to a-sym :name '->fact}])))))
