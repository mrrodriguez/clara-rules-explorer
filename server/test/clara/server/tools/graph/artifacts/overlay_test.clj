(ns clara.server.tools.graph.artifacts.overlay-test
  "The curated overlay: the work list, recording what a pass settled, what the
  merge derives from it, and the lint over the stack.

  Two invariants these tests exist to protect:

  1. The overlay holds only what was *settled*. Unresolved callsites belong to
     `get-unresolved-report`, computed from the merge; duplicating them into a file
     would create a second work list to keep in step with the first.
  2. A sparse overlay entry never costs the analyzer's callsite audit trail —
     the merge is keyed by `:callsite-id` and merges field by field."
  (:require
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.overlay :as overlay]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts* discovered-callsite-id merged-insert-callsites
            merged-insert-types record-resolution! write-generated-layer!]]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

;; ---------------------------------------------------------------------------
;; the work list, and recording resolutions
;;
;; The overlay holds ONLY what a pass settled. Unresolved callsites live in
;; `get-unresolved-report`, computed from the merge — writing them to a file too
;; would be a second copy of the work list that has to be kept in step with the
;; first. These tests pin that: the file never grows an entry for a gap nobody
;; closed.
;; ---------------------------------------------------------------------------

(deftest get-unresolved-report-is-the-work-list-test
  (write-generated-layer!)
  (let [{:keys [summary rules]} (overlay/get-unresolved-report *artifact-opts*)]
    (testing "exactly the rules with a gap — not the resolved ones"
      (is (= 2 (:rules summary)))
      (is (= ["a.ns/gap-rule" "a.ns/partial-rule"] (keys rules)))
      (is (= {:none 2} (:by-resolution summary))))
    (testing "and within a partially-resolved rule, only the unresolved callsite"
      (is (= ["(dissoc m :input)"]
             (map :source-str (get-in rules ["a.ns/partial-rule" :insert :callsites])))))
    (testing "each carries the id a resolution is recorded against"
      (is (= (discovered-callsite-id "a.ns/gap-rule" 0)
             (:callsite-id (first (get-in rules ["a.ns/gap-rule" :insert :callsites]))))))))

(deftest no-overlay-file-until-something-is-resolved-test
  (write-generated-layer!)
  (testing "nothing stages a file of gaps — the report is the work list"
    (is (nil? (store/read-layer :agent *artifact-opts*)))
    (is (not (.exists (io/file (store/get-artifact-path :agent *artifact-opts*)))))))

(deftest recorded-entries-hold-only-the-conclusion-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (let [overlay (store/read-layer :agent *artifact-opts*)]
    (testing "only the settled rule is in the file — the other gap stays absent"
      (is (= ["a.ns/gap-rule"] (keys (:annotations overlay))))
      (is (contains? (:rules (overlay/get-unresolved-report *artifact-opts*)) "a.ns/partial-rule")
          "…and is still reported as work"))
    (testing "the entry is the join key, the conclusion, and a witness"
      (let [cs (-> overlay :annotations (get "a.ns/gap-rule")
                   :clara-rules/dynamic-insert-types-detected :callsites first)]
        (is (= #{:callsite-id :source-str :status :resolved-types :resolution-evidence}
               (set (keys cs))))
        (is (= :full (:status cs)))
        (is (= [:a/two] (:resolved-types cs)))
        (is (= "(->fact (type-for ?x) {:k v})" (:source-str cs))
            "filled in from the discovered callsite — the caller need not restate it")))
    (testing "and no rule-level conclusions — those are the merge's to derive"
      (is (= [:clara-rules/dynamic-insert-types-detected]
             (keys (get (:annotations overlay) "a.ns/gap-rule")))))))

