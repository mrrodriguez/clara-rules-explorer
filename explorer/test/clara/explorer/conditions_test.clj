(ns clara.explorer.conditions-test
  (:require [clara.rules.accumulators :as acc]
            [clara.rules.compiler :as com]
            [clara.explorer.conditions :as conditions]
            [clara.explorer.test.rules.loan-app-facts]
            [clara.explorer.test.rules.loan-doc-rules]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st])
  (:import [clara.explorer.test.rules.loan_app_facts
            Application
            GivenDocument]))

(use-fixtures :once st/validate-schemas)

(def prod-ns 'clara.explorer.test.rules.loan-doc-rules)
(def my-all (acc/all))

(deftest test-normalize-lhs
  (let [raw [[:and {:type Application :constraints []}
              [:or {:type GivenDocument :constraints []}
               {:type GivenDocument :constraints []}]]]
        normalized (conditions/normalize-lhs raw)]
    (testing "groups become homogeneous maps"
      (is (= :and (get-in normalized [0 :condition-type])))
      (is (= :or (get-in normalized [0 :children 1 :condition-type]))))
    (testing "raw forms are retained for the binding walk"
      (is (= raw (conditions/get-raw-lhs normalized)))))

  (testing "accumulator :from subtrees are normalized and raw is retained"
    (let [raw [{:accumulator '(clara.rules.accumulators/all)
                :from [:not {:type GivenDocument :constraints []}]
                :result-binding :?docs}]
          normalized (conditions/normalize-lhs raw)]
      (is (= :not (get-in normalized [0 :from :condition-type])))
      (is (= raw (conditions/get-raw-lhs normalized))))))

(deftest test-normalize-lhs--idempotent
  (testing "re-normalizing an already-normalized LHS does not double-wrap :raw-condition"
    (let [raw [{:accumulator '(clara.rules.accumulators/all)
                :from {:type GivenDocument :constraints []}
                :result-binding :?docs}]
          once (conditions/normalize-lhs raw)]
      (is (= once (conditions/normalize-lhs once)))
      (is (= raw (conditions/get-raw-lhs (conditions/normalize-lhs once)))))))

(deftest test-normalize-lhs--malformed-group-head
  (testing "a group vector whose head is not a keyword/symbol throws a clear ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported LHS condition shape"
                          (conditions/normalize-lhs
                           [[{:type Application :constraints []}]])))))

(deftest test-extract-walkers--non-normalized-throws
  (testing "extract-lhs-fact-types throws on raw group vectors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected a normalized LHS condition"
                          (conditions/extract-lhs-fact-types
                           [[:or {:type Application :constraints []}]]))))
  (testing "extract-lhs-fact-types throws on raw accumulator maps"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected a normalized LHS condition"
                          (conditions/extract-lhs-fact-types
                           [{:accumulator '(clara.rules.accumulators/all)
                             :from {:type GivenDocument :constraints []}}]))))
  (testing "extract-lhs-fact-types throws on raw leaf maps"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected a normalized LHS condition"
                          (conditions/extract-lhs-fact-types
                           [{:type Application :constraints []}]))))
  (testing "extract-var-bindings throws on raw group vectors"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected a normalized LHS condition"
                          (conditions/extract-var-bindings
                           [[:or {:type Application
                                  :constraints []
                                  :fact-binding :?a}]])))))

