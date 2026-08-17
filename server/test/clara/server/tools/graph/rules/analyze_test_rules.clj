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
;; Constructor-of-interest fixtures (:fact-constructors mechanism)

(defn make-tagged-facts
  "Helper that builds facts via the ->fact constructor and returns them.
   The constructor is reached transitively through this helper — the analyzer
   must trace through it to find the ->fact callsite."
  [ids]
  (mapv (fn [id] (->fact :demo/tagged {:id id})) ids))

(r/defrule rule-ctor-of-interest-via-helper
  "Rule L: inserts via a helper that internally calls the ->fact constructor.
   Without :fact-constructors the ->fact callsite inside
   make-tagged-facts is invisible; with it, the type :demo/tagged is resolved."
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (make-tagged-facts [?app-id])))

(defn make-middle-fact
  "A plain middle fn returning a constructed fact. Note the arity-1 constructor
   call — the fact type is the whole fact."
  [id]
  (->fact :demo/middle {:id id}))

(r/defrule rule-ctor-via-middle-fn
  "Rule L3: `(insert! (make-middle-fact …))`. The constructor call is NOT written
   inside the insert! call, so coverage cannot come from lexical nesting — it has
   to come from the call chain (`make-middle-fact` is a link on the path from this
   rule to `->fact`)."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-middle-fact ?app-id)))

(defn deep-inner-fact [id] (->fact :demo/deep {:id id}))
(defn deep-outer-fact [id] (deep-inner-fact id))

(r/defrule rule-ctor-via-two-hop-chain
  "Rule L4: two hops between the insert! and the constructor — the argument names
   `deep-outer-fact`, which is a link on the chain, while the constructor lives in
   `deep-inner-fact`."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (deep-outer-fact ?app-id)))

(r/defrule rule-ctor-bound-to-local
  "Rule L5: the constructor call is bound to a local *outside* the insert! call,
   so the insert!'s argument is a bare local naming no link in the call chain.
   The constructor is still discovered — it is a var-usage in the rule's own body
   — but nothing marks the boundary call as covered."
  [Application (= ?app-id app-id)]
  =>
  (let [f (->fact :demo/local-bound {:id ?app-id})]
    (r/insert! f)))

(defn opaque-fact
  "Builds a fact without a constructor of interest — only a `:callsite-resolver-fn`
   can read it."
  [id]
  (with-meta {:id id} {:type :demo/opaque-2}))

(r/defrule rule-ctor-local-plus-multiple-inserts
  "Rule L6: a let-bound constructor alongside several inserts — one inline
   constructor, one opaque, one inserting the local."
  [Application (= ?app-id app-id)]
  =>
  (let [f (->fact :demo/local-x {:id ?app-id})]
    (r/insert! (->fact :demo/inline-y {:id ?app-id}))
    (r/insert! (opaque-fact ?app-id))
    (r/insert! f)))

(r/defrule rule-ctor-local-never-inserted
  "Rule L7: a constructor is called and bound, but the local is never inserted.
   Nothing about this rule actually emits :demo/never-inserted."
  [Application (= ?app-id app-id)]
  =>
  (let [_f (->fact :demo/never-inserted {:id ?app-id})]
    (r/insert! (opaque-fact ?app-id))))

(r/defrule rule-ctor-and-opaque-inserts
  "Rule L2: two inserts in one rule — one built by the ->fact constructor of
   interest, one by an opaque form only a `:callsite-resolver-fn` can read.

   Exercises the precedence rule: the constructor path owns the first insert
   (exactly one callsite, carrying :constructor-sym/:via, and the generic
   resolver is never asked about it), while the second insert still reaches
   `:callsite-resolver-fn` normally."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->fact :demo/ctor-owned {:id ?app-id}))
  (r/insert! (with-meta {:id ?app-id} {:type :demo/opaque})))

(r/defrule rule-ctor-identical-forms
  "Rule L8: two textually-identical constructor forms in one rule — one
   let-bound, one written inline in a second insert!.  Position identity must
   keep them straight: the inline constructor owns the second insert, the
   let-bound constructor owns the first (via its binding), and neither
   cross-attributes.  With value-equality matching, the inline form would
   also match the first insert's traced local and be reported twice."
  [Application (= ?app-id app-id)]
  =>
  (let [f (->fact :demo/identical {:id ?app-id})]
    (r/insert! f)
    (r/insert! (->fact :demo/identical {:id ?app-id}))))

;; ---------------------------------------------------------------------------
;; Callsite `:via` provenance fixtures
;; (gap A / gap B — see docs/planning/analyze-callsite-provenance-fixes-*)

(defn insert-summary! [id]
  (r/insert! (->fact :demo/summary {:id id})))

(defn record-summary! [id]
  (insert-summary! id))

(r/defrule rule-boundary-two-hops-above
  "The boundary call lives in insert-summary!, two hops above the rule:
   rule -> record-summary! -> insert-summary! -> insert!."
  [Application (= ?app-id app-id)]
  =>
  (record-summary! ?app-id))

(defn insert-parameterized-fact! [fact-type]
  (r/insert! (->fact fact-type {:value :x})))

(r/defrule rule-ctor-unresolvable-parameter
  "The constructor's fact type is a parameter — a literal-only type resolver
   cannot type it, so the constructor entry is dropped and its provenance must
   survive on the boundary entry."
  [Application (= ?app-id app-id)]
  =>
  (insert-parameterized-fact! :example/from-config))

(defn insert-facts! [facts]
  (r/insert-all! facts))

(r/defrule rule-insert-via-parameter
  "The boundary argument is the helper's parameter — no constructor call to
   read, so the callsite carries boundary-side provenance only."
  [Application (= ?app-id app-id)]
  =>
  (insert-facts! [{:value ?app-id}]))

(defn build-ambiguous-facts! [a b]
  [(->fact a {:id 1})
   (->fact b {:id 2})])

(r/defrule rule-two-constructors-one-arg
  "One boundary argument reaches two constructors; when both are dropped the
   constructor identity is ambiguous."
  [Application (= ?app-id app-id)]
  =>
  (r/insert-all! (build-ambiguous-facts! :one :two)))

(defn insert-shared! [x]
  (r/insert! (->fact :demo/shared {:id x})))

(defn insert-via-a! [x]
  (insert-shared! x))

(defn insert-via-b! [x]
  (insert-shared! x))

(r/defrule rule-two-paths-to-boundary
  "The rule reaches insert-shared! by two paths; :rule-to-boundary-path picks one
   deterministically (BFS sorted by str)."
  [Application (= ?app-id app-id)]
  =>
  (insert-via-a! ?app-id)
  (insert-via-b! ?app-id))

;; ---------------------------------------------------------------------------
;; Heuristic record-ctor scan fallback fixtures
;; (defect: spurious record-ctor scan types outranking constructor-of-interest
;; resolution — see server/docs/defect-spurious-defrecord-ctor-types-resolved.md)

(defrecord UnrelatedScanRecord [x])

(defrecord HiddenHelperRecord [id])

(definterface IScanMarker)

(defrecord MarkerRecord [id]
  IScanMarker)

(defn helper-with-unrelated-ctor
  "Builds the rule's fact via the registered ->fact constructor, but its
   subtree also contains an unrelated record ctor — a stand-in for
   third-party records reachable through shared helpers (e.g. malli.core/->Tag
   from the defect report)."
  [m]
  (when (:validate? m) (->UnrelatedScanRecord 1))
  (->fact :demo/scan-precedence m))

(r/defrule rule-scan-must-not-displace-ctor
  "Defect reproduction: the RHS insert goes through the registered ->fact
   constructor, while an unrelated record ctor (->UnrelatedScanRecord) is
   reachable in the same subtree.  Caller-registered resolution must win and
   the spurious scan type must not appear."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (helper-with-unrelated-ctor {:app-id ?app-id})))

(defn make-hidden-helper-record
  "Builds a record behind an opaque helper — the record ctor is only visible
   to the subtree scan, not to boundary-argument resolution."
  [id]
  (map->HiddenHelperRecord {:id id}))

(defn helper-inserter
  "A direct inserter whose boundary argument is an opaque helper call."
  [id]
  (r/insert! (make-hidden-helper-record id)))

(defn helper-retractor
  "A direct retractor whose boundary argument is an opaque helper call."
  [id]
  (r/retract! (make-hidden-helper-record id)))

(r/defrule rule-mixed-ctor-and-helper-insert
  "Two insert paths: the rule's own (insert! (->fact …)) — owned by the
   constructor-of-interest path — and a call to helper-inserter, a *separate*
   direct-inserter var that falls to the heuristic scan fallback."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (->fact :demo/mixed-registered {:id ?app-id}))
  (helper-inserter ?app-id))

(r/defrule rule-retract-via-helper-fallback
  "Retract symmetry for the heuristic fallback: the retract happens in a
   helper whose boundary argument is opaque."
  [HiddenHelperRecord (= ?id id)]
  =>
  (helper-retractor ?id))

(r/defrule rule-consume-hidden-helper-record
  "Puts HiddenHelperRecord on an LHS so the default
   :rulebase-fact-types-only filter admits its fallback types."
  [HiddenHelperRecord (= ?id id)]
  [Application (= ?app-id app-id)]
  =>
  (swap! side-effect-counter inc))

(r/defrule rule-consume-local-dummy-record
  "Puts LocalDummyRecord on an LHS so the default
   :rulebase-fact-types-only filter admits its fallback types."
  [LocalDummyRecord (= ?id id)]
  [Application (= ?app-id app-id)]
  =>
  (swap! side-effect-counter inc))

(defn make-marker-record
  "Builds a MarkerRecord behind an opaque helper."
  [id]
  (map->MarkerRecord {:id id}))

(r/defrule rule-insert-marker-record
  "Inserts a MarkerRecord via an opaque helper; no production's LHS names
   MarkerRecord directly, so the default filter can only admit it through the
   ancestors path (rule-consume-marker-via-interface matches its interface)."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-marker-record ?app-id)))

(r/defrule rule-consume-marker-via-interface
  "LHS on the IScanMarker interface — an inserted MarkerRecord matches via
   the type hierarchy (its Java ancestors include IScanMarker)."
  [IScanMarker]
  [Application (= ?app-id app-id)]
  =>
  (swap! side-effect-counter inc))

(defrecord QueryOnlyRecord [id])

(defn make-query-only-record
  "Builds a QueryOnlyRecord behind an opaque helper."
  [id]
  (map->QueryOnlyRecord {:id id}))

(r/defrule rule-insert-query-only-record
  "Inserts a record consumed only by a query — the default filter must still
   admit it, since query LHS types count as rulebase fact types."
  [Application (= ?app-id app-id)]
  =>
  (r/insert! (make-query-only-record ?app-id)))

(r/defquery find-query-only-record
  [:?id]
  [QueryOnlyRecord (= ?id id)])
