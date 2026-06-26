(ns clara.server.tools.graph.rules.analyze-test-rules
  (:require [clara.rules :as r]
            [clara.server.tools.graph.rules.loan-app-facts :as laf])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            DocumentCheck]))

;; A dummy record locally defined for testing
(defrecord LocalDummyRecord [id value])

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