(deftest test-accumulator-info
  (testing "inline accumulator with a non-nil initial-value"
    (is (= {:form '(clara.rules.accumulators/all)
            :some-initial-value? true}
           (conditions/accumulator-info '(clara.rules.accumulators/all) prod-ns))))

  (testing "inline accumulator with a nil initial-value"
    (is (= {:form '(clara.rules.accumulators/min :temperature)
            :some-initial-value? false}
           (conditions/accumulator-info '(clara.rules.accumulators/min :temperature) prod-ns))))

  (testing "a var holding an accumulator"
    (is (= {:form 'clara.explorer.conditions-test/my-all
            :some-initial-value? true}
           (conditions/accumulator-info 'clara.explorer.conditions-test/my-all prod-ns))))

  (testing "unevaluable accumulator forms throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Failed to evaluate accumulator form"
                          (conditions/accumulator-info '(this-does-not-exist) prod-ns)))))

(deftest test-augment-lhs
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             {:accumulator '(clara.rules.accumulators/all)
              :from {:type GivenDocument
                     :constraints '[(= ?app-id app-id)]}
              :result-binding :?docs}
             {:type :extracted-doc-meta
              :constraints []
              :fact-binding :?extract-doc-meta}]
        augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})]

    (testing "leaf conditions are augmented with a nested bindings summary"
      (is (= {:binding-keys []
              :new-bindings [:?app-id]}
             (:bindings (first augmented)))))

    (testing "non-accumulator leaf contents are preserved"
      (is (= Application (:type (first augmented))))
      (is (= '[(= ?app-id app-id)] (:constraints (first augmented)))))

    (testing "accumulator conditions carry accumulator info and binding info"
      (let [acc-entry (second augmented)]
        (is (= {:form '(clara.rules.accumulators/all)
                :some-initial-value? true}
               (:accumulator acc-entry)))
        (is (= {:binding-keys [:?app-id]
                :new-bindings []}
               (:bindings acc-entry)))
        (is (= :?docs (:result-binding acc-entry)))))

    (testing "fact-binding leaf is augmented too"
      (let [fact-entry (nth augmented 2)]
        (is (= {:binding-keys []
                :new-bindings []}
               (:bindings fact-entry)))))))

(deftest test-augment-lhs--shared-cache-reuses-work
  (testing "a shared cache reuses classification, node conversion, and accumulator eval across equal LHS forms"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               {:accumulator '(clara.rules.accumulators/all)
                :from {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}
                :result-binding :?docs}]
          normalized (conditions/normalize-lhs lhs)
          cache (atom {})
          opts {:prod-ns prod-ns :env nil :cache cache}
          analyze-calls (atom 0)
          to-node-calls (atom 0)
          accum-calls (atom 0)
          orig-analyze com/analyze-condition
          orig-to-node com/condition-to-node
          orig-accum conditions/accumulator-info]
      (with-redefs [com/analyze-condition (fn [c]
                                            (swap! analyze-calls inc)
                                            (orig-analyze c))
                    com/condition-to-node (fn [c env parent]
                                            (swap! to-node-calls inc)
                                            (orig-to-node c env parent))
                    conditions/accumulator-info (fn [form pns]
                                                  (swap! accum-calls inc)
                                                  (orig-accum form pns))]
        (let [first-aug (conditions/augment-lhs normalized opts)
              analyze-after-first @analyze-calls
              to-node-after-first @to-node-calls
              accum-after-first @accum-calls
              second-aug (conditions/augment-lhs normalized opts)]
          (is (= first-aug second-aug))
          (is (pos? analyze-after-first))
          (is (pos? to-node-after-first))
          (is (pos? accum-after-first))
          (is (= analyze-after-first @analyze-calls)
              "second call reuses com/analyze-condition results")
          (is (= to-node-after-first @to-node-calls)
              "second call reuses com/condition-to-node results")
          (is (= accum-after-first @accum-calls)
              "second call reuses accumulator-info results"))))))

(deftest test-augment-lhs--join-filter-join-bindings
  (testing "non-equality unifications referencing an upstream binding are surfaced"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               {:type Application
                :constraints '[(> ?app-id 1000)]}]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          entry (second augmented)]
      (is (= {:binding-keys []
              :new-bindings []
              :join-filter-join-bindings [:?app-id]}
             (:bindings entry)))))

  (testing "non-equality unifications that do not reference an upstream binding are omitted"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               {:type Application
                :constraints '[(> ?d 1000)]
                :fact-binding :?d}]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          entry (second augmented)]
      (is (= {:binding-keys []
              :new-bindings []}
             (:bindings entry)))
      (is (not (contains? (:bindings entry) :join-filter-join-bindings))))))

(deftest test-augment-lhs--unsatisfiable
  (testing "an unsatisfiable LHS throws rather than looping"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not previously bound"
                          (conditions/augment-lhs
                           (conditions/normalize-lhs [{:constraints '[(> ?n 0)]}])
                           {:prod-ns prod-ns :env nil})))))

(deftest test-augment-lhs--compound-negation-augmented
  (testing "compound negation groups attach nested leaves from the sub-scope walk"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:not [:and {:type Application
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})
          not-entry (second augmented)
          and-entry (first (:children not-entry))]
      (is (= :not (:condition-type not-entry)))
      (is (= :and (:condition-type and-entry)))
      (is (every? #(contains? % :bindings) (:children and-entry))
          "compound-negation leaves must be augmented from the sub-scope walk")
      (is (contains? not-entry :bindings))
      (is (contains? and-entry :bindings)))))

(deftest test-augment-lhs--exists-deterministic
  (testing "repeated augmentations of the same :exists LHS are stable and synthetic-free"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:exists {:type GivenDocument
                         :constraints '[(= ?app-id app-id)]}]]
          normalized (conditions/normalize-lhs lhs)
          a1 (conditions/augment-lhs normalized {:prod-ns prod-ns :env nil})
          a2 (conditions/augment-lhs normalized {:prod-ns prod-ns :env nil})
          exists-entry (second a1)
          child (first (:children exists-entry))]
      (is (= a1 a2))
      (is (= (:bindings exists-entry) (:bindings child))
          "group and child carry the same bindings")
      (is (not (re-find #"__exists__" (pr-str a1)))
          ":?__exists__… appears nowhere"))))

(deftest test-augment-lhs--not-group
  (let [lhs [{:type Application
              :constraints '[(= ?app-id app-id)]}
             [:not {:type Application
                    :constraints '[(= ?app-id app-id)]}]]
        augmented (conditions/augment-lhs (conditions/normalize-lhs lhs) {:prod-ns prod-ns :env nil})
        not-entry (second augmented)
        not-leaf (first (:children not-entry))]
    (testing "group entries are normalized maps"
      (is (= :not (:condition-type not-entry)))
      (is (map? not-leaf)))

    (testing "the nested leaf inside :not is augmented"
      (is (= {:binding-keys [:?app-id]
              :new-bindings []}
             (:bindings not-leaf))))

    (testing "the :not group carries the union of its children"
      (is (= (:bindings not-leaf) (:bindings not-entry))))))

(defn- union-of
  "Test helper: componentwise union of `:bindings` maps over `children`."
  [children]
  (let [binding-keys (into #{} (mapcat (comp :binding-keys :bindings)) children)
        new-bindings (into #{} (mapcat (comp :new-bindings :bindings)) children)
        join-filter (into #{} (mapcat (comp :join-filter-join-bindings :bindings)) children)]
    (cond-> {:binding-keys (vec (sort-by name binding-keys))
             :new-bindings (vec (sort-by name new-bindings))}
      (seq join-filter)
      (assoc :join-filter-join-bindings (vec (sort-by name join-filter))))))

(defn- every-group
  "Collects every group node in an augmented LHS tree (walking `:children`, not
   accumulator `:from` subtrees, which share the accumulator's bindings)."
  [augmented]
  (mapcat (fn [node]
            (when (and (map? node) (contains? node :children))
              (cons node (every-group (:children node)))))
          augmented))

(defn- every-node
  "Collects every leaf and group node in an augmented LHS tree (walking
   `:children`, not accumulator `:from` subtrees)."
  [augmented]
  (mapcat (fn [node]
            (if (and (map? node) (contains? node :children))
              (cons node (every-node (:children node)))
              [node]))
          augmented))

(deftest test-augment-lhs--flat-or
  (testing "flat :or: both branches attached; group equals their union"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id) (= ?k kind)]}
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          or-entry (second augmented)
          [doc flag] (:children or-entry)]
      (is (= {:binding-keys [:?app-id] :new-bindings [:?k]} (:bindings doc)))
      (is (= {:binding-keys [:?app-id] :new-bindings []} (:bindings flag)))
      (is (= {:binding-keys [:?app-id] :new-bindings [:?k]} (:bindings or-entry)))
      (is (= (union-of (:children or-entry)) (:bindings or-entry))))))

(deftest test-augment-lhs--nested-or-over-and
  (testing "nested :or over :and: every leaf attached at its real path"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or [:and {:type GivenDocument
                           :constraints '[(= ?app-id app-id)]}
                     {:type GivenDocument
                      :constraints '[(= ?app-id app-id) (= ?k kind)]}]
                [:and {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          or-entry (second augmented)
          [and-a and-b] (:children or-entry)
          [doc flag] (:children and-a)
          [doc2] (:children and-b)]
      (is (every? #(contains? % :bindings) [or-entry and-a and-b doc flag doc2]))
      (is (= (union-of (:children and-a)) (:bindings and-a)))
      (is (= (union-of (:children and-b)) (:bindings and-b)))
      (is (= (union-of (:children or-entry)) (:bindings or-entry)))
      (is (= {:binding-keys [:?app-id] :new-bindings [:?k]} (:bindings or-entry))))))

(deftest test-augment-lhs--or-identical-children
  (testing ":or with identical children: both attached, no duplicate-path throw"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id)]}
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          or-entry (second augmented)
          [a b] (:children or-entry)]
      (is (= (:bindings a) (:bindings b)))
      (is (= (:bindings a) (:bindings or-entry))))))

(deftest test-augment-lhs--or-asymmetric-branches
  (testing ":or with asymmetric branches: one-branch binding still appears on the group"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id) (= ?k kind)]}
                {:type Application
                 :constraints '[(= ?app-id app-id)]}]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          or-entry (second augmented)]
      (is (= [:?k] (get-in or-entry [:bindings :new-bindings]))))))

(deftest test-augment-lhs--two-or-groups-no-crosstalk
  (testing "two :or groups in one LHS: no cross-talk between sub-scopes"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id) (= ?k kind)]}
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id) (= ?m meta)]}
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          [acct or-a or-b] augmented]
      (is (= {:binding-keys [] :new-bindings [:?app-id]} (:bindings acct)))
      (is (= {:binding-keys [:?app-id] :new-bindings [:?k]} (:bindings or-a)))
      (is (= {:binding-keys [:?app-id] :new-bindings [:?m]} (:bindings or-b)))
      (is (not (contains? (set (get-in or-a [:bindings :new-bindings])) :?m)))
      (is (not (contains? (set (get-in or-b [:bindings :new-bindings])) :?k))))))

