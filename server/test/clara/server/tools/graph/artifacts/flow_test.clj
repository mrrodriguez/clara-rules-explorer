(ns clara.server.tools.graph.artifacts.flow-test
  "The `annotations` flow itself: `persist!` / `merge-persisted!` over a layer
  stack, and the working-memory layer (`memory-delta` / `memory-layer`).

  The layers this writes over come from `annotations.test-fixtures`; the
  individual pieces have their own tests (`.fact-constructors-test`,
  `.var-as-fact-test`, `.store-test`, `.overlay-test`).

  What these pin: a curated overlay is the one artifact here that cannot be
  regenerated, so no machine step may lose it — and the working-memory layer
  must stay a *delta*, so the types it contributes remain attributable to a
  running session rather than to source analysis."
  (:require
   [clara.server.tools.graph.analyze :as analyze]
   [clara.server.tools.graph.annotations.merge :as ann.merge]
   [clara.server.tools.graph.core :as core]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.flow :as ann]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.artifacts.test-fixtures :as fixtures
    :refer [*artifact-opts* generated-annotations merged-insert-types
            record-resolution! stub-rulebase write-generated-layer!]]
   [clara.server.tools.graph.edn-io :as edn-io]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)
(use-fixtures :each fixtures/temp-artifact-dir-fixture)

;; ---------------------------------------------------------------------------
;; persist
;; ---------------------------------------------------------------------------

