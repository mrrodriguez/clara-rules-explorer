(ns clara.server.tools.graph.rules.analyze-test-rules
  (:require [clara.rules :as r]
            [clara.server.tools.graph.rules.loan-app-facts :as laf])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            DocumentCheck]))

;; A dummy record locally defined for testing
(defrecord LocalDummyRecord [id value])

(defn ->fact
  "A mock fact builder mimicking facts.model.core/->fact"
  [type data]
  (with-meta data {:type type}))

(def side-effect-counter (atom 0))

(defn make-document-check
  "1. Helper function that constructs a record"
  [app-id]
  (laf/map->DocumentCheck
   {:app-id app-id :status :pass :message "Passed via helper"}))

(defn make-document-check-nested
  "2. Helper function that calls another helper function to construct a record"
  [app-id]
  (make-document-check app-id))

(defn make-java-document-check
  "3. Helper function that constructs a Java class directly"
  [app-id]
  (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. app-id :pass "via-java-helper" nil nil))

(defn make-java-document-check-nested
  "4. Helper function that calls another helper function to construct a Java class"
  [app-id]
  (make-java-document-check app-id))

(defn insert-document-check-helper
  "5. Helper function that performs constructor and insertion"
  [app-id]
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass "helper-insert" nil nil)))

(defn make-facts
  "6. Helper function that constructs a collection of records"
  [app-id]
  [(map->LocalDummyRecord {:id app-id :value "helper-all-1"})
   (map->LocalDummyRecord {:id app-id :value "helper-all-2"})])

(defn make-heterogeneous-facts
  "7. Helper function that constructs a heterogeneous collection of records and Java classes"
  [app-id]
  [(map->LocalDummyRecord {:id app-id :value "helper-mixed-1"})
   (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. app-id :pass "helper-mixed-2" nil nil)])

;;
;; Rules
;;

(r/defrule rule-record-constructor
  "Rule A: Standard Clojure record constructor"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (map->LocalDummyRecord {:id ?app-id :value "standard"})))

(r/defrule rule-java-constructor-dot
  "Rule B: Java constructor style 1 (Class. args)"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (DocumentCheck. ?app-id :pass "dot-style" nil nil)))

(r/defrule rule-java-constructor-new
  "Rule C: Java constructor style 2 (new Class args)"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass "new-style" nil nil)))

(r/defrule rule-nested-helper-call
  "Rule D: Tracing through helper function"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-document-check-nested ?app-id)))

(r/defrule rule-metadata-map-fact
  "Rule E: Map facts with metadata (highly dynamic, manual annotations only)"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (with-meta {:app-id ?app-id :status :pass} {:type :custom-map-type})))

(r/defrule rule-java-constructor-fq-dot
  "Rule B2: Fully-qualified Class. constructor"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. ?app-id :pass "fq-dot-style" nil nil)))

(r/defrule rule-java-constructor-short-new
  "Rule C2: Short name new Class constructor"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (new DocumentCheck ?app-id :pass "short-new-style" nil nil)))

(r/defrule rule-java-constructor-short-modern
  "Rule F1: Modern constructor syntax (Class/new) via short name"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (DocumentCheck/new ?app-id :pass "short-modern" nil nil)))

(r/defrule rule-java-constructor-fq-modern
  "Rule F2: Modern constructor syntax (Class/new) via fully-qualified name"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass "fq-modern" nil nil)))

(r/defrule rule-nested-java-helper-call
  "Rule G: Tracing through helper function for Java constructor"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-java-document-check-nested ?app-id)))

(r/defrule rule-insert-all-collection
  "Rule H1: insert-all! with a collection of constructed records"
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! [(map->LocalDummyRecord {:id ?app-id :value "all-1"})
                  (map->LocalDummyRecord {:id ?app-id :value "all-2"})]))

(r/defrule rule-insert-varargs
  "Rule H2: insert! with varargs"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (map->LocalDummyRecord {:id ?app-id :value "var-1"})
             (map->LocalDummyRecord {:id ?app-id :value "var-2"})))

(r/defrule rule-retract-varargs
  "Rule H3: retract! with varargs"
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (map->LocalDummyRecord {:id ?app-id :value "ret-1"})
              (map->LocalDummyRecord {:id ?app-id :value "ret-2"})))

(r/defrule rule-complex-rhs-nested
  "Rule H4: RHS with complex nested doseq loop"
  [Application (= ?app-id app-id)]
  =>
  (let [items [{:id ?app-id :value "doseq-1"}]]
    (doseq [item items]
      (r/insert! (map->LocalDummyRecord item)))))

(r/defrule rule-helper-does-insert
  "Rule H5: RHS calls helper function which does the insert"
  [Application (= ?app-id app-id)]
  =>
  (insert-document-check-helper ?app-id))

