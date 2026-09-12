(ns clara.server.tools.graph.ns-deps-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [clara.server.tools.graph.ns-deps :as ns-deps]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(def ^:private shape-ns-sym 'fake.ns-deps-shape)

;; Fake ns exercising every data axis: alias+refer, alias-only, explicit
;; non-default `java.lang` import, `:refer-clojure` exclude+rename.
(create-ns shape-ns-sym)
(binding [*ns* (the-ns shape-ns-sym)]
  (eval '(do (clojure.core/ns fake.ns-deps-shape
               (:require [clojure.set :as s :refer [union difference]]
                         [clojure.string :as str])
               (:import java.lang.StackWalker java.util.ArrayList)
               (:refer-clojure :exclude [read-string]
                               :rename {map my-map})))))

(deftest test-ns-data-fns--alias-refer-import-exclude-rename
  (testing "data fns decompose the live ns without overlap or loss"
    (let [nsobj (the-ns shape-ns-sym)]
      (is (= [{:ns-name-sym 'clojure.set
               :refers '[difference union]}]
             (ns-deps/->ns-required nsobj))
          "one entry per referred ns; clojure.core excluded; alias-only clojure.string absent")
      (is (= [{:ns-name-sym 'clojure.set :alias-sym 's}
              {:ns-name-sym 'clojure.string :alias-sym 'str}]
             (ns-deps/->ns-aliases nsobj))
          "aliases sorted by alias-sym, independent of refers")
      (is (= '[java.lang.StackWalker java.util.ArrayList]
             (ns-deps/->ns-imports nsobj))
          "flat FQ class-name syms, sorted — incl. non-default java.lang.StackWalker")
      (is (= {:excludes '[read-string]
              :renames '{my-map clojure.core/map}}
             (ns-deps/->ns-refer-clojure nsobj))
          "kebabed keys, plain symbol values (JSON-safe)")
      (is (= [] (ns-deps/->ns-unmapped-default-imports nsobj))
          "no default imports unmapped"))))

(deftest test-loan-doc-rules-data
  (testing "representative real ns: aliases only, fixture imports present"
    (let [nsobj (the-ns 'clara.server.tools.graph.rules.loan-doc-rules)]
      (is (= [] (ns-deps/->ns-required nsobj))
          "no refers — requires are alias-only")
      (is (match? [{:ns-name-sym 'clara.rules.accumulators :alias-sym 'acc}
                   {:ns-name-sym 'clara.server.tools.graph.rules.helpers :alias-sym 'h}
                   {:ns-name-sym 'clara.server.tools.graph.rules.loan-app-facts
                    :alias-sym 'laf}
                   {:ns-name-sym 'clara.rules :alias-sym 'r}]
                  (ns-deps/->ns-aliases nsobj)))
      (let [imports (set (ns-deps/->ns-imports nsobj))]
        (is (contains? imports
                       'clara.server.tools.graph.rules.loan_app_facts.Application))
        (is (contains? imports
                       'clara.server.tools.graph.rules.loan_app_facts.DocumentCheck))
        (is (not (contains? imports 'java.lang.String))
            "default imports stay excluded")))))

(deftest test-header-parsing--injected-sources
  (testing "prefix lists, :as/:refer/:rename, both :import shapes, :refer-clojure"
    (let [sources {'fake.hdr-a (str "(ns fake.hdr-a "
                                    "(:require [clojure.set :as s :refer [union difference]] "
                                    "(clojure [string :as str2 :refer [join]] [edn :refer :all]) "
                                    "[prefixed :as p :refer [a] :rename {a b}] plain.lib) "
                                    "(:import (java.util ArrayList HashMap) java.lang.StackWalker) "
                                    "(:refer-clojure :exclude [read-string] :rename {map my-map}))")}
          deps (ns-deps/->ns-deps {:ns-syms ['fake.hdr-a]
                                   :base-source-fn (fn [ns-sym] (get sources ns-sym))})
          entry (get deps 'fake.hdr-a)]
      (is (sorted? deps))
      (is (= [{:ns-name-sym 'clojure.edn :refers []}
              {:ns-name-sym 'clojure.set :refers '[difference union]}
              {:ns-name-sym 'clojure.string :refers '[join]}
              {:ns-name-sym 'prefixed :refers '[b]}]
             (:require entry)))
      (is (= [{:ns-name-sym 'prefixed :alias-sym 'p}
              {:ns-name-sym 'clojure.set :alias-sym 's}
              {:ns-name-sym 'clojure.string :alias-sym 'str2}]
             (:aliases entry)))
      (is (= '[java.lang.StackWalker java.util.ArrayList java.util.HashMap]
             (:imports entry)))
      (is (= {:excludes '[read-string]
              :renames '{my-map clojure.core/map}}
             (:refer-clojure entry)))
      (is (= [] (:unmapped-default-imports entry))
          "no live ns — unmapped defaults to empty")))
  (testing ":refer :all, :use, and :refer-clojure :only"
    (let [sources {'fake.hdr-b (str "(ns fake.hdr-b "
                                    "(:require [clojure.set :refer :all]) "
                                    "(:use clojure.walk [clojure.string :only [join]]) "
                                    "(:refer-clojure :only [map filter]))")}
          entry (get (ns-deps/->ns-deps {:ns-syms ['fake.hdr-b]
                                         :base-source-fn (fn [ns-sym] (get sources ns-sym))})
                     'fake.hdr-b)]
      (is (= [{:ns-name-sym 'clojure.set :refers []}
              {:ns-name-sym 'clojure.string :refers '[join]}
              {:ns-name-sym 'clojure.walk :refers []}]
             (:require entry))
          ":refer :all and bare :use yield entries with unknown (empty) refers")
      (let [{:keys [excludes renames]} (:refer-clojure entry)]
        (is (not (contains? (set excludes) 'map)))
        (is (not (contains? (set excludes) 'filter)))
        (is (contains? (set excludes) 'read-string))
        (is (= excludes (vec (sort excludes))))
        (is (= {} renames))))))

(deftest test-source-runtime-agreement--loan-doc-rules
  (testing "parsed header agrees with the runtime entry modulo own-ns imports"
    (let [ns-sym 'clara.server.tools.graph.rules.loan-doc-rules
          parsed (get (ns-deps/->ns-deps {:ns-syms [ns-sym]}) ns-sym)
          runtime (ns-deps/->ns-deps-entry (the-ns ns-sym))]
      (is (= '[clara.server.tools.graph.rules.loan_app_facts.AllGivenDocuments
               clara.server.tools.graph.rules.loan_app_facts.AllRequiredDocuments
               clara.server.tools.graph.rules.loan_app_facts.Application
               clara.server.tools.graph.rules.loan_app_facts.DocumentCheck
               clara.server.tools.graph.rules.loan_app_facts.GivenDocument
               clara.server.tools.graph.rules.loan_app_facts.RequiredDocument]
             (:imports parsed))
          "the classpath-source path was actually exercised")
      (is (= (dissoc runtime :imports) (dissoc parsed :imports)))
      (let [extra (set/difference (set (:imports runtime)) (set (:imports parsed)))]
        (is (seq extra))
        (is (every? #(str/starts-with? (str %)
                                       "clara.server.tools.graph.rules.loan_doc_rules.")
                    extra)
            "runtime-only imports are the ns's own auto-imported record classes")))))

(deftest test-missing-everywhere--empty-entry-plus-tap
  (testing "ns with neither source nor live ns yields an empty entry and a tap> report"
    (let [tapped (atom [])
          tap-fn (fn [x] (swap! tapped conj x))]
      (add-tap tap-fn)
      (try
        (let [deps (ns-deps/->ns-deps {:ns-syms ['fake.no-such-ns-at-all]})]
          (is (= {'fake.no-such-ns-at-all
                  {:require []
                   :aliases []
                   :imports []
                   :refer-clojure {:excludes [] :renames {}}
                   :unmapped-default-imports []}}
                 deps))
          (is (some #(= {:event :clara-rules/ns-deps-missing
                         :ns 'fake.no-such-ns-at-all} %)
                    @tapped)))
        (finally (remove-tap tap-fn))))))

(deftest test-ns-imports-excludes-only-default-imports
  (testing "explicit non-default java.lang import is preserved"
    (let [nsobj (the-ns shape-ns-sym)]
      (is (contains? (set (ns-deps/->ns-imports nsobj)) 'java.lang.StackWalker)
          "ns-deps data must list its FQ symbol")
      (is (not (contains? (set (ns-deps/->ns-unmapped-default-imports nsobj)) 'StackWalker))
          "unmapped-default-imports unaffected (StackWalker was never a default)")))
  (testing "fresh ns: defaults excluded from imports, nothing unmapped"
    (let [fresh-sym 'fake.ns-deps-fresh]
      (create-ns fresh-sym)
      (binding [*ns* (the-ns fresh-sym)]
        (eval '(clojure.core/refer-clojure)))
      (let [nsobj (the-ns fresh-sym)
            imports (set (ns-deps/->ns-imports nsobj))]
        (is (not (contains? imports 'java.lang.String)))
        (is (not (contains? imports 'java.lang.Object)))
        (is (= [] (ns-deps/->ns-unmapped-default-imports nsobj)))))))

