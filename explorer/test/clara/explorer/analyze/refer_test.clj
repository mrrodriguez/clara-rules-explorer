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

;; Two refer-all'd namespaces exporting the *same* name — the live `ns-refers`
;; table records only the runtime winner, so re-attribution keys on the winner
;; and the candidate set is provably 0-or-1.
(def ^:private collision-a-sym 'fake.refer.collision-a)
(def ^:private collision-b-sym 'fake.refer.collision-b)

(create-ns collision-a-sym)
(binding [*ns* (the-ns collision-a-sym)]
  (eval '(do (clojure.core/ns fake.refer.collision-a)
             (defn shared [x] x)
             (defn a-only [x] x))))

(create-ns collision-b-sym)
(binding [*ns* (the-ns collision-b-sym)]
  (eval '(do (clojure.core/ns fake.refer.collision-b)
             (defn shared [x] x)
             (defn b-only [x] x))))

(def ^:private from-collision 'fake.refer.consumer-collision)
(create-ns from-collision)
(binding [*ns* (the-ns from-collision)]
  (eval '(clojure.core/ns fake.refer.consumer-collision
           (:require [fake.refer.collision-a :refer :all]
                     [fake.refer.collision-b :refer :all]))))

(defn- collision-winner-sym
  "The namespace the live consumer's `ns-refers` maps `shared` to (the runtime
   winner among the two refer-all'd namespaces exporting it)."
  []
  (-> (ns-resolve from-collision 'shared) meta :ns ns-name))

(deftest test-rewrites-collision-to-runtime-winner
  (let [winner (collision-winner-sym)
        loser (if (= winner collision-a-sym) collision-b-sym collision-a-sym)]
    (testing "a name exported by two refer-all'd namespaces is rewritten to the runtime winner"
      (is (= [{:from from-collision :to winner :name 'shared}]
             (refer/re-attribute-refer-all-usages
              [{:from from-collision :to loser :name 'shared}]))))
    (testing "the already-correct (winner) attribution is left alone"
      (is (= [{:from from-collision :to winner :name 'shared}]
             (refer/re-attribute-refer-all-usages
              [{:from from-collision :to winner :name 'shared}]))))))

;; Residual corner: the loser namespace exports *only* the colliding name, so
;; it contributes no refers at all and is absent from `refer-sets`.  A usage
;; kondo misattributes to it passes through uncorrected.
(def ^:private corner-loser-sym 'fake.refer.corner-loser)
(def ^:private corner-winner-sym 'fake.refer.corner-winner)

(create-ns corner-loser-sym)
(binding [*ns* (the-ns corner-loser-sym)]
  (eval '(do (clojure.core/ns fake.refer.corner-loser)
             (defn shared [x] x))))

(create-ns corner-winner-sym)
(binding [*ns* (the-ns corner-winner-sym)]
  (eval '(do (clojure.core/ns fake.refer.corner-winner)
             (defn shared [x] x)
             (defn winner-only [x] x))))

(def ^:private from-corner 'fake.refer.consumer-corner)
(create-ns from-corner)
(binding [*ns* (the-ns from-corner)]
  (eval '(clojure.core/ns fake.refer.consumer-corner
           (:require [fake.refer.corner-loser :refer :all]
                     [fake.refer.corner-winner :refer :all]))))

(deftest test-leaves-corner-loser-usage-alone
  (testing "a usage misattributed to a refer-all'd ns exporting only the colliding name
            passes through (documented residual corner)"
    (is (= [{:from from-corner :to corner-loser-sym :name 'shared}]
           (refer/re-attribute-refer-all-usages
            [{:from from-corner :to corner-loser-sym :name 'shared}])))))

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
