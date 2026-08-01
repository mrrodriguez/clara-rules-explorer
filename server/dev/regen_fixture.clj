;; Regenerates test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn
;; as a Layer (docs/anno-merging-update-plan.md phase 6.6).
;;
;; Run from server/:
;;   make regen-fixture
;;
;; The fixture mirrors generation with constructor-of-interest resolution for
;; helpers/->fact (see analyze_test's loan-doc-ctor-annotations) plus the
;; hand-authored entries the generator cannot produce (marked below).

(ns regen-fixture
  (:require [clara.rules :as r]
            [clara.server.tools.graph.analyze :as analyze]
            [clara.server.tools.graph.annotations.merge :as ann.merge]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clojure.pprint :as pprint]))

(def ^:private helpers->fact-sym
  'clara.server.tools.graph.rules.helpers/->fact)

(defn- ->fact-sym-match-fn [ctor-sym]
  (fn [sym] (= ctor-sym sym)))

(defn- ->fact-type-resolver [{:keys [arg-form]}]
  (when (and (seq? arg-form)
             (= 3 (count arg-form)))
    {:resolved-types [(second arg-form)]}))

(def ^:private session
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(def ^:private analysis
  (analyze/analyze-session-rules {:session-or-rulebase session}))

(def ^:private generated
  (analyze/generate-annotations-from-analysis
   {:analysis analysis
    :session-or-rulebase session
    :fact-constructors [{:match-fn (->fact-sym-match-fn helpers->fact-sym)
                         :type-resolver-fn ->fact-type-resolver}]}))

(let [path "test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn"]
  (ann.merge/write-layer! path
                          (ann.merge/layer {:id :clara.tools.graph.analyze/generated
                                            :source {:generated-from "clara.server.tools.graph.rules.loan-doc-rules"}
                                            :annotations generated}))
  (println "wrote" path)
  (println (with-out-str (pprint/pprint generated))))
