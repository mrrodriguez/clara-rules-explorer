(ns clara.server.tools.graph.annotations-merge-test
  "Tests for the layered-annotation format and merge semantics
   (docs/anno-merging-update-plan.md §4–§5, phase 1).  Pure-data tests over
   fixture layers — no session, no rulebase, no classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clara.server.tools.graph.annotations :as ann]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private generated-callsite
  {:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
   :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
   :ns-name-sym 'acme.pricing
   :filename "acme/pricing.clj"
   :constructor-sym 'acme.facts/make-fact
   :via {:boundary-var-name-sym 'clara.rules/insert!
         :callstack [{:var-name-sym 'acme.facts/make-fact}]}
   :status :none})

(def ^:private generated-layer
  (ann/layer
   {:id :generated
    :source {:generated-from "acme.pricing"}
    :annotations
    {"acme.pricing/discount-rule"
     #:clara-rules{:dynamic-insert-types-detected
                   {:callsites [generated-callsite]
                    :resolution :none}}}}))

(def ^:private curated-layer
  (ann/layer
   {:id :curated
    :annotations
    {"acme.pricing/discount-rule"
     #:clara-rules{:dynamic-insert-types-detected
                   {:callsites
                    [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                      :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                      :status :full
                      :resolved-types [:acme.pricing/gold-discount
                                       :acme.pricing/std-discount]
                      :resolution-evidence
                      {:note "closed map tier->type; both values enumerated"}}]}}}}))

;; ---------------------------------------------------------------------------
;; F1 — sparse layers: omission means "no opinion"
;; ---------------------------------------------------------------------------