(deftest persist-never-overwrites-the-overlay-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (let [overlay-file (store/get-artifact-path :agent *artifact-opts*)
        before (slurp overlay-file)]
    (with-redefs [core/->rulebase-analysis (fn [_ _ _] {:rules {}})]
      (ann/persist! {:layer (store/->generated-layer *artifact-opts* generated-annotations)}
                    (assoc *artifact-opts* :session stub-rulebase)))
    (testing "regenerating rewrites every derived artifact"
      (is (= 5 (count (filter #(.exists (io/file (store/get-artifact-path % *artifact-opts*)))
                              [:auto :agent :merged :rulebase-analysis
                               :rulebase-analysis-digest])))))
    (testing "…and leaves the curated overlay byte-identical"
      (is (= before (slurp overlay-file))))
    (testing "…with the curation still folded into the merge"
      (is (= #{:a/two} (merged-insert-types "a.ns/gap-rule"))))))

(deftest merged-artifact-carries-layers-and-provenance-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (with-redefs [core/->rulebase-analysis (fn [_ _ _] {:rules {}})]
    (ann/merge-persisted! (assoc *artifact-opts* :session stub-rulebase)))
  (let [m (store/read-merged-annotations *artifact-opts*)]
    (testing "the merged artifact is a MergedAnnotations value, not a layer"
      (is (= #{:annotations :layers :provenance} (set (keys m))))
      (is (nil? (:id m))))
    (testing "it names the layers that folded, in order — props first, as the base"
      (is (= [:props store/generated-layer-id store/agent-layer-id] (mapv :id (:layers m)))))
    (testing "and says per key where each value came from"
      (is (= :derived (get-in m [:provenance "a.ns/gap-rule" :clara-rules/insert-types]))))))

(deftest merge-is-stored-by-reference-to-the-layers-test
  (let [layer (store/->generated-layer *artifact-opts* generated-annotations)
        opts (assoc *artifact-opts* :session stub-rulebase)]
    (with-redefs [core/->rulebase-analysis (fn [_ _ _] {:rules {}})]
      (ann/persist! {:layer layer} opts))

    (let [written-layer (store/read-layer :auto *artifact-opts*)
          stored (store/read-compact-merged-annotations *artifact-opts*)
          callsites (fn [anns rule]
                      (get-in anns [rule :clara-rules/dynamic-insert-types-detected :callsites]))]

      (testing "the LAYER reaches disk whole. A server is handed this exact file
                (see `clara.server.tools.graph.artifacts.serve`), so anything
                taken out of it would vanish from GET /v1/rules/:fq-name"
        (is (= (:annotations layer) (:annotations written-layer)))
        (is (= "(->fact :a/one x)"
               (:source-str (first (callsites (:annotations written-layer) "a.ns/full-rule"))))))

      (testing "a rule the fold did not change is NOT restated on disk — the
                stored merge names the layer that holds it instead.

                Only `a.ns/side-effect-rule` qualifies here: this fixture's
                detection maps omit `:resolution`, which the fold computes, so
                every rule with callsites legitimately differs from the layer and
                is inlined. Real analyzer output carries it, which is why 3,403
                of a 3,426-rule ruleset's rules go by reference"
        (is (not (contains? (:annotations stored) "a.ns/side-effect-rule")))
        (is (= store/generated-layer-id (get-in stored [:verbatim :default]))))

      (testing "expansion reproduces the in-memory fold exactly, props layer and
                all. This is the whole contract: the file is smaller, not lesser"
        (is (= (store/->merged-annotations opts)
               (store/read-merged-annotations *artifact-opts*))))

      (testing "and a reader of the merge gets WHOLE callsites, evidence
                included, because it is reading them off the layer. The pass
                that used to strip `:via` / `:source-str` here is gone"
        (let [merged (store/read-merged-annotations *artifact-opts*)
              cs (first (callsites (:annotations merged) "a.ns/full-rule"))]
          (is (= :full (:status cs)))
          (is (= [:a/one] (:resolved-types cs)))
          (is (= "(->fact :a/one x)" (:source-str cs)))
          (is (= store/generated-layer-id (:from-layer cs))))))))

(defn- stub-analysis
  "A `RulebaseAnalysis` big enough for collection ordering to show on disk: every
  map is past the array-map threshold, and it carries the reverse directions
  `..slim` drops plus the sets it walks through.

  Sequential values are written in whatever order `->rulebase-analysis` produced
  them — `:ancestors` is shallowest-first, where the order *is* the information,
  and nothing downstream re-sorts the rest. That upstream order is accepted
  rather than normalized, so this fixture reproduces it verbatim and the test
  below asks only that the same input give the same bytes."
  []
  (let [names (mapv #(str "n.ns/rule-" %) (range 20))
        types (mapv #(str "t/type-" %) (range 20))]
    {:rules (into {} (map (fn [n] [n {:name n :ns "n.ns"
                                      :lhs-types types
                                      :insert-types [(first types)]
                                      :source-rule true}]))
                  names)
     :fact-types (into {} (map (fn [t] [t {:name t :ns "t"
                                           :ancestors []
                                           :used-by-rules names
                                           :descendants []}]))
                       types)
     :dep-graph (into {} (map (fn [n] [n {:upstream (set (remove #{n} names))
                                          :downstream #{}}]))
                      names)
     :unresolved []}))

(deftest persisting-twice-is-byte-identical-test
  (testing "the registry is a query store nobody reviews, so a diff is not how
            anyone reads it — but a regeneration that rewrites bytes without
            changing content makes 'did anything change?' unanswerable by any
            means, including hashing the file. Every artifact a persist writes
            must be a pure function of its input.

            This is the acceptance criterion for the whole persistence format:
            drop a key, move evidence to a sidecar, split a file — none of it
            may introduce an unordered collection on the way to disk."
    (let [dir (io/file (store/get-out-dir *artifact-opts*))
          ;; every file the run wrote, by path relative to the run dir — so a
          ;; part file added under merged-rulebase-analysis/ is covered without
          ;; this test being told about it
          snapshot (fn []
                     (into (sorted-map)
                           (comp (filter #(.isFile ^java.io.File %))
                                 (map (fn [^java.io.File f]
                                        [(subs (.getPath f) (count (.getPath dir))) (slurp f)])))
                           (file-seq dir)))
          persist-once (fn []
                         (with-redefs [core/->rulebase-analysis (fn [_ _ _] (stub-analysis))]
                           (ann/persist! {:layer (store/->generated-layer *artifact-opts* generated-annotations)}
                                         (assoc *artifact-opts* :session stub-rulebase)))
                         (snapshot))
          first-run (persist-once)
          second-run (persist-once)]
      (is (seq first-run) "the run wrote nothing — this would pass vacuously")
      (is (= (set (keys first-run)) (set (keys second-run)))
          "a second persist wrote a different set of files")
      (doseq [path (keys first-run)]
        (is (= (get first-run path) (get second-run path))
            (str path " is not a pure function of its input"))))))

(deftest merge-refuses-a-stale-analysis-test
  (write-generated-layer!)
  (record-resolution! "a.ns/gap-rule" 0 [:a/two])
  (testing "an analysis that does not say which merge it is of cannot be shown to
            describe this one, so with no session there is nothing honest to write"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pass :session"
                          (ann/merge-persisted!
                           (assoc *artifact-opts*
                                  :rulebase-analysis-in-hand {:rulebase-analysis (delay {:rules {}})}))))))

(deftest merge-reuses-an-analysis-of-the-same-merge-test
  (write-generated-layer!)
  (let [builds (atom 0)]
    (with-redefs [core/->rulebase-analysis (fn [_ _ _] (swap! builds inc) {:rules {}})]
      (let [in-hand (ann/->deferred-rulebase-analysis
                     stub-rulebase (store/->merged-annotations *artifact-opts*))]
        (testing "an analysis of this exact merge is written as-is — no session,
                  no rebuild. `->rulebase-analysis` is a function of the rulebase
                  and the merge alone, so the merge is the whole precondition"
          (ann/merge-persisted! (assoc *artifact-opts* :rulebase-analysis-in-hand in-hand))
          (is (= 1 @builds) "the deferred analysis is forced, and only once")
          (is (.exists (store/get-artifact-file :rulebase-analysis *artifact-opts*))))
        (testing "…and once a layer moves the merge out from under it, the session
                  builds a new one rather than the stale one being reused"
          (record-resolution! "a.ns/gap-rule" 0 [:a/two])
          (ann/merge-persisted! (assoc *artifact-opts*
                                       :session stub-rulebase
                                       :rulebase-analysis-in-hand in-hand))
          (is (= 2 @builds)))))))

(def ^:private a-merge
  "A `MergedAnnotations`-shaped value, which is what a `deferred-rulebase-analysis`
  pair is stamped from. `:layers` and `:provenance` are along for the ride —
  the stamp is `:annotations` alone."
  {:annotations {"a.ns/r" #:clara-rules{:insert-types [:a/one]}}
   :layers [{:id :some-layer}]
   :provenance {}})

(deftest deferred-analysis-is-not-built-until-asked-test
  (let [builds (atom 0)
        opts-seen (atom [])]
    (with-redefs [core/->rulebase-analysis (fn [_ _ opts]
                                             (swap! builds inc)
                                             (swap! opts-seen conj opts)
                                             {:rules {}})]
      (let [in-hand (ann/->deferred-rulebase-analysis stub-rulebase a-merge)]
        (testing "constructing the pair runs nothing"
          (is (zero? @builds)))
        (testing "`get-rulebase-analysis` is the one way in, and it builds once"
          (is (= {:rules {}} (ann/get-rulebase-analysis in-hand)))
          (is (= {:rules {}} (ann/get-rulebase-analysis in-hand)))
          (is (= 1 @builds)))
        (testing "…through the repo's own form printer, never the explorer's
                  `clojure.pprint` default"
          (is (= [edn-io/pretty-edn-str] (map :form-printer @opts-seen))))
        (testing "…and is nil, not a throw, on something carrying none"
          (is (nil? (ann/get-rulebase-analysis {:layer ::no-analysis-here}))))
        (testing "`rulebase-analysis-of?` answers without building anything"
          (is (true? (ann/rulebase-analysis-of? in-hand a-merge)))
          (is (false? (ann/rulebase-analysis-of? in-hand (assoc-in a-merge [:annotations "a.ns/r"]
                                                                   #:clara-rules{:insert-types [:a/two]}))))
          (is (false? (ann/rulebase-analysis-of? {} a-merge))))
        (testing "the stamp is the annotations alone, so a merge that folded
                  different layers to the same annotations still reuses"
          (is (true? (ann/rulebase-analysis-of? in-hand (assoc a-merge :layers [{:id :other-layer}])))))))))

;; ===========================================================================
;; the working-memory layer
;;
;; What makes this layer worth having is that it stays *separate*: the types it
;; contributes are attributable to a running session rather than to source
;; analysis. Each test below pins one way that could quietly stop being true.
;; ===========================================================================

(defn- memory-delta-for
  "One rule's working-memory delta, through the public
  `ann/->memory-delta`:
  `enriched` stands in for what `merge-memory-derived-insert-types` would have
  returned for the rule `base` describes, so the session — `stub-rulebase`, only
  ever there to satisfy the schema — is never touched.

  The per-rule diff is the explorer's (`clara.server.tools.graph.annotations/annotations-delta`); what
  these tests pin is the property this repo depends on — that the layer carries
  the delta and nothing more — against an upstream that could quietly stop
  providing it."
  [rule-name base enriched]
  (with-redefs [analyze/merge-memory-derived-insert-types (fn [_ _] {rule-name enriched})]
    (get (ann/->memory-delta {:session stub-rulebase :annotations {rule-name base}})
         rule-name)))

(defn- memory-delta [base enriched]
  (memory-delta-for "a.ns/r" base enriched))

(deftest memory-delta-carries-only-what-is-new-test
  (testing "nothing new means no entry at all — not an entry restating the base"
    (is (nil? (memory-delta {:clara-rules/insert-types [:a/one]}
                            {:clara-rules/insert-types [:a/one]}))))
  (testing "only the added type, so the union merge attributes both layers"
    (is (= {:clara-rules/insert-types [:a/two]}
           (dissoc (memory-delta {:clara-rules/insert-types [:a/one]}
                                 {:clara-rules/insert-types [:a/one :a/two]})
                   :clara-rules/dynamic-insert-types-detected)))))

(deftest memory-delta-compares-types-the-way-the-merge-does-test
  (testing "a symbol and the Class it resolves to under the rule's own namespace
            are one type, not two — both sides go through
            `clara.server.tools.graph.serialize/resolve-type`, the same normalization the fold uses"
    (is (nil? (memory-delta-for "clara.server.tools.graph.artifacts.flow-test/r"
                                {:clara-rules/insert-types ['String]}
                                {:clara-rules/insert-types [String]}))))
  (testing "but the comparison is kind-explicit, so a keyword fact type and a
            string one stay distinct — clara treats them as different types, and
            collapsing them would silently drop one"
    (is (= {:clara-rules/insert-types [:a/one]}
           (memory-delta {:clara-rules/insert-types ["a/one"]}
                         {:clara-rules/insert-types [:a/one]})))))

(deftest memory-delta-covers-both-dimensions-test
  (testing "retract is handled symmetrically with insert, whether or not
            enrichment populates it today"
    (is (= {:clara-rules/retract-types [:a/gone]
            :clara-rules/dynamic-retract-types-detected
            {:fact-instance-derived-types ["a/gone"]}}
           (memory-delta {:clara-rules/retract-types [:a/one]}
                         {:clara-rules/retract-types [:a/one :a/gone]
                          :clara-rules/dynamic-retract-types-detected
                          {:fact-instance-derived-types ["a/gone"]}}))))
  (testing "both dimensions at once"
    (is (= {:clara-rules/insert-types [:a/in] :clara-rules/retract-types [:a/out]}
           (memory-delta {} {:clara-rules/insert-types [:a/in]
                             :clara-rules/retract-types [:a/out]})))))

(deftest memory-delta-carries-the-audit-trail-test
  (testing "the audit trail is carried whether or not the base has callsites"
    (is (= {:fact-instance-derived-types ["a/two"]}
           (:clara-rules/dynamic-insert-types-detected
            (memory-delta {:clara-rules/insert-types [:a/one]}
                          {:clara-rules/insert-types [:a/one :a/two]
                           :clara-rules/dynamic-insert-types-detected
                           {:fact-instance-derived-types ["a/two"]}}))))
    (is (= {:fact-instance-derived-types ["a/two"]}
           (:clara-rules/dynamic-insert-types-detected
            (memory-delta {:clara-rules/insert-types [:a/one]
                           :clara-rules/dynamic-insert-types-detected
                           {:callsites [{:callsite-id "c" :status :full}]}}
                          {:clara-rules/insert-types [:a/one :a/two]
                           :clara-rules/dynamic-insert-types-detected
                           {:fact-instance-derived-types ["a/two"]}}))))))

(deftest memory-delta-never-clobbers-callsites-test
  (testing "folding the delta over a generated layer that HAS callsites keeps
            both — the analyzer's audit trail and this layer's derived types.
            Structural in `ann.merge/fold-detection-key` since clara-rules-explorer
            2fbe58a; before that the delta had to withhold the derived types,
            and a regression upstream would silently lose one side again"
    (let [base {:clara-rules/insert-types [:a/one]
                :clara-rules/dynamic-insert-types-detected
                {:callsites [{:ns-name-sym 'a.ns
                              :source-str "(->fact :a/one)"
                              :status :full}]}}
          delta (memory-delta base
                              {:clara-rules/insert-types [:a/one :a/two]
                               :clara-rules/dynamic-insert-types-detected
                               {:fact-instance-derived-types ["a/two"]}})
          merged (ann.merge/merge-layers
                  [(ann.merge/->layer {:id :generated :annotations {"a.ns/r" base}})
                   (ann.merge/->layer {:id :memory :annotations {"a.ns/r" delta}})])
          detected (get-in merged [:annotations "a.ns/r"
                                   :clara-rules/dynamic-insert-types-detected])]
      (is (= ["a/two"] (:fact-instance-derived-types detected)))
      (is (= ["(->fact :a/one)"] (mapv :source-str (:callsites detected))))
      (is (= [:generated :memory]
             (get-in merged [:provenance "a.ns/r"
                             :clara-rules/dynamic-insert-types-detected]))))))

(deftest memory-delta-tombstones-no-output-types-test
  (testing "observing a rule produce something disproves :no-output-types, and
            leaving it set suppresses sink classification downstream"
    (is (contains? (memory-delta {:clara-rules/no-output-types true}
                                 {:clara-rules/insert-types [:a/two]})
                   :clara-rules/no-output-types))
    (is (nil? (:clara-rules/no-output-types
               (memory-delta {:clara-rules/no-output-types true}
                             {:clara-rules/insert-types [:a/two]})))))
  (testing "no tombstone when the base never claimed it"
    (is (not (contains? (memory-delta {} {:clara-rules/insert-types [:a/two]})
                        :clara-rules/no-output-types)))))

(deftest memory-layer-folds-with-honest-provenance-test
  (let [generated (ann.merge/->layer
                   {:id store/generated-layer-id
                    :annotations {"a.ns/r" {:clara-rules/insert-types [:a/one]
                                            :clara-rules/no-output-types true
                                            :clara-rules/dynamic-insert-types-detected
                                            {:callsites [{:ns-name-sym 'a.ns
                                                          :source-str "(->fact :a/one)"
                                                          :status :full}]}}}})
        memory (store/->memory-layer {:generated-by fixtures/generated-by}
                                     {"a.ns/r" {:clara-rules/insert-types [:a/two]
                                                :clara-rules/no-output-types nil}})
        merged (ann.merge/merge-layers [generated memory])
        entry (get-in merged [:annotations "a.ns/r"])]
    (testing "the merged types are the union of both passes"
      (is (= #{:a/one :a/two} (set (:clara-rules/insert-types entry)))))
    (testing "and provenance records both as contributing — the whole point of
              keeping this a separate layer"
      (is (= [store/generated-layer-id store/memory-layer-id]
             (get-in merged [:provenance "a.ns/r" :clara-rules/insert-types]))))
    (testing "the analyzer's callsites survive underneath"
      (is (= 1 (count (get-in entry [:clara-rules/dynamic-insert-types-detected :callsites])))))
    (testing "the tombstone erases the stale claim"
      (is (not (contains? entry :clara-rules/no-output-types))))))

(deftest memory-layer-is-nil-when-nothing-was-observed-test
  (testing "an empty delta yields no layer, so `:layers` in the merged artifact
            stays an honest record of what actually contributed"
    ;; enrichment returning the annotations unchanged is exactly the
    ;; nothing-new case; the session itself is never touched here.
    (with-redefs [analyze/merge-memory-derived-insert-types (fn [anns _] anns)]
      (is (nil? (ann/->memory-layer
                 {:session stub-rulebase
                  :generated-by fixtures/generated-by
                  :annotations {"a.ns/r" {:clara-rules/insert-types [:a/one]}}})))))
  (testing "and a layer when something was"
    (with-redefs [analyze/merge-memory-derived-insert-types
                  (fn [anns _] (assoc-in anns ["a.ns/r" :clara-rules/insert-types] [:a/one :a/two]))]
      (let [layer (ann/->memory-layer
                   {:session stub-rulebase
                    :generated-by fixtures/generated-by
                    :annotations {"a.ns/r" {:clara-rules/insert-types [:a/one]}}})]
        (is (= store/memory-layer-id (:id layer)))
        (is (= {"a.ns/r" {:clara-rules/insert-types [:a/two]}} (:annotations layer)))))))