(deftest recording-is-additive-across-passes-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (record-resolution! "a.ns/partial-rule" 1 [:a/four])
  (testing "a later pass adds to the overlay rather than replacing it"
    (is (= ["a.ns/gap-rule" "a.ns/partial-rule"]
           (keys (:annotations (store/read-layer :agent *artifact-opts*)))))
    (is (= #{:a/two} (merged-insert-types "a.ns/gap-rule")))
    (is (contains? (merged-insert-types "a.ns/partial-rule") :a/four))))

(deftest re-recording-an-id-corrects-it-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/wrong])
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (let [callsites (-> (store/read-layer :agent *artifact-opts*) :annotations
                      (get "a.ns/gap-rule")
                      :clara-rules/dynamic-insert-types-detected :callsites)]
    (testing "one entry, corrected — not two the merge would have to arbitrate"
      (is (= 1 (count callsites)))
      (is (= [:a/two] (:resolved-types (first callsites)))))
    (is (= #{:a/two} (merged-insert-types "a.ns/gap-rule")))))

(deftest replace-starts-the-overlay-over-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (overlay/record-resolutions! [] (assoc *artifact-opts* :replace? true))
  (is (empty? (:annotations (store/read-layer :agent *artifact-opts*)))
      ":replace? is the explicit, only destructive path"))

(deftest recording-an-unknown-id-is-reported-test
  (write-generated-layer!)
  (let [result (overlay/record-resolutions!
                [{:callsite-id "a.ns:->fact:deadbeef:0"
                  :rule "a.ns/gap-rule"
                  :resolved-types [:a/nope]}]
                *artifact-opts*)]
    (testing "written, but named — hiding it would only delay the discovery"
      (is (= ["a.ns:->fact:deadbeef:0"] (:unknown-callsite-ids result))))
    (testing "and the merge quarantines it rather than letting it make an edge"
      (is (not (contains? (merged-insert-types "a.ns/gap-rule") :a/nope))))))

(deftest recording-an-unknown-id-with-no-rule-throws-test
  (write-generated-layer!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :rule to file it under"
                        (overlay/record-resolutions!
                         [{:callsite-id "nope:x:00000000:0" :resolved-types [:a/x]}]
                         *artifact-opts*))))

(deftest recording-requires-a-generated-layer-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No generated layer"
                        (overlay/record-resolutions! [] *artifact-opts*))))

;; ---------------------------------------------------------------------------
;; merge — what the layer model buys, stated as tests
;; ---------------------------------------------------------------------------

(deftest curated-callsite-becomes-an-edge-with-no-finalize-step-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (testing "the rule-level type is derived by the merge from the callsite alone —
            a curator never writes it, so it cannot drift from the evidence"
    (is (= #{:a/two} (merged-insert-types "a.ns/gap-rule"))))
  (testing "and the dimension resolution is recomputed"
    (is (= :full (-> (store/->merged-annotations *artifact-opts*)
                     :annotations (get "a.ns/gap-rule")
                     :clara-rules/dynamic-insert-types-detected :resolution)))))

(deftest sparse-overlay-preserves-the-analyzer-audit-trail-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (testing "the discovery fields the overlay never restated survive the merge,
            because the merge is keyed by :callsite-id and merges field-by-field"
    (let [cs (first (merged-insert-callsites "a.ns/gap-rule"))]
      (is (= "a_ns.clj" (:filename cs)))
      (is (= 'acme.facts/->fact (:constructor-sym cs)))
      (is (= 'a.ns (:ns-name-sym cs)))))
  (testing "and the merge records which layer drew the conclusion"
    (is (= store/agent-layer-id
           (:from-layer (first (merged-insert-callsites "a.ns/gap-rule")))))))

(deftest merge-keeps-partial-honest-test
  (write-generated-layer!)
  (record-resolution! "a.ns/partial-rule" 0 [:a/three])
  (testing "curating the already-resolved callsite leaves the other one open"
    (is (= :partial (-> (store/->merged-annotations *artifact-opts*)
                        :annotations (get "a.ns/partial-rule")
                        :clara-rules/dynamic-insert-types-detected :resolution))))
  (testing "already-known types survive the union"
    (is (= #{:a/three} (merged-insert-types "a.ns/partial-rule")))))

(deftest untouched-rules-pass-through-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (let [m (:annotations (store/->merged-annotations *artifact-opts*))]
    (is (= #{:a/one} (merged-insert-types "a.ns/full-rule")))
    (is (true? (:clara-rules/no-output-types (get m "a.ns/side-effect-rule"))))))

(deftest stale-callsite-id-is-quarantined-not-believed-test
  (write-generated-layer!)
  (store/write-layer!
   :agent *artifact-opts*
   (ann.merge/->layer
    {:id store/agent-layer-id
     :annotations {"a.ns/gap-rule"
                   #:clara-rules{:dynamic-insert-types-detected
                                 {:callsites [{:callsite-id "a.ns:->fact:deadbeef:0"
                                               :status :full
                                               :resolved-types [:a/wrong]}]}}}}))
  (testing "an id matching no discovered form contributes no edge"
    (is (not (contains? (merged-insert-types "a.ns/gap-rule") :a/wrong))))
  (testing "…but is kept and flagged, not silently dropped"
    (let [stale (first (filter :dangling? (merged-insert-callsites "a.ns/gap-rule")))]
      (is (some? stale))
      (is (= store/agent-layer-id (:from-layer stale)))))
  (testing "and lint reports it against the layer that wrote it"
    (is (contains? (set (map :type (overlay/lint-layers *artifact-opts*)))
                   :dangling-callsite))))

(deftest lint-flags-a-conclusion-with-no-types-test
  (write-generated-layer!)
  (store/write-layer!
   :agent *artifact-opts*
   (ann.merge/->layer
    {:id store/agent-layer-id
     :annotations {"a.ns/gap-rule"
                   #:clara-rules{:dynamic-insert-types-detected
                                 {:callsites [{:callsite-id (discovered-callsite-id "a.ns/gap-rule" 0)
                                               :status :full}]}}}}))
  (is (= [:resolved-without-types]
         (map :type (filter #(= :error (:severity %)) (overlay/lint-layers *artifact-opts*))))
      "a callsite claiming :full with no :resolved-types contributes no edge"))

(deftest lint-flags-an-unknown-rule-test
  (write-generated-layer!)
  (store/write-layer!
   :agent *artifact-opts*
   (ann.merge/->layer
    {:id store/agent-layer-id
     :annotations {"a.ns/typo-rule" #:clara-rules{:insert-types [:a/x]}}}))
  (is (contains? (set (map :type (overlay/lint-layers *artifact-opts*))) :unknown-rule)
      "a typo'd rule name would otherwise merge in as a phantom entry"))

(deftest lint-passes-a-sound-overlay-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (is (= [] (overlay/lint-layers *artifact-opts*))))