(deftest sparse-layer-preserves-detection-map
  (testing "an overlay that mentions a rule but omits its detection map leaves it intact (F1)"
    (let [overlay (ann/layer {:id :overlay
                              :annotations
                              {"acme.pricing/discount-rule"
                               #:clara-rules{:notes "reviewed"}}})
          merged (ann/merge-layers [generated-layer overlay])
          rule (get (ann/annotations merged) "acme.pricing/discount-rule")]
      (is (some? (:clara-rules/dynamic-insert-types-detected rule)))
      (is (= "reviewed" (:clara-rules/notes rule)))
      (is (= :overlay (get (ann/provenance merged "acme.pricing/discount-rule")
                           :clara-rules/notes))))))

;; ---------------------------------------------------------------------------
;; F2 — unknown keys are preserved
;; ---------------------------------------------------------------------------

(deftest unknown-keys-survive
  (let [base (ann/layer {:id :base
                         :annotations
                         {"rule/a" {:acme/reviewed-by "alice"
                                    :clara-rules/insert-types [:x]}
                          "rule/b" {:acme/only-base true}}})
        overlay (ann/layer {:id :overlay
                            :annotations
                            {"rule/a" {:clara-rules/notes "n"
                                       :acme/reviewed-by "bob"}
                             "rule/c" {:acme/new-key 42}}})
        rules (ann/annotations (ann/merge-layers [base overlay]))]
    (testing "overlapping rule: unknown keys from both layers survive, upper wins on conflict"
      (is (= "bob" (:acme/reviewed-by (get rules "rule/a"))))
      (is (= [:x] (:clara-rules/insert-types (get rules "rule/a")))))
    (testing "non-overlapping rules pass through untouched"
      (is (= {:acme/only-base true} (get rules "rule/b")))
      (is (= {:acme/new-key 42} (get rules "rule/c"))))))

;; ---------------------------------------------------------------------------
;; §5.4 — tombstones and the short-circuit shape
;; ---------------------------------------------------------------------------

(deftest tombstones
  (let [base (ann/layer {:id :base
                         :annotations
                         {"rule/a"
                          #:clara-rules{:insert-types [:x]
                                        :retract-types [:y]
                                        :no-output-types true
                                        :notes "old"
                                        :dynamic-insert-types-detected
                                        {:callsites [generated-callsite]}
                                        :extra "keep-me"}}})
        tombstoning (ann/layer {:id :curated
                                :annotations
                                {"rule/a"
                                 #:clara-rules{:insert-types nil
                                               :retract-types nil
                                               :no-output-types nil
                                               :notes nil
                                               :dynamic-insert-types-detected nil}}})
        merged (ann/merge-layers [base tombstoning])
        rule (get (ann/annotations merged) "rule/a")]
    (testing "explicit nil erases every mergeable key"
      (is (not (contains? rule :clara-rules/insert-types)))
      (is (not (contains? rule :clara-rules/retract-types)))
      (is (not (contains? rule :clara-rules/no-output-types)))
      (is (not (contains? rule :clara-rules/notes)))
      (is (not (contains? rule :clara-rules/dynamic-insert-types-detected))))
    (testing "absence is not a tombstone: untouched keys survive"
      (is (= "keep-me" (:clara-rules/extra rule))))
    (testing "tombstones erase provenance"
      (is (= {:clara-rules/extra :base}
             (ann/provenance merged "rule/a")))))

  (testing "a later layer may re-establish an erased key"
    (let [base (ann/layer {:id :base
                           :annotations {"rule/a" #:clara-rules{:insert-types [:x]}}})
          erase (ann/layer {:id :erase
                            :annotations {"rule/a" #:clara-rules{:insert-types nil}}})
          readd (ann/layer {:id :readd
                            :annotations {"rule/a" #:clara-rules{:insert-types [:z]}}})
          rule (get (ann/annotations (ann/merge-layers [base erase readd])) "rule/a")]
      (is (= [:z] (:clara-rules/insert-types rule)))))

  (testing "short-circuit curation: types with no callsites and no resolution (§5.4)"
    (let [short-circuit (ann/layer {:id :curated
                                    :annotations
                                    {"acme.pricing/discount-rule"
                                     #:clara-rules{:insert-types [:acme.pricing/gold-discount
                                                                  :acme.pricing/std-discount]
                                                   :merge-props {:insert-types :replace}
                                                   :dynamic-insert-types-detected nil}}})
          merged (ann/merge-layers [generated-layer short-circuit])
          rule (get (ann/annotations merged) "acme.pricing/discount-rule")]
      (is (= [:acme.pricing/gold-discount :acme.pricing/std-discount]
             (:clara-rules/insert-types rule)))
      (is (not (contains? rule :clara-rules/dynamic-insert-types-detected)))
      ;; merge-props is a directive — consumed, never emitted
      (is (not (contains? rule :clara-rules/merge-props))))))

;; ---------------------------------------------------------------------------
;; §5.3 — deep callsite merge
;; ---------------------------------------------------------------------------

(deftest callsite-deep-merge
  (let [merged (ann/merge-layers [generated-layer curated-layer])
        rule (get (ann/annotations merged) "acme.pricing/discount-rule")
        dm (:clara-rules/dynamic-insert-types-detected rule)
        [cs] (:callsites dm)]
    (testing "curator's declared fields win; analyzer's discovery fields survive unstated"
      (is (= :full (:status cs)))
      (is (= [:acme.pricing/gold-discount :acme.pricing/std-discount]
             (:resolved-types cs)))
      (is (= "(f/make-fact (tier->type ?tier) {:total ?total})" (:source-str cs)))
      (is (= 'acme.facts/make-fact (:constructor-sym cs)))
      (is (some? (:via cs))))
    (testing "the merged entry records who supplied the conclusion"
      (is (= :curated (:from-layer cs))))
    (testing "resolution is recomputed from the merged callsites"
      (is (= :full (:resolution dm))))
    (testing "provenance records both contributing layers in precedence order"
      (is (= [:generated :curated]
             (get (ann/provenance merged "acme.pricing/discount-rule")
                  :clara-rules/dynamic-insert-types-detected))))))

(deftest callsite-deep-merge-union-and-ordering
  (let [cs-b (assoc generated-callsite :callsite-id "ns:ctor:bbbbbbbb:0"
                    :source-str "(insert! b)")
        cs-c (assoc generated-callsite :callsite-id "ns:ctor:cccccccc:0"
                    :source-str "(insert! c)")
        base (ann/layer {:id :base
                         :annotations
                         {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                 {:callsites [generated-callsite cs-b]}}}})
        overlay (ann/layer {:id :overlay
                            :annotations
                            {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                    {:callsites [cs-c]}}}})
        dm (get-in (ann/annotations (ann/merge-layers [base overlay]))
                   ["rule/a" :clara-rules/dynamic-insert-types-detected])]
    (testing "id-keyed union keeps a's order, appends b-only entries"
      (is (= ["acme.pricing:make-fact:a3f19c2b:0" "ns:ctor:bbbbbbbb:0" "ns:ctor:cccccccc:0"]
             (mapv :callsite-id (:callsites dm)))))
    (testing "untouched entries keep their original :from-layer"
      (is (= [:base :base :overlay] (mapv :from-layer (:callsites dm)))))
    (testing "all-:none aggregate"
      (is (= :none (:resolution dm))))))

;; ---------------------------------------------------------------------------
;; F4 / §4.3 — resolution is derived, never authored
;; ---------------------------------------------------------------------------

(deftest resolution-is-recomputed
  (testing "a contradictory authored :resolution is ignored (F4)"
    (let [lying (ann/layer {:id :lying
                            :annotations
                            {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                    {:callsites [generated-callsite]
                                                     :resolution :full}}}})
          dm (get-in (ann/annotations (ann/merge-layers [lying]))
                     ["rule/a" :clara-rules/dynamic-insert-types-detected])]
      (is (= :none (:resolution dm))))))

(deftest resolution-aggregation
  (let [cs (fn [id status]
             {:callsite-id id :source-str "(x)" :status status})
        dm-for (fn [& statuses]
                 (let [layer (ann/layer {:id :l
                                         :annotations
                                         {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                                 {:callsites
                                                                  (map-indexed (fn [i st]
                                                                                 (cs (str "id:" i) st))
                                                                               statuses)}}}})]
                   (get-in (ann/annotations (ann/merge-layers [layer]))
                           ["rule/a" :clara-rules/dynamic-insert-types-detected
                            :resolution])))]
    (is (= :full (dm-for :full)))
    (is (= :full (dm-for :full :full)))
    (is (= :none (dm-for :none :none)))
    (is (= :partial (dm-for :full :none)))
    (is (= :partial (dm-for :partial :full)))
    (is (= :partial (dm-for :partial :none)))
    (is (= :partial (dm-for :partial :partial)))))

;; ---------------------------------------------------------------------------
;; §5.1 — merge-props precedence
;; ---------------------------------------------------------------------------

(deftest merge-props-precedence
  (let [base (ann/layer {:id :base
                         :annotations
                         {"rule/a" #:clara-rules{:insert-types [:a]}
                          "rule/b" #:clara-rules{:insert-types [:a]}
                          "rule/c" #:clara-rules{:insert-types [:a]}}})
        overlay (ann/layer {:id :overlay
                            :merge-props {:insert-types :replace}
                            :annotations
                            {"rule/a" #:clara-rules{:insert-types [:b]}
                             "rule/b" #:clara-rules{:insert-types [:b]
                                                    :merge-props {:insert-types :union}}
                             "rule/c" #:clara-rules{:insert-types [:b]}}})
        rules (ann/annotations (ann/merge-layers [base overlay]))]
    (testing "layer-level merge-props is the default for rules it touches"
      (is (= [:b] (:clara-rules/insert-types (get rules "rule/a")))))
    (testing "rule-level merge-props overrides the layer level"
      (is (= [:a :b] (:clara-rules/insert-types (get rules "rule/b")))))
    (testing "replace provenance names the single winning layer"
      (is (= :overlay (get (ann/provenance (ann/merge-layers [base overlay]) "rule/a")
                           :clara-rules/insert-types)))))

  (testing "default strategy is union, a first, distinct"
    (let [base (ann/layer {:id :base
                           :annotations {"rule/a" #:clara-rules{:insert-types [:x :y]}}})
          overlay (ann/layer {:id :overlay
                              :annotations {"rule/a" #:clara-rules{:insert-types [:y :z]}}})
          rule (get (ann/annotations (ann/merge-layers [base overlay])) "rule/a")]
      (is (= [:x :y :z] (:clara-rules/insert-types rule))))))

(deftest notes-append-strategy
  (let [base (ann/layer {:id :base
                         :annotations {"rule/a" #:clara-rules{:notes "first"}}})
        overlay (ann/layer {:id :overlay
                            :annotations
                            {"rule/a" #:clara-rules{:notes "second"
                                                    :merge-props {:notes :append}}}})
        rule (get (ann/annotations (ann/merge-layers [base overlay])) "rule/a")]
    (is (= "first\nsecond" (:clara-rules/notes rule)))))

;; ---------------------------------------------------------------------------
;; §5 — precedence, associativity, duplicate ids
;; ---------------------------------------------------------------------------

(deftest three-layer-precedence
  (let [props (ann/layer {:id :props
                          :annotations {"rule/a" #:clara-rules{:insert-types [:p]
                                                               :notes "from props"}}})
        generated (ann/layer {:id :generated
                              :annotations {"rule/a" #:clara-rules{:insert-types [:g]}}})
        reviewed (ann/layer {:id :reviewed
                             :annotations {"rule/a" #:clara-rules{:insert-types [:r]
                                                                  :notes "final word"}}})
        merged (ann/merge-layers [props generated reviewed])
        rule (get (ann/annotations merged) "rule/a")]
    (testing "types union across all three layers, lowest precedence first"
      (is (= [:p :g :r] (:clara-rules/insert-types rule)))
      (is (= [:props :generated :reviewed]
             (get (ann/provenance merged "rule/a") :clara-rules/insert-types))))
    (testing "last declared wins for notes"
      (is (= "final word" (:clara-rules/notes rule))))
    (testing ":layers lists the fold in precedence order"
      (is (= [:props :generated :reviewed] (mapv :id (:layers merged)))))))

(deftest fold-associativity
  (let [l1 (ann/layer {:id :l1 :annotations {"rule/a" #:clara-rules{:insert-types [:a]
                                                                    :notes "n1"}}})
        l2 (ann/layer {:id :l2 :annotations {"rule/a" #:clara-rules{:insert-types [:b]}}})
        l3 (ann/layer {:id :l3 :annotations {"rule/a" #:clara-rules{:insert-types [:c]
                                                                    :notes "n3"}}})
        one-pass (ann/annotations (ann/merge-layers [l1 l2 l3]))
        two-pass (ann/annotations
                  (ann/merge-layers [(ann/layer {:id :l1+l2
                                                 :annotations
                                                 (:annotations (ann/merge-layers [l1 l2]))})
                                     l3]))]
    (is (= one-pass two-pass))))

(deftest duplicate-layer-id-throws
  (let [l1 (ann/layer {:id :curated :annotations {}})
        l2 (ann/layer {:id :curated :annotations {}})]
    (is (thrown? clojure.lang.ExceptionInfo (ann/merge-layers [l1 l2])))))

;; ---------------------------------------------------------------------------
;; §6 / F7 — layer IO round-trip
;; ---------------------------------------------------------------------------

(deftest write-read-round-trip
  (let [path (io/file (System/getProperty "java.io.tmpdir")
                      (str "anno-roundtrip-" (System/nanoTime) ".edn"))]
    (try
      (let [layer (ann/layer {:id :curated
                              :annotations
                              {"acme.pricing/discount-rule"
                               #:clara-rules{:dynamic-insert-types-detected
                                             {:callsites
                                              [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                                                :source-str "(f/make-fact x)"
                                                :ns-name-sym (with-meta 'acme.pricing
                                                               {:row 1 :col 5})
                                                :status :full
                                                :resolved-types [:t]}]}}}})
            _ (ann/write-layer! path layer)
            written (slurp path)
            reread (ann/read-layer path)]
        (testing "no reader metadata is emitted (F7)"
          (is (not (re-find #"\^\{" written)))
          (is (not (re-find #":row" written))))
        (testing ":source defaults to the path"
          (is (= (str path) (:source reread))))
        (testing "write → read → merge is a fixed point"
          (is (= (ann/annotations (ann/merge-layers [(dissoc layer :source)]))
                 (ann/annotations (ann/merge-layers [(dissoc reread :source)]))))))
      (finally
        (io/delete-file path :silently)))))

;; ---------------------------------------------------------------------------
;; §4.4 — callsite identity (phase 3)
;; ---------------------------------------------------------------------------

(deftest callsite-id-format-and-stability
  (let [c {:source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
           :ns-name-sym 'acme.pricing
           :constructor-sym 'acme.facts/make-fact}]
    (testing "format is ns:ctor:hash8 — pinned hash protects the algorithm"
      (is (= "acme.pricing:make-fact:ef421c1c" (ann/callsite-id c))))
    (testing "assign-callsite-ids appends the group ordinal"
      (is (= ["acme.pricing:make-fact:ef421c1c:0"]
             (mapv :callsite-id (ann/assign-callsite-ids [c])))))
    (testing "boundary-path callsites (no constructor) use `-`"
      (is (re-find #"^acme.pricing:-:[0-9a-f]{8}:0$"
                   (:callsite-id (first (ann/assign-callsite-ids
                                         [(dissoc c :constructor-sym)]))))))
    (testing "an unrelated edit elsewhere leaves the id unchanged"
      (is (= (ann/callsite-id c)
             (ann/callsite-id (assoc c :via {:boundary-var-name-sym 'other/ns}
                                     :filename "other/file.clj")))))
    (testing "editing the callsite's own form changes the id"
      (is (not= (ann/callsite-id c)
                (ann/callsite-id (assoc c :source-str "(f/make-fact :gold {:total ?total})")))))))

(deftest duplicate-group-ordinals
  (let [dup {:source-str "(insert! x)"
             :ns-name-sym 'acme.pricing
             :constructor-sym 'acme.facts/make-fact}
        other {:source-str "(insert! y)"
               :ns-name-sym 'acme.pricing
               :constructor-sym 'acme.facts/make-fact}]
    (testing "textually identical siblings get distinct ordinals"
      (is (= ["acme.pricing:make-fact:0ed8c382:0" "acme.pricing:make-fact:0ed8c382:1"]
             (mapv :callsite-id (ann/assign-callsite-ids [dup dup])))))
    (testing "an unrelated callsite in the same rule does not renumber the group"
      (let [[_ d0 d1] (mapv :callsite-id (ann/assign-callsite-ids [other dup dup]))]
        (is (= ["acme.pricing:make-fact:0ed8c382:0" "acme.pricing:make-fact:0ed8c382:1"]
               [d0 d1]))))
    (testing "ids are unique by construction across the vector"
      (let [ids (mapv :callsite-id (ann/assign-callsite-ids [dup dup other]))]
        (is (apply distinct? ids))))))

(deftest ids-derived-on-read
  (testing "hand-written layers need not compute hashes when they supply the basis"
    (let [layer (ann/layer {:id :curated
                            :annotations
                            {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                    {:callsites
                                                     [{:source-str "(f/make-fact x)"
                                                       :ns-name-sym 'acme.pricing
                                                       :constructor-sym 'acme.facts/make-fact
                                                       :status :full
                                                       :resolved-types [:t]}]}}}})
          cs (get-in (ann/annotations (ann/merge-layers [layer]))
                     ["rule/a" :clara-rules/dynamic-insert-types-detected :callsites 0])]
      (is (string? (:callsite-id cs)))))
  (testing "an entry carrying an id keeps it"
    (let [layer (ann/layer {:id :curated
                            :annotations
                            {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                    {:callsites
                                                     [{:callsite-id "kept:id:deadbeef:0"
                                                       :status :none}]}}}})
          cs (get-in (ann/annotations (ann/merge-layers [layer] {:on-dangling :keep}))
                     ["rule/a" :clara-rules/dynamic-insert-types-detected :callsites 0])]
      (is (= "kept:id:deadbeef:0" (:callsite-id cs))))))

;; ---------------------------------------------------------------------------
;; §5.6 — dangling references (phase 3)
;; ---------------------------------------------------------------------------

(def ^:private dangling-curated-layer
  "A curated layer written against an older revision: its id matches nothing
   the analyzer discovered, and it carries no :source-str."
  (ann/layer {:id :curated
              :annotations
              {"acme.pricing/discount-rule"
               #:clara-rules{:dynamic-insert-types-detected
                             {:callsites
                              [{:callsite-id "acme.pricing:make-fact:7d10e4aa:0"
                                :status :full
                                :resolved-types [:acme.pricing/gold-discount]
                                :resolution-evidence {:note "was the old form"}}]}}}}))

(deftest dangling-quarantine
  (let [merged (ann/merge-layers [generated-layer dangling-curated-layer])
        dm (get-in (ann/annotations merged)
                   ["acme.pricing/discount-rule" :clara-rules/dynamic-insert-types-detected])
        [discovered stale] (:callsites dm)]
    (testing "the unmatched entry is kept and marked"
      (is (= 2 (count (:callsites dm))))
      (is (not (:dangling? discovered)))
      (is (true? (:dangling? stale))))
    (testing "quarantined entries are excluded from the resolution aggregate"
      ;; discovered is :none; stale (:full) is quarantined — aggregate is :none
      (is (= :none (:resolution dm))))
    (testing "its evidence survives for the report"
      (is (= {:note "was the old form"} (:resolution-evidence stale))))))

(deftest dangling-keep-and-drop
  (testing ":keep treats the entry as ordinary — its status counts"
    (let [merged (ann/merge-layers [generated-layer dangling-curated-layer]
                                   {:on-dangling :keep})
          dm (get-in (ann/annotations merged)
                     ["acme.pricing/discount-rule" :clara-rules/dynamic-insert-types-detected])]
      (is (not-any? :dangling? (:callsites dm)))
      (is (= :partial (:resolution dm)))))
  (testing ":drop removes the entry"
    (let [merged (ann/merge-layers [generated-layer dangling-curated-layer]
                                   {:on-dangling :drop})
          dm (get-in (ann/annotations merged)
                     ["acme.pricing/discount-rule" :clara-rules/dynamic-insert-types-detected])]
      (is (= 1 (count (:callsites dm))))
      (is (= :none (:resolution dm))))))

(deftest layer-introduced-callsite-is-not-dangling
  (testing "an entry supplying its own :source-str annotates a callsite the analyzer missed"
    (let [introduced (ann/layer {:id :curated
                                 :annotations
                                 {"acme.pricing/discount-rule"
                                  #:clara-rules{:dynamic-insert-types-detected
                                                {:callsites
                                                 [{:source-str "(f/make-fact :extra {})"
                                                   :ns-name-sym 'acme.pricing
                                                   :constructor-sym 'acme.facts/make-fact
                                                   :status :full
                                                   :resolved-types [:acme.pricing/extra]}]}}}})
          merged (ann/merge-layers [generated-layer introduced])
          dm (get-in (ann/annotations merged)
                     ["acme.pricing/discount-rule" :clara-rules/dynamic-insert-types-detected])]
      (is (= 2 (count (:callsites dm))))
      (is (not-any? :dangling? (:callsites dm)))
      (is (= :partial (:resolution dm))))))

;; ---------------------------------------------------------------------------
;; §7.1 — unresolved-report (phase 4)
;; ---------------------------------------------------------------------------

(deftest unresolved-report-shape-and-counts
  (let [resolved-cs (assoc generated-callsite
                           :callsite-id "acme.pricing:make-fact:11111111:0"
                           :source-str "(f/make-fact :gold {})"
                           :status :full
                           :resolved-types [:acme.pricing/gold-discount])
        partial-cs {:callsite-id "acme.pricing:make-fact:22222222:0"
                    :source-str "(f/make-fact (some-tier) {})"
                    :ns-name-sym 'acme.pricing
                    :constructor-sym 'acme.facts/make-fact
                    :status :partial
                    :resolved-types [:acme.pricing/gold-discount]}
        richer (ann/layer {:id :generated2
                           :annotations
                           {"acme.pricing/discount-rule"
                            #:clara-rules{:dynamic-insert-types-detected
                                          {:callsites [generated-callsite resolved-cs partial-cs]}}
                            "acme.pricing/other-rule"
                            #:clara-rules{:insert-types [:t]}}})
        report (ann/unresolved-report (ann/merge-layers [richer]))]
    (testing "summary counts rules and callsites needing work, by status"
      (is (= {:rules 1 :callsites 2 :by-resolution {:none 1 :partial 1} :dangling 0}
             (:summary report))))
    (testing ":rules carries the unresolved callsites with their discovery context"
      (is (= #{["acme.pricing:make-fact:a3f19c2b:0" :none]
               ["acme.pricing:make-fact:22222222:0" :partial]}
             (set (map (juxt :callsite-id :status)
                       (get-in report [:rules "acme.pricing/discount-rule" :insert :callsites])))))
      (is (= "(f/make-fact (tier->type ?tier) {:total ?total})"
             (:source-str (first (get-in report [:rules "acme.pricing/discount-rule"
                                                 :insert :callsites]))))))
    (testing "rules with no unresolved callsites do not appear"
      (is (not (contains? (:rules report) "acme.pricing/other-rule"))))))

(deftest unresolved-report-dangling
  (let [report (ann/unresolved-report (ann/merge-layers [generated-layer dangling-curated-layer]))]
    (testing "quarantined entries are reported separately, not as work"
      (is (= 1 (get-in report [:summary :dangling])))
      (is (= 1 (get-in report [:summary :callsites]))))
    (testing "the dangling entry names its layer and keeps its evidence"
      (is (= [{:rule "acme.pricing/discount-rule"
               :dimension :insert
               :callsite-id "acme.pricing:make-fact:7d10e4aa:0"
               :from-layer :curated
               :resolution-evidence {:note "was the old form"}}]
             (:dangling report))))))

;; ---------------------------------------------------------------------------
;; §7.2 — validate-layers (phase 4)
;; ---------------------------------------------------------------------------

(defn- ^:private findings-by-type [findings]
  (group-by :type findings))

(deftest validate-unknown-rule
  (let [layer (ann/layer {:id :curated
                          :annotations {"acme.pricing/discount-rule" #:clara-rules{:notes "ok"}
                                        "acme.pricing/nope-rule" #:clara-rules{:notes "typo"}}})
        findings (ann/validate-layers [layer] {:known-rule-names #{"acme.pricing/discount-rule"}})
        unknown (:unknown-rule (findings-by-type findings))]
    (is (= 1 (count unknown)))
    (is (= :error (:severity (first unknown))))
    (is (= "acme.pricing/nope-rule" (:rule (first unknown))))
    (testing "offline validation (no :known-rule-names) skips the check"
      (is (empty? (:unknown-rule (findings-by-type (ann/validate-layers [layer]))))))))

(deftest validate-resolved-without-types
  (let [layer (ann/layer {:id :curated
                          :annotations
                          {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                  {:callsites
                                                   [{:callsite-id "id:1"
                                                     :source-str "(x)"
                                                     :status :full}]}}}})
        findings (:resolved-without-types (findings-by-type (ann/validate-layers [layer])))]
    (is (= 1 (count findings)))
    (is (= :error (:severity (first findings))))
    (is (= "id:1" (:callsite-id (first findings))))))

(deftest validate-authored-derived-fields
  (let [layer (ann/layer {:id :curated
                          :annotations
                          {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                  {:callsites
                                                   [{:callsite-id "id:1"
                                                     :source-str "(x)"
                                                     :status :none
                                                     :from-layer :curated
                                                     :dangling? true}]
                                                   :resolution :none}}}})
        findings (:authored-derived-field (findings-by-type (ann/validate-layers [layer])))]
    (testing ":resolution, :from-layer, and :dangling? are all flagged"
      (is (= 3 (count findings))))
    (is (every? #(= :warn (:severity %)) findings))))

(deftest validate-dangling-callsite
  (let [findings (:dangling-callsite
                  (findings-by-type
                   (ann/validate-layers [generated-layer dangling-curated-layer])))]
    (is (= 1 (count findings)))
    (is (= :warn (:severity (first findings))))
    (is (= :curated (:layer (first findings))))
    (is (= "acme.pricing:make-fact:7d10e4aa:0" (:callsite-id (first findings))))))

(deftest validate-ambiguous-callsite-reference
  (let [dup {:source-str "(insert! x)"
             :ns-name-sym 'acme.pricing
             :constructor-sym 'acme.facts/make-fact
             :filename "acme/pricing.clj"
             :status :none}
        generated (ann/layer {:id :generated
                              :annotations
                              {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                      {:callsites [dup dup]}}}})
        [id0 _] (mapv :callsite-id
                      (:callsites (:clara-rules/dynamic-insert-types-detected
                                   (get (:annotations generated) "rule/a"))))
        curator (ann/layer {:id :curated
                            :annotations
                            {"rule/a" #:clara-rules{:dynamic-insert-types-detected
                                                    {:callsites
                                                     [{:callsite-id id0
                                                       :status :full
                                                       :resolved-types [:t]}]}}}})
        findings (:ambiguous-callsite-reference
                  (findings-by-type (ann/validate-layers [generated curator])))]
    (testing "a curator referencing a multi-member group id is warned"
      (is (= 1 (count findings)))
      (is (= id0 (:callsite-id (first findings)))))
    (testing "the discovering layer itself is not flagged"
      (is (every? #(= :curated (:layer %)) findings)))))

(deftest validate-no-op-entry
  (let [base (ann/layer {:id :base
                         :annotations {"rule/a" #:clara-rules{:insert-types [:x]}}})
        noop (ann/layer {:id :noop
                         :annotations {"rule/a" #:clara-rules{:insert-types [:x]}}})
        real (ann/layer {:id :real
                         :annotations {"rule/a" #:clara-rules{:insert-types [:y]}}})
        findings (:no-op-entry
                  (findings-by-type (ann/validate-layers [base noop real])))]
    (testing "restating the merged value beneath is flagged; adding to it is not"
      (is (= 1 (count findings)))
      (is (= :noop (:layer (first findings)))))))

(deftest validate-clean-layers
  (testing "the worked-example stack validates clean (no spurious findings)"
    (is (= [] (ann/validate-layers [generated-layer curated-layer])))))

;; ---------------------------------------------------------------------------
;; §5.7 — type derivation (phase 5)
;; ---------------------------------------------------------------------------

(def ^:private props-layer-fixture
  (ann/layer {:id :props
              :source :rulebase
              :annotations
              {"acme.pricing/discount-rule"
               #:clara-rules{:insert-types [:acme.pricing/gold-discount]
                             :notes "tier pricing"}}}))

(deftest worked-example-end-to-end
  (testing "the §8 stack: curated evidence promotes graph edges with provenance"
    (let [merged (ann/merge-layers [props-layer-fixture generated-layer curated-layer]
                                   {:type-derivation :from-callsites})
          rule (get (ann/annotations merged) "acme.pricing/discount-rule")
          prov (ann/provenance merged "acme.pricing/discount-rule")]
      (is (= [:acme.pricing/gold-discount :acme.pricing/std-discount]
             (:clara-rules/insert-types rule)))
      (is (= :derived (:clara-rules/insert-types prov)))
      (is (= :props (:clara-rules/notes prov)))
      (is (= [:generated :curated] (:clara-rules/dynamic-insert-types-detected prov)))
      (is (= :full (get-in rule [:clara-rules/dynamic-insert-types-detected :resolution])))
      (is (= :curated (get-in rule [:clara-rules/dynamic-insert-types-detected
                                    :callsites 0 :from-layer]))))))

(deftest additive-vs-from-callsites
  (let [layer (ann/layer {:id :l
                          :annotations
                          {"rule/a"
                           #:clara-rules{:insert-types [:authored/t]
                                         :dynamic-insert-types-detected
                                         {:callsites
                                          [{:callsite-id "id:1"
                                            :source-str "(x)"
                                            :status :full
                                            :resolved-types [:derived/t]}]}}
                           "rule/b"
                           #:clara-rules{:insert-types [:authored/only]}
                           "rule/c"
                           #:clara-rules{:insert-types [:authored/unsupported]
                                         :dynamic-insert-types-detected
                                         {:callsites
                                          [{:callsite-id "id:2"
                                            :source-str "(y)"
                                            :status :none}]}}}})]
    (testing ":additive (default) unions authored and derived types"
      (let [rules (ann/annotations (ann/merge-layers [layer] {:type-derivation :additive}))]
        (is (= [:authored/t :derived/t] (:clara-rules/insert-types (get rules "rule/a"))))
        (is (= [:authored/unsupported] (:clara-rules/insert-types (get rules "rule/c"))))))
    (testing ":from-callsites makes the callsite record authoritative per dimension"
      (let [rules (ann/annotations (ann/merge-layers [layer] {:type-derivation :from-callsites}))]
        (is (= [:derived/t] (:clara-rules/insert-types (get rules "rule/a")))
            "authored type with no callsite backing is dropped")
        (is (not (contains? (get rules "rule/c") :clara-rules/insert-types))
            "a :none callsite supports nothing — authored type is dropped")))
    (testing "props-survival clause: no detection map → authored types stand (§5.5)"
      (let [rules (ann/annotations (ann/merge-layers [layer] {:type-derivation :from-callsites}))]
        (is (= [:authored/only] (:clara-rules/insert-types (get rules "rule/b"))))))))

(deftest downgrading-a-wrong-callsite
  (let [generated (ann/layer {:id :generated
                              :annotations
                              {"rule/a"
                               #:clara-rules{:insert-types [:wrong/t]
                                             :dynamic-insert-types-detected
                                             {:callsites
                                              [{:callsite-id "id:1"
                                                :source-str "(make-wrong)"
                                                :ns-name-sym 'some.ns
                                                :filename "some/ns.clj"
                                                :status :full
                                                :resolved-types [:wrong/t]}]}}}})
        downgrade (ann/layer {:id :curated
                              :annotations
                              {"rule/a"
                               #:clara-rules{:dynamic-insert-types-detected
                                             {:callsites
                                              [{:callsite-id "id:1"
                                                :status :none
                                                :resolved-types nil
                                                :resolution-evidence
                                                {:note "analyzer mis-resolved; type is opaque"}}]}}}})]
    (testing ":additive cannot remove the type — the generated layer authored it rule-level"
      (let [rule (get (ann/annotations (ann/merge-layers [generated downgrade]))
                      "rule/a")]
        (is (= [:wrong/t] (:clara-rules/insert-types rule)))))
    (testing ":from-callsites can — D is the answer"
      (let [rule (get (ann/annotations (ann/merge-layers [generated downgrade]
                                                         {:type-derivation :from-callsites}))
                      "rule/a")]
        (is (not (contains? rule :clara-rules/insert-types)))
        (is (= :none (get-in rule [:clara-rules/dynamic-insert-types-detected :resolution])))))))

(deftest derivation-does-not-resurrect
  (let [props (ann/layer {:id :props
                          :annotations {"rule/a" #:clara-rules{:insert-types [:mistaken/t]}}})]
    (testing "a type overruled by :replace stays gone in both modes"
      (let [fix (ann/layer {:id :curated
                            :annotations
                            {"rule/a" #:clara-rules{:insert-types [:right/t]
                                                    :merge-props {:insert-types :replace}}}})]
        (doseq [mode [:additive :from-callsites]]
          (is (= [:right/t]
                 (:clara-rules/insert-types
                  (get (ann/annotations (ann/merge-layers [props fix] {:type-derivation mode}))
                       "rule/a")))))))
    (testing "a tombstoned type stays gone in both modes"
      (let [erase (ann/layer {:id :curated
                              :annotations {"rule/a" #:clara-rules{:insert-types nil}}})]
        (doseq [mode [:additive :from-callsites]]
          (is (not (contains? (get (ann/annotations (ann/merge-layers [props erase]
                                                                      {:type-derivation mode}))
                                   "rule/a")
                              :clara-rules/insert-types))))))))

(deftest derive-conclusions-idempotent
  (let [merged (ann/merge-layers [props-layer-fixture generated-layer curated-layer]
                                 {:type-derivation :from-callsites})
        once (ann/derive-conclusions (ann/annotations merged)
                                     {:type-derivation :from-callsites})]
    (is (= (ann/annotations merged) once))
    (is (= once (ann/derive-conclusions once {:type-derivation :from-callsites})))))

(deftest validate-derivation-dropped-authored-type
  (let [generated (ann/layer {:id :generated
                              :annotations
                              {"rule/a"
                               #:clara-rules{:dynamic-insert-types-detected
                                             {:callsites
                                              [{:callsite-id "id:1"
                                                :source-str "(x)"
                                                :ns-name-sym 'some.ns
                                                :filename "some/ns.clj"
                                                :status :none}]}}}})
        props (ann/layer {:id :props
                          :annotations {"rule/a" #:clara-rules{:insert-types [:props/t]}}})
        findings (:derivation-dropped-authored-type
                  (findings-by-type
                   (ann/validate-layers [props generated]
                                        {:type-derivation :from-callsites})))]
    (testing "the dropped props type is reported with its layer"
      (is (= 1 (count findings)))
      (is (= :props (:layer (first findings))))
      (is (= :insert (:dimension (first findings)))))
    (testing "the check is silent under :additive"
      (is (empty? (:derivation-dropped-authored-type
                   (findings-by-type
                    (ann/validate-layers [props generated]))))))))

;; ---------------------------------------------------------------------------
;; phase 7 — rebase-layer
;; ---------------------------------------------------------------------------

(deftest rebase-layer-test
  (let [curated (ann/layer {:id :curated
                            :annotations
                            {"acme.pricing/discount-rule"
                             #:clara-rules{:dynamic-insert-types-detected
                                           {:callsites
                                            [{:source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                                              :ns-name-sym 'acme.pricing
                                              :filename "acme/pricing.clj"
                                              :constructor-sym 'acme.facts/make-fact
                                              :status :full
                                              :resolved-types [:acme.pricing/gold-discount]
                                              :resolution-evidence {:note "closed map"}}]}}
                             "other.ns/rule"
                             #:clara-rules{:dynamic-insert-types-detected
                                           {:callsites
                                            [{:source-str "(insert! x)"
                                              :ns-name-sym 'other.ns
                                              :filename "other/ns.clj"
                                              :status :none}]}}}})
        rebased (ann/rebase-layer curated '{acme.pricing acme.billing
                                            acme.facts acme.facts-v2})]
    (testing "rule-name keys are remapped"
      (is (contains? (:annotations rebased) "acme.billing/discount-rule")))
    (testing "callsite discovery fields and type tokens are remapped"
      (let [cs (get-in rebased [:annotations "acme.billing/discount-rule"
                                :clara-rules/dynamic-insert-types-detected :callsites 0])]
        (is (= 'acme.billing (:ns-name-sym cs)))
        (is (= 'acme.facts-v2/make-fact (:constructor-sym cs)))
        (is (= "acme/billing.clj" (:filename cs)))
        (is (= [:acme.billing/gold-discount] (:resolved-types cs)))))
    (testing "ids are recomputed from the remapped basis"
      (let [cs (get-in rebased [:annotations "acme.billing/discount-rule"
                                :clara-rules/dynamic-insert-types-detected :callsites 0])]
        (is (= (ann/callsite-id (dissoc cs :callsite-id))
               (subs (:callsite-id cs) 0 (str/last-index-of (:callsite-id cs) ":"))))
        (is (str/starts-with? (:callsite-id cs) "acme.billing:make-fact:"))))
    (testing "unmapped namespaces pass through unchanged"
      (is (= (get (:annotations curated) "other.ns/rule")
             (get (:annotations rebased) "other.ns/rule"))))
    (testing "a rebased curated layer overlays fresh discovery without dangling"
      (let [new-discovery (ann/layer {:id :generated
                                      :annotations
                                      {"acme.billing/discount-rule"
                                       #:clara-rules{:dynamic-insert-types-detected
                                                     {:callsites
                                                      [{:source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                                                        :ns-name-sym 'acme.billing
                                                        :filename "acme/billing.clj"
                                                        :constructor-sym 'acme.facts-v2/make-fact
                                                        :status :none}]}}}})
            merged (ann/merge-layers [new-discovery rebased])
            dm (get-in (ann/annotations merged)
                       ["acme.billing/discount-rule" :clara-rules/dynamic-insert-types-detected])]
        (is (= 1 (count (:callsites dm))))
        (is (not-any? :dangling? (:callsites dm)))
        (is (= :full (:resolution dm)))))))

(deftest rebase-preserves-duplicate-group-ordinals
  (let [dup {:source-str "(insert! x)"
             :ns-name-sym 'acme.pricing
             :constructor-sym 'acme.facts/make-fact
             :filename "acme/pricing.clj"
             :status :none}
        layer (ann/layer {:id :curated
                          :annotations
                          {"acme.pricing/rule"
                           #:clara-rules{:dynamic-insert-types-detected
                                         {:callsites [dup dup]}}}})
        rebased (ann/rebase-layer layer '{acme.pricing acme.billing})
        ids (mapv :callsite-id
                  (get-in rebased [:annotations "acme.billing/rule"
                                   :clara-rules/dynamic-insert-types-detected :callsites]))]
    (is (= 2 (count ids)))
    (is (apply distinct? ids))
    (is (str/ends-with? (first ids) ":0"))
    (is (str/ends-with? (second ids) ":1"))))