(r/defrule rule-side-effect-only
  "Rule H6: Side-effect only rule (no insert/retract)"
  [Application (= ?app-id app-id)]
  =>
  (swap! side-effect-counter inc))

(r/defrule rule-insert-all-helper
  "Rule H7: insert-all! with collection constructed by helper function"
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-facts ?app-id)))

(r/defrule rule-insert-all-heterogeneous
  "Rule H8: insert-all! with a heterogeneous collection constructed by helper function"
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-heterogeneous-facts ?app-id)))

(r/defrule rule-insert-unconditional
  "Rule H9: insert-unconditional! usage"
  [Application (= ?app-id app-id)]
  =>
  (r/insert-unconditional! (map->LocalDummyRecord {:id ?app-id :value "unconditional"})))

(r/defrule rule-insert-all-unconditional
  "Rule H10: insert-all-unconditional! usage"
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all-unconditional! [(map->LocalDummyRecord {:id ?app-id :value "all-unconditional"})]))

(r/defrule rule-fact-builder-call
  "Rule E2: Insert using a custom function that has a constructor-like name but is not a record"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->fact :custom-fact-type {:app-id ?app-id :status :pass})))

;; ---------------------------------------------------------------------------
;; Dynamic retract rules

(defn retract-document-check-helper
  "Helper function that performs Java constructor and retraction"
  [app-id]
  (r/retract! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass "helper-retract" nil nil)))

(r/defrule rule-retract-java-dot
  "Rule I1: retract! with Java constructor (Class.)"
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (DocumentCheck. ?app-id :pass "dot-retract" nil nil)))

(r/defrule rule-retract-java-new
  "Rule I2: retract! with Java constructor (new Class)"
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass "new-retract" nil nil)))

(r/defrule rule-retract-java-modern
  "Rule I3: retract! with modern Java constructor (Class/new)"
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass "modern-retract" nil nil)))

(r/defrule rule-retract-metadata-map
  "Rule I4: retract! with metadata map fact (highly dynamic)"
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (with-meta {:app-id ?app-id :status :pass} {:type :custom-retract-type})))

(r/defrule rule-retract-helper-call
  "Rule I5: retract! via helper function performing Java constructor + retract"
  [Application (= ?app-id app-id)]
  =>
  (retract-document-check-helper ?app-id))

;; ---------------------------------------------------------------------------
;; Constructor resolution chain rules

(r/defrule rule-let-bound-ctor
  "Rule J1: RHS let-binds a Java constructor result, then inserts the local —
   resolved by tracing the local to its init form"
  [Application (= ?app-id app-id)]
  =>
  (let [dc (DocumentCheck. ?app-id :pass "let-bound" nil nil)]
    (r/insert! dc)))

(r/defrule rule-insert-mixed-varargs
  "Rule J2: varargs insert where only some args are automatically resolvable —
   yields a :partial aggregate resolution"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (DocumentCheck. ?app-id :pass "mixed" nil nil)
             (make-java-document-check-nested ?app-id)))

;; ---------------------------------------------------------------------------
;; Var-as-fact pattern fixtures (:fact-type-spec-fn var-alias chains)

(defn widget-transform
  "A var-as-fact fixture: inserted as a fact (the var itself) by
   rule-insert-widget-transform, then bound and invoked by
   rule-consume-widget-transform. Its body performs a dynamic insert whose
   type static analysis cannot resolve (a helper call), mimicking the
   def-fact-fn pattern from loan-doc-rules."
  {:type :widget-transform}
  [app-id]
  (r/insert! (->fact :widget-output {:app-id app-id})))

(r/defrule rule-insert-widget-transform
  "Rule K1: inserts the widget-transform var itself as a fact
   (the producing side of the var-as-fact pattern)"
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (var widget-transform)))

(r/defrule rule-consume-widget-transform
  "Rule K2: binds the widget-transform var-fact and invokes it in the RHS
   (the consuming side of the var-as-fact pattern)"
  [?t <- :widget-transform]
  [Application (= ?app-id app-id)]
  =>
  (mapv ?t [?app-id]))

;; ---------------------------------------------------------------------------
;; Constructor-of-interest fixtures (:fact-constructor-match-fn mechanism)

(defn make-tagged-facts
  "Helper that builds facts via the ->fact constructor and returns them.
   The constructor is reached transitively through this helper — the analyzer
   must trace through it to find the ->fact callsite."
  [ids]
  (mapv (fn [id] (->fact :demo/tagged {:id id})) ids))

(r/defrule rule-ctor-of-interest-via-helper
  "Rule L: inserts via a helper that internally calls the ->fact constructor.
   Without :fact-constructor-match-fn the ->fact callsite inside
   make-tagged-facts is invisible; with it, the type :demo/tagged is resolved."
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-tagged-facts [?app-id])))

