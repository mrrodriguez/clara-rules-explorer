(ns clara.explorer.analyze.refer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clara.explorer.analyze.refer :as refer]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

;; ---------------------------------------------------------------------------
;; Loaded target namespaces with distinct publics (mimics clara.rules vs a
;; facts namespace), and consumer namespaces that refer them wholesale /
;; aliased in each require order.
;; ---------------------------------------------------------------------------

(defn- define-fake-ns!
  "Creates `ns-sym` and evals `body` inside it."
  [ns-sym body]
  (create-ns ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (eval body)))

(def ^:private a-sym 'fake.refer.a)
(def ^:private b-sym 'fake.refer.b)

(define-fake-ns! a-sym
  '(do (clojure.core/ns fake.refer.a)
       (defn insert! [x] x)))

(define-fake-ns! b-sym
  '(do (clojure.core/ns fake.refer.b)
       (defn ->fact [t m] m)))

(def ^:private from-ab 'fake.refer.consumer-ab)
(define-fake-ns! from-ab
  '(clojure.core/ns fake.refer.consumer-ab
     (:require [fake.refer.a :refer :all]
               [fake.refer.b :refer :all])))

(def ^:private from-ba 'fake.refer.consumer-ba)
(define-fake-ns! from-ba
  '(clojure.core/ns fake.refer.consumer-ba
     (:require [fake.refer.b :refer :all]
               [fake.refer.a :refer :all])))

;; ---------------------------------------------------------------------------
;; Usage-shape builders. kondo tells bare / aliased / fully-qualified /
;; renamed usages apart by `:alias` and the `:name-col`/`:name-end-col` span:
;; a bare usage has no `:alias` and a span exactly the symbol name; qualified
;; tokens are longer (they include `ns/`), and a `:rename`d usage spans the
;; local symbol while `:name` is the resolved original.
;; ---------------------------------------------------------------------------

(defn- ->bare-usage
  "A bare `:var-usage` for `sym`: no alias, name span = symbol length."
  [from to sym]
  {:from from :to to :name sym
   :name-col 1 :name-end-col (inc (count (name sym)))})

(defn- ->aliased-usage
  "An aliased `:var-usage` (`alias/sym`): `:alias` present, name span =
   `alias/sym` length."
  [from to sym alias]
  {:from from :to to :name sym :alias alias
   :name-col 1 :name-end-col (inc (count (str (name alias) "/" (name sym))))})

(defn- ->qualified-usage
  "A fully-qualified `:var-usage` (`ns-sym/sym`): no alias, name span =
   `ns-sym/sym` length."
  [from to sym ns-sym]
  {:from from :to to :name sym
   :name-col 1 :name-end-col (inc (count (str (name ns-sym) "/" (name sym))))})

;; ---------------------------------------------------------------------------
;; Aliased-usage fixture: an aliased ns that refers only `defrule`, alongside
;; a refer-all'd facts ns that exports `insert!`. `(r/insert! …)` must keep
;; its `:to` (the aliased ns) even though the facts ns also exports `insert!`.
;; ---------------------------------------------------------------------------

(def ^:private rules-sym 'fake.refer.rules)
(def ^:private facts-sym 'fake.refer.facts)

(define-fake-ns! rules-sym
  '(do (clojure.core/ns fake.refer.rules)
       (defn defrule [& _] nil)
       (defn insert! [x] x)))

(define-fake-ns! facts-sym
  '(do (clojure.core/ns fake.refer.facts)
       (defn insert! [x] x)))

(def ^:private from-alias 'fake.refer.consumer-alias)
(define-fake-ns! from-alias
  '(clojure.core/ns fake.refer.consumer-alias
     (:require [fake.refer.rules :as r :refer [defrule]]
               [fake.refer.facts :refer :all])))

;; ---------------------------------------------------------------------------
;; Refer-all collision fixtures: two namespaces exporting the same name. The
;; live `ns-refers` table records only the runtime winner, so re-attribution
;; keys on the winner and the candidate set is provably 0-or-1.
;; ---------------------------------------------------------------------------

(def ^:private collision-a-sym 'fake.refer.collision-a)
(def ^:private collision-b-sym 'fake.refer.collision-b)

(define-fake-ns! collision-a-sym
  '(do (clojure.core/ns fake.refer.collision-a)
       (defn shared [x] x)
       (defn a-only [x] x)))

(define-fake-ns! collision-b-sym
  '(do (clojure.core/ns fake.refer.collision-b)
       (defn shared [x] x)
       (defn b-only [x] x)))