(deftest test-augment-lhs--compound-negation-subscope
  (testing "compound negation: group binding-keys equal vars ∩ ancestor"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:not [:and {:type GivenDocument
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          not-entry (second augmented)
          negation-expr (second (second (conditions/get-raw-lhs (conditions/normalize-lhs lhs))))
          expected-keys (set/intersection (com/variables-as-keywords negation-expr)
                                          #{:?app-id})]
      (is (= expected-keys (set (get-in not-entry [:bindings :binding-keys]))))
      (is (= [] (get-in not-entry [:bindings :new-bindings]))))))

(deftest test-augment-lhs--negation-exists-no-leak
  (testing "negation-internal and :exists-internal bindings do not leak outward"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:not [:and {:type GivenDocument
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]
               {:type GivenDocument
                :constraints '[(= ?app-id app-id) (= ?after kind)]}]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          downstream (nth augmented 2)]
      (is (= {:binding-keys [:?app-id]
              :new-bindings [:?after]}
             (:bindings downstream))
          "negation contributes nothing to the outer ancestor set"))
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:exists {:type GivenDocument
                         :constraints '[(= ?app-id app-id) (= ?inner kind)]}]
               {:type GivenDocument
                :constraints '[(= ?app-id app-id) (= ?after kind)]}]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          downstream (nth augmented 2)]
      (is (= {:binding-keys [:?app-id]
              :new-bindings [:?after]}
             (:bindings downstream))
          "exists-internal ?inner must not leak into the outer ancestor set"))))

(deftest test-augment-lhs--group-union-structural
  (testing "every group's :bindings equals the union of its children's"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               [:or [:and {:type GivenDocument
                           :constraints '[(= ?app-id app-id)]}
                     {:type GivenDocument
                      :constraints '[(= ?app-id app-id) (= ?k kind)]}]
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]
               [:exists {:type GivenDocument
                         :constraints '[(= ?app-id app-id)]}]
               [:not [:and {:type GivenDocument
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})]
      (is (seq (every-group augmented)))
      (doseq [group (every-group augmented)]
        (is (= (union-of (:children group)) (:bindings group))
            (str "group mismatch at " (:condition-type group)))))))

(deftest test-augment-lhs--no-node-without-bindings
  (testing "no node in an augmented LHS lacks :bindings"
    (let [lhs [{:type Application
                :constraints '[(= ?app-id app-id)]}
               {:type GivenDocument
                :constraints '[(= ?app-id app-id)]
                :fact-binding :?doc}
               [:or {:type GivenDocument
                     :constraints '[(= ?app-id app-id) (= ?k kind)]}
                {:type GivenDocument
                 :constraints '[(= ?app-id app-id)]}]
               [:exists {:type GivenDocument
                         :constraints '[(= ?app-id app-id)]}]
               [:not {:type GivenDocument
                      :constraints '[(= ?app-id app-id)]}]
               [:not [:and {:type GivenDocument
                            :constraints '[(= ?app-id app-id)]}
                      {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}]]]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})]
      (doseq [node (every-node augmented)]
        (is (contains? node :bindings) (str "node without :bindings: " (pr-str node)))))))

(deftest test-augment-lhs--retains-internal-keys
  (testing "augment-lhs keeps :raw-condition and :clara.explorer.internal/normalized for in-memory consumers"
    (let [lhs [{:accumulator '(clara.rules.accumulators/all)
                :from {:type GivenDocument
                       :constraints '[(= ?app-id app-id)]}
                :result-binding :?docs}]
          augmented (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                            {:prod-ns prod-ns :env nil})
          acc-entry (first augmented)]
      (is (contains? acc-entry :raw-condition))
      (is (true? (:clara.explorer.internal/normalized acc-entry)))
      (is (= '(clara.rules.accumulators/all)
             (get-in acc-entry [:raw-condition :accumulator]))))))

(deftest test-augment-lhs--accumulator-evaluated-once
  (testing "each accumulator is evaluated once, not once per retained raw copy"
    (let [calls (atom 0)
          original conditions/accumulator-info
          counting (fn [form pns] (swap! calls inc) (original form pns))
          lhs [[:or
                {:accumulator '(clara.rules.accumulators/all)
                 :from {:type GivenDocument
                        :constraints '[(= ?app-id app-id)]}
                 :result-binding :?docs}
                {:accumulator '(clara.rules.accumulators/all)
                 :from {:type GivenDocument
                        :constraints '[(= ?app-id app-id)]}
                 :result-binding :?docs}]]]
      (with-redefs [conditions/accumulator-info counting]
        (conditions/augment-lhs (conditions/normalize-lhs lhs)
                                {:prod-ns prod-ns :env nil}))
      (is (= 2 @calls)))))
