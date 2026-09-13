(ns clara.server.tools.graph.analyze.synth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clara.server.tools.graph.analyze.synth :as synth]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(def ^:private shape-ns-sym 'fake.synth-shape)

;; Fake ns exercising every clause axis: alias+refer, alias-only, explicit
;; non-default `java.lang` import, `:refer-clojure` exclude+rename.
(create-ns shape-ns-sym)
(binding [*ns* (the-ns shape-ns-sym)]
  (eval '(do (clojure.core/ns fake.synth-shape
               (:require [clojure.set :as s :refer [union difference]]
                         [clojure.string :as str])
               (:import java.lang.StackWalker java.util.ArrayList)
               (:refer-clojure :exclude [read-string]
                               :rename {map my-map})))))

(deftest test-synth-clauses
  (testing "require and import clauses project the live ns mappings"
    (let [nsobj (the-ns shape-ns-sym)]
      (is (= '[[clojure.set :as s]
               [clojure.set :refer [difference union]]
               [clojure.string :as str]]
             (synth/->require-clauses nsobj))
          "one ns yields two clauses (alias + refer), sorted by target")
      (is (= '[[java.lang StackWalker] [java.util ArrayList]]
             (synth/->import-clauses nsobj))
          "package-grouped imports, non-default java.lang classes preserved")
      (is (= [] (synth/->unmapped-default-imports nsobj)))))
  (testing "alias-only ns yields alias clauses"
    (is (= '[[clara.rules :as r]
             [clara.rules.accumulators :as acc]
             [clara.server.tools.graph.rules.helpers :as h]
             [clara.server.tools.graph.rules.loan-app-facts :as laf]]
           (synth/->require-clauses
            (the-ns 'clara.server.tools.graph.rules.loan-doc-rules))))))

(deftest test-reconstruct-ns-source
  (testing "emits require, import, and refer-clojure clauses"
    (let [src (synth/reconstruct-ns-source shape-ns-sym)]
      (is (str/includes? src "(:require [clojure.set :as s]"))
      (is (str/includes? src "[clojure.set :refer [difference union]]"))
      (is (str/includes? src "(:import [java.lang StackWalker] [java.util ArrayList])"))
      (is (str/includes? src "(:refer-clojure"))))
  (testing "rename mapping survives a round trip"
    (let [src (synth/reconstruct-ns-source shape-ns-sym)]
      (is (str/includes? src ":rename {map my-map}")
          "emission inverts to the {orig local} shape the ns spec accepts")
      (is (nil? (eval (read-string src))) "round trip must not throw")
      (let [mappings (ns-map (the-ns shape-ns-sym))]
        (is (= #'clojure.core/map (get mappings 'my-map)))
        (is (not (contains? mappings 'map)) "renamed var vacates its old name")
        (is (not (contains? mappings 'read-string)) "excluded var stays out"))))
  (testing "refer-only ns survives a round trip"
    (let [ref-ns-sym 'fake.synth-refers]
      (create-ns ref-ns-sym)
      (binding [*ns* (the-ns ref-ns-sym)]
        (eval '(do (clojure.core/ns fake.synth-refers
                     (:require [clojure.set :refer [union difference]])))))
      (let [src (synth/reconstruct-ns-source ref-ns-sym)
            _ (eval (read-string src))
            evaled-nsobj (the-ns ref-ns-sym)]
        (is (contains? (set (keys (ns-refers evaled-nsobj))) 'union))
        (is (contains? (set (keys (ns-refers evaled-nsobj))) 'difference))
        (is (str/includes? src ":refer [difference union]"))))))