(def ^:private from-collision 'fake.refer.consumer-collision)
(define-fake-ns! from-collision
  '(clojure.core/ns fake.refer.consumer-collision
     (:require [fake.refer.collision-a :refer :all]
               [fake.refer.collision-b :refer :all])))

(defn- get-collision-winner-sym
  "The namespace the live consumer's `ns-refers` maps `shared` to (the runtime
   winner among the two refer-all'd namespaces exporting it)."
  []
  (-> (ns-resolve from-collision 'shared) meta :ns ns-name))

;; ---------------------------------------------------------------------------
;; `:refer :all :rename` fixture: the local symbol differs from the resolved
;; name, so the usage is never bare.
;; ---------------------------------------------------------------------------

(def ^:private from-rename 'fake.refer.consumer-rename)
(define-fake-ns! from-rename
  '(clojure.core/ns fake.refer.consumer-rename
     (:require [fake.refer.b :refer :all :rename {->fact build}])))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-reattributes-bare-to-second-refer-all-ns
  (testing "a bare name only in the second :refer :all ns is rewritten"
    (is (= [{:from from-ab :to b-sym :name '->fact
             :name-col 1 :name-end-col 7}]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage from-ab a-sym '->fact)])))))

(deftest test-reattributes-bare-reversed
  (testing "reversed require order: the bare name only in the second ns is rewritten"
    (is (= [{:from from-ba :to a-sym :name 'insert!
             :name-col 1 :name-end-col 8}]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage from-ba b-sym 'insert!)])))))

(deftest test-leaves-correct-bare-attribution-alone
  (testing "a bare name the reported :to namespace actually provides stays put"
    (is (= [(->bare-usage from-ab a-sym 'insert!)]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage from-ab a-sym 'insert!)])))))

(deftest test-leaves-aliased-usage-alone
  (testing "an aliased usage is never rewritten, even when a refer-all'd ns exports the name"
    (let [usage (->aliased-usage from-alias rules-sym 'insert! 'r)]
      (is (= [usage]
             (refer/re-attribute-refer-all-usages [usage]))
          "`r/insert!` resolves to the aliased ns, not the refer-all'd facts ns"))))

(deftest test-leaves-fully-qualified-usage-alone
  (testing "a fully-qualified usage is never rewritten, even when the reported :to ns does not refer the name"
    (let [usage (->qualified-usage from-ab b-sym 'insert! 'fake.refer.a)]
      (is (= [usage]
             (refer/re-attribute-refer-all-usages [usage]))
          "`fake.refer.a/insert!` is a qualified token — its span is longer than `insert!`"))))

(deftest test-rewrites-collision-to-runtime-winner
  (let [winner (get-collision-winner-sym)
        loser (if (= winner collision-a-sym) collision-b-sym collision-a-sym)]
    (testing "a bare name exported by two refer-all'd namespaces is rewritten to the runtime winner"
      (is (= [{:from from-collision :to winner :name 'shared
               :name-col 1 :name-end-col 7}]
             (refer/re-attribute-refer-all-usages
              [(->bare-usage from-collision loser 'shared)]))))
    (testing "the already-correct (winner) attribution is left alone"
      (is (= [(->bare-usage from-collision winner 'shared)]
             (refer/re-attribute-refer-all-usages
              [(->bare-usage from-collision winner 'shared)]))))))

(deftest test-resolves-bare-unknown-namespace
  (testing "a bare usage kondo could not resolve is rewritten to the live refer winner"
    (is (= [{:from from-ab :to b-sym :name '->fact
             :name-col 1 :name-end-col 7}]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage from-ab :clj-kondo/unknown-namespace '->fact)])))))

(deftest test-leaves-unresolvable-alone
  (testing "a bare name no referred namespace provides is untouched"
    (is (= [(->bare-usage from-ab a-sym 'no-such-name)]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage from-ab a-sym 'no-such-name)])))))

(deftest test-leaves-unloaded-from-ns-alone
  (testing "a :from namespace with no live ns passes through untouched"
    (is (= [(->bare-usage 'fake.refer.not-loaded a-sym '->fact)]
           (refer/re-attribute-refer-all-usages
            [(->bare-usage 'fake.refer.not-loaded a-sym '->fact)])))))

(deftest test-leaves-renamed-refer-all-alone
  (testing "a :rename'd usage is not bare: the source span is the local symbol, `:name` the resolved original"
    (is (= [{:from from-rename :to b-sym :name '->fact
             :name-col 1 :name-end-col 6}]
           (refer/re-attribute-refer-all-usages
            [{:from from-rename :to b-sym :name '->fact
              :name-col 1 :name-end-col 6}]))
        "`build` (5 chars) ≠ `->fact` (6 chars), so the usage stays put")))
