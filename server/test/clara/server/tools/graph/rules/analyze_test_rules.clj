(ns clara.server.tools.graph.rules.analyze-test-rules
  (:require [clara.rules :as r]
            [clara.server.tools.graph.rules.loan-app-facts :as laf])
  (:import [clara.server.tools.graph.rules.loan_app_facts
            Application
            DocumentCheck]))

;; A dummy record locally defined for testing
(defrecord LocalDummyRecord [id value])

(def side-effect-counter (atom 0))

;; 1. Helper function that constructs a record
(defn make-document-check [app-id]
  (laf/map->DocumentCheck
   {:app-id app-id :status :pass :message "Passed via helper"}))

;; 2. Helper function that calls another helper function to construct a record
(defn make-document-check-nested [app-id]
  (make-document-check app-id))

;; 3. Helper function that constructs a Java class directly
(defn make-java-document-check [app-id]
  (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. app-id :pass "via-java-helper" nil nil))

;; 4. Helper function that calls another helper function to construct a Java class
(defn make-java-document-check-nested [app-id]
  (make-java-document-check app-id))

;; 5. Helper function that performs constructor and insertion
(defn insert-document-check-helper [app-id]
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new app-id :pass "helper-insert" nil nil)))

;; 6. Helper function that constructs a collection of records
(defn make-facts [app-id]
  [(map->LocalDummyRecord {:id app-id :value "helper-all-1"})
   (map->LocalDummyRecord {:id app-id :value "helper-all-2"})])

;; 7. Helper function that constructs a heterogeneous collection of records and Java classes
(defn make-heterogeneous-facts [app-id]
  [(map->LocalDummyRecord {:id app-id :value "helper-mixed-1"})
   (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. app-id :pass "helper-mixed-2" nil nil)])

;;
;; Rules
;;

;; Rule A: Standard Clojure record constructor
(r/defrule rule-record-constructor
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (map->LocalDummyRecord {:id ?app-id :value "standard"})))

;; Rule B: Java constructor style 1 (Class. args)
(r/defrule rule-java-constructor-dot
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (DocumentCheck. ?app-id :pass "dot-style" nil nil)))

;; Rule C: Java constructor style 2 (new Class args)
(r/defrule rule-java-constructor-new
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (new clara.server.tools.graph.rules.loan_app_facts.DocumentCheck ?app-id :pass "new-style" nil nil)))

;; Rule D: Tracing through helper function
(r/defrule rule-nested-helper-call
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-document-check-nested ?app-id)))

;; Rule E: Map facts with metadata (highly dynamic, manual annotations only)
(r/defrule rule-metadata-map-fact
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (with-meta {:app-id ?app-id :status :pass} {:type :custom-map-type})))

;; Rule B2: Fully-qualified Class. constructor
(r/defrule rule-java-constructor-fq-dot
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck. ?app-id :pass "fq-dot-style" nil nil)))

;; Rule C2: Short name new Class constructor
(r/defrule rule-java-constructor-short-new
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (new DocumentCheck ?app-id :pass "short-new-style" nil nil)))

;; Rule F1: Modern constructor syntax (Class/new) via short name
(r/defrule rule-java-constructor-short-modern
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (DocumentCheck/new ?app-id :pass "short-modern" nil nil)))

;; Rule F2: Modern constructor syntax (Class/new) via fully-qualified name
(r/defrule rule-java-constructor-fq-modern
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (clara.server.tools.graph.rules.loan_app_facts.DocumentCheck/new ?app-id :pass "fq-modern" nil nil)))

;; Rule G: Tracing through helper function for Java constructor
(r/defrule rule-nested-java-helper-call
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-java-document-check-nested ?app-id)))

;; Rule H1: insert-all! with a collection of constructed records
(r/defrule rule-insert-all-collection
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! [(map->LocalDummyRecord {:id ?app-id :value "all-1"})
                  (map->LocalDummyRecord {:id ?app-id :value "all-2"})]))

;; Rule H2: insert! with varargs
(r/defrule rule-insert-varargs
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (map->LocalDummyRecord {:id ?app-id :value "var-1"})
             (map->LocalDummyRecord {:id ?app-id :value "var-2"})))

;; Rule H3: retract! with varargs
(r/defrule rule-retract-varargs
  [Application (= ?app-id app-id)]
  =>
  (r/retract! (map->LocalDummyRecord {:id ?app-id :value "ret-1"})
              (map->LocalDummyRecord {:id ?app-id :value "ret-2"})))

;; Rule H4: RHS with complex nested doseq loop
(r/defrule rule-complex-rhs-nested
  [Application (= ?app-id app-id)]
  =>
  (let [items [{:id ?app-id :value "doseq-1"}]]
    (doseq [item items]
      (r/insert! (map->LocalDummyRecord item)))))

;; Rule H5: RHS calls helper function which does the insert
(r/defrule rule-helper-does-insert
  [Application (= ?app-id app-id)]
  =>
  (insert-document-check-helper ?app-id))

;; Rule H6: Side-effect only rule (no insert/retract)
(r/defrule rule-side-effect-only
  [Application (= ?app-id app-id)]
  =>
  (swap! side-effect-counter inc))

;; Rule H7: insert-all! with collection constructed by helper function
(r/defrule rule-insert-all-helper
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-facts ?app-id)))

;; Rule H8: insert-all! with a heterogeneous collection constructed by helper function
(r/defrule rule-insert-all-heterogeneous
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-heterogeneous-facts ?app-id)))

;; Rule H9: insert-unconditional! usage
(r/defrule rule-insert-unconditional
  [Application (= ?app-id app-id)]
  =>
  (r/insert-unconditional! (map->LocalDummyRecord {:id ?app-id :value "unconditional"})))

;; Rule H10: insert-all-unconditional! usage
(r/defrule rule-insert-all-unconditional
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all-unconditional! [(map->LocalDummyRecord {:id ?app-id :value "all-unconditional"})]))
