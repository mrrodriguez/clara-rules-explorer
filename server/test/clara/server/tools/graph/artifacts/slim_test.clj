(ns clara.server.tools.graph.artifacts.slim-test
  "Pins the property that keeps this repo out of clara-rules-explorer's business:
  `slim-analysis` **only removes information**. It drops keys and collapses a
  cross-reference to the name it points at; it never derives or re-indexes.

  There is nothing to round-trip here and that is deliberate. An earlier version
  stripped `:lhs-types` / `:fact-types` and rebuilt them offline, which cannot be
  done correctly — `serialize-fact-type` is closed over the production's
  namespace and `resolve-type` calls `ns-resolve` for a symbol-typed fact type,
  so reproducing it needs the ruleset loaded in a JVM. These tests exist mostly
  to catch someone re-adding that: they assert the drop set is exactly what it
  claims, and that every value carrying fact-type or condition semantics comes
  through untouched.

  The fixture carries the **post-hierarchy-API** reference shapes — a pointer to
  a production is `{:name :id :ns :type}` and to a fact type `{:name :id
  :known}` — because those records are the bulk of a real artifact and the
  collapse is the thing most likely to break under an upstream shape change.

  Its `:lhs` is likewise the **post-normalization** shape clara-rules-explorer
  emits now: every node carries `::conditions/normalized`, a boolean group is
  `{:condition-type :not :children […]}` rather than a raw `[:not …]` vector, an
  accumulator's `:accumulator` is `{:form … :some-initial-value? …}` rather than
  a raw form, group and accumulator nodes retain a `:raw-condition` duplicate,
  and **every node carries `:bindings`** — leaves, test conditions, and `:and` /
  `:or` / `:not` / `:exists` groups alike, a group's being the componentwise
  union of its children's. The one exception is an accumulator's `:from`
  subtree, which is the accumulator condition's source pattern rather than a
  condition of its own; the accumulator node above it carries the bindings.

  This is the only place in the repo that pins that shape, and
  `dropped-condition-keys` is only meaningful against a fixture that actually
  has the keys."
  (:require
   [clara.server.tools.graph.conditions :as conditions]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.walk :as walk]
   [clara.server.tools.graph.artifacts.digest :as digest]
   [clara.server.tools.graph.artifacts.slim :as slim]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

;; The two cross-reference shapes clara-rules-explorer emits since the fact-type
;; hierarchy API. Everything but :name is either a restatement of the name or of
;; the record the analysis already stores under it.
(defn- rule-ref [n] {:name n :id (str n "-abc12345") :ns (first (.split ^String n "/")) :type "rule"})
(defn- query-ref [n] (assoc (rule-ref n) :type "query"))
(defn- type-ref [n] {:name n :id (str n "-def67890") :known true})

;; A boolean group, an accumulator, a vector fact type, a symbol fact type, and a
;; query — the shapes an offline reconstruction would have had to interpret.
(def ^:private analysis
  {:rules
   {"a.ns/producer"
    {:name "a.ns/producer" :id "a-ns-producer-aaaa1111"
     :ns "a.ns" :doc "produces two" :props {} :notes nil
     :lhs [{:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]" :args "[\n{:keys [x]}\n]"
            :bindings {:binding-keys [] :new-bindings [:?x]}
            ::conditions/normalized true}]
     :lhs-types [(type-ref "a/one")]
     :lhs-form "[:a/one\n [{:keys [x]}]\n (= x ?x)]\n"
     :rhs-form "(insert! (->fact :b/two {:x ?x}))\n"
     :insert-types [(type-ref "b/two")] :retract-types []
     :source-rule true :sink-rule false :unlinked-rule false
     :downstream [(rule-ref "b.ns/consumer")]
     :dynamic-insert-types-detected {:resolution :full :callsites [{:status :full}]}}

    "b.ns/consumer"
    {:name "b.ns/consumer" :id "b-ns-consumer-bbbb2222"
     :ns "b.ns" :doc nil :props {} :notes nil
     :lhs [{:type (type-ref "b/two") :constraints "[\n]" :args "[\n{:keys [x]}\n]"
            :bindings {:binding-keys [] :new-bindings [:?x]}
            ::conditions/normalized true}
           ;; A simple negation. The group's :bindings is the union over its one
           ;; child, so here it is the child's.
           {:condition-type :not
            :children [{:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"
                        :bindings {:binding-keys [:?x] :new-bindings []}
                        ::conditions/normalized true}]
            :bindings {:binding-keys [:?x] :new-bindings []}
            :raw-condition [:not {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"}]
            ::conditions/normalized true}
           {:accumulator {:form "(clara.rules.accumulators/all)" :some-initial-value? true}
            :from {:type (type-ref "a/one") :constraints "[\n]"
                   ::conditions/normalized true}
            :result-binding :?all
            :bindings {:binding-keys [] :new-bindings []}
            :raw-condition {:accumulator "(clara.rules.accumulators/all)"
                            :from {:type (type-ref "a/one") :constraints "[\n]"}
                            :result-binding :?all}
            ::conditions/normalized true}
           ;; A non-equality unification. `(= ?x x)` is an equality join, so ?x
           ;; is a :binding-key; `(< ?n n)` is not, so the compiler moves it to
           ;; a join filter and ?n lands in the third, optional binding group.
           ;; It is emitted only when non-empty, so a fixture without one would
           ;; not exercise it at all.
           {:type (type-ref "b/two") :constraints "[\n(= ?x x)\n(< ?n n)\n]"
            :bindings {:binding-keys [:?x] :new-bindings []
                       :join-filter-join-bindings [:?n]}
            ::conditions/normalized true}
           ;; An `:or` with asymmetric branches — one binds :?k, the other does
           ;; not. The group unions them, so a binding present on one branch
           ;; only still shows on the group. This is the case that makes a
           ;; group's :bindings *derived*: aggregate over leaves or read group
           ;; summaries, never both.
           {:condition-type :or
            :children [{:type (type-ref "c/three") :constraints "[\n(= x ?x)\n(= k ?k)\n]"
                        :bindings {:binding-keys [:?x] :new-bindings [:?k]}
                        ::conditions/normalized true}
                       {:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]"
                        :bindings {:binding-keys [:?x] :new-bindings []}
                        ::conditions/normalized true}]
            :bindings {:binding-keys [:?x] :new-bindings [:?k]}
            :raw-condition [:or
                            {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n(= k ?k)\n]"}
                            {:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]"}]
            ::conditions/normalized true}
           ;; An `:exists`. One child, so the group's bindings *are* the child's.
           ;; The synthetic `:?__exists__…` result-binding the analysis uses to
           ;; expand this into an accumulator is never surfaced.
           {:condition-type :exists
            :children [{:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"
                        :bindings {:binding-keys [:?x] :new-bindings []}
                        ::conditions/normalized true}]
            :bindings {:binding-keys [:?x] :new-bindings []}
            :raw-condition [:exists {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"}]
            ::conditions/normalized true}
           ;; A compound negation — a `:not` over an `:and`, so groups nest.
           ;; Nothing bound inside a negation escapes it, which is why every
           ;; :new-bindings in here is empty.
           {:condition-type :not
            :children [{:condition-type :and
                        :children [{:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]"
                                    :bindings {:binding-keys [:?x] :new-bindings []}
                                    ::conditions/normalized true}
                                   {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"
                                    :bindings {:binding-keys [:?x] :new-bindings []}
                                    ::conditions/normalized true}]
                        :bindings {:binding-keys [:?x] :new-bindings []}
                        :raw-condition [:and
                                        {:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]"}
                                        {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"}]
                        ::conditions/normalized true}]
            :bindings {:binding-keys [:?x] :new-bindings []}
            :raw-condition [:not [:and
                                  {:type (type-ref "a/one") :constraints "[\n(= x ?x)\n]"}
                                  {:type (type-ref "c/three") :constraints "[\n(= x ?x)\n]"}]]
            ::conditions/normalized true}]
     ;; a vector type and a resolved symbol type — exactly what a bb-side
     ;; `serialize-fact-type` got wrong. They arrive wrapped in reference maps
     ;; and must come back out as these exact strings.
     :lhs-types (mapv type-ref ["b/two" "c/three" "a/one"
                                "[:previous :domain/resource]"
                                "acme.facts/SomeRecord"])
     :lhs-form "[:b/two\n [{:keys [x]}]]\n[]\n"
     :rhs-form "(retract! ?f)\n"
     :insert-types [] :retract-types [(type-ref "a/one")]
     :source-rule false :sink-rule true :unlinked-rule false
     :upstream [(rule-ref "a.ns/producer")]}

    "c.ns/unlinked"
    {:name "c.ns/unlinked" :id "c-ns-unlinked-cccc3333"
     :ns "c.ns" :doc nil :props {} :notes nil
     :lhs [{:type (type-ref "c/three") :constraints "[\n]"
            :bindings {:binding-keys [] :new-bindings []}
            ::conditions/normalized true}]
     :lhs-types [(type-ref "c/three")] :lhs-form "[:c/three]\n" :rhs-form "(println :x)\n"
     :insert-types [] :retract-types []
     :source-rule true :sink-rule false :unlinked-rule true :no-output-types true}}

   :queries
   {"q.ns/all-twos"
    {:name "q.ns/all-twos" :id "q-ns-all-twos-dddd4444"
     :ns "q.ns" :doc nil :props {} :notes nil :params #{}
     :lhs [{:type (type-ref "b/two") :constraints "[\n]" :fact-binding :?output
            :bindings {:binding-keys [] :new-bindings []}
            ::conditions/normalized true}]
     :lhs-types [(type-ref "b/two")] :lhs-form "[:?output <- :b/two]\n"}}

   ;; Both directions on every node, the way the explorer emits it: :downstream
   ;; is the transpose of :upstream across the whole map, which is why only
   ;; :upstream reaches disk. A fixture carrying one key per node would let the
   ;; drop pass without ever exercising the redundancy it exists to remove.
   :dep-graph
   {"a.ns/producer" {:upstream #{} :downstream #{"b.ns/consumer"}}
    "b.ns/consumer" {:upstream #{"a.ns/producer"} :downstream #{}}}

   ;; :descendants is the transpose of :ancestors over this same map — "b/two"
   ;; lists "a/one" as an ancestor, so "a/one" lists "b/two" back. It is dropped
   ;; for exactly that reason, and "y/ghost" is why the drop still has to be
   ;; swept: a hierarchy-only type carries :known false there just as an
   ;; unresolved ancestor does, and that bit lives nowhere else.
   :fact-types
   {"a/one" {:name "a/one" :id "a-one-11111111" :ns "a"
             :ancestors []
             :descendants [(type-ref "b/two") (assoc (type-ref "y/ghost") :known false)]
             :used-by-rules [(rule-ref "a.ns/producer") (rule-ref "b.ns/consumer")]
             ;; a.ns/producer inserts b/two, which derives from a/one — so the
             ;; fact it makes IS an a/one, and the ancestor closure credits
             ;; producer here too. Modelled on upstream's
             ;; `production-fact-type-updates`; an earlier hand-written version
             ;; of this fixture left it out and the inversion test passed anyway.
             :inserted-by-rules [(rule-ref "a.ns/producer")]
             :retracted-by-rules [(rule-ref "b.ns/consumer")]
             :used-by-queries []}
    "b/two" {:name "b/two" :id "b-two-22222222" :ns "b"
             ;; an ancestor the explorer could not resolve — the one bit of a
             ;; reference that is not recoverable from the name
             :ancestors [(type-ref "a/one") (assoc (type-ref "z/gone") :known false)]
             :descendants []
             ;; a.ns/producer matches :a/one, and b/two derives from it, so a
             ;; b/two fact satisfies that condition — the ancestor closure is
             ;; what puts producer here, and dropping :used-by-rules is only
             ;; sound because inverting :lhs-types over :ancestors puts it back
             :used-by-rules [(rule-ref "a.ns/producer") (rule-ref "b.ns/consumer")]
             :inserted-by-rules [(rule-ref "a.ns/producer")] :retracted-by-rules []
             :used-by-queries [(query-ref "q.ns/all-twos")]}
    "c/three" {:name "c/three" :id "c-three-33333333" :ns "c"
               :ancestors [] :descendants []
               :used-by-rules [(rule-ref "b.ns/consumer") (rule-ref "c.ns/unlinked")]
               :inserted-by-rules [] :retracted-by-rules [] :used-by-queries []}}

   :nodes {1 {:id 1 :kind :alpha :productions #{"a.ns/producer"}}}

   ;; added by clara-rules-explorer alongside the fact-type hierarchy API: a
   ;; flat {id -> name} index, exactly the :id/:name pair already on every
   ;; :rules/:queries/:fact-types entry
   :fact-type-id-index {"a-one-11111111" "a/one" "b-two-22222222" "b/two"
                        "c-three-33333333" "c/three"}
   :production-id-index {"a-ns-producer-aaaa1111" "a.ns/producer"
                         "b-ns-consumer-bbbb2222" "b.ns/consumer"
                         "c-ns-unlinked-cccc3333" "c.ns/unlinked"
                         "q-ns-all-twos-dddd4444" "q.ns/all-twos"}

   ;; the annotations the explorer computed this analysis over, stamped onto the
   ;; result so a caller can tell whether a cached analysis is still current. A
   ;; *sorted* map keyed by rule-name strings, as `merge-layers` builds it — the
   ;; shape a keyword lookup throws `ClassCastException` on.
   :merged-annotations (into (sorted-map)
                             {"a.ns/producer" #:clara-rules{:insert-types [:b/two]}
                              "b.ns/consumer" #:clara-rules{:retract-types [:a/one]}})

   :unresolved [{:rule "c.ns/unlinked" :reason :no-detections}]})

;; ---------------------------------------------------------------------------

(defn- names-only?
  "No expanded cross-reference survives anywhere in `x`."
  [x]
  (let [bad (volatile! [])]
    (walk/postwalk (fn [v]
                     (when (and (map? v) (:name v) (:id v)
                                (every? #{:name :id :ns :type :known} (keys v)))
                       (vswap! bad conj v))
                     v)
                   x)
    (empty? @bad)))

(deftest slim-only-removes-information-test
  (let [slim (slim/slim-rulebase-analysis analysis)]
    (testing "the drop set is exactly what :slim advertises"
      (is (= (into (sorted-set)
                   (concat slim/dropped-top-level-keys
                           slim/dropped-production-keys
                           slim/dropped-fact-type-keys
                           slim/dropped-dep-graph-keys
                           slim/dropped-condition-keys))
             (get-in slim [:slim :dropped])))
      (is (nil? (:nodes slim)))
      (is (nil? (:fact-type-id-index slim)))
      (is (nil? (:production-id-index slim)))
      (is (nil? (:merged-annotations slim)))
      (doseq [[k p] (merge (:rules slim) (:queries slim))]
        (is (empty? (select-keys p slim/dropped-production-keys))
            (str k " kept a dropped production key")))
      (doseq [[k t] (:fact-types slim)]
        (is (empty? (select-keys t slim/dropped-fact-type-keys))
            (str k " kept a dropped fact-type key")))
      (doseq [[k e] (:dep-graph slim)]
        (is (empty? (select-keys e slim/dropped-dep-graph-keys))
            (str k " kept a dropped dep-graph key"))))

    (testing "every key that survives holds its original value, with references
              collapsed to the name they point at — nothing else recomputed"
      (is (= {:name "a.ns/producer" :ns "a.ns" :doc "produces two" :props {} :notes nil
              :lhs [{:type "a/one" :constraints "[\n(= x ?x)\n]" :args "[\n{:keys [x]}\n]"
                     :bindings {:binding-keys [] :new-bindings [:?x]}}]
              :lhs-types ["a/one"]
              :rhs-form "(insert! (->fact :b/two {:x ?x}))\n"
              :insert-types ["b/two"] :retract-types []
              :source-rule true :sink-rule false :unlinked-rule false}
             (get-in slim [:rules "a.ns/producer"])))
      ;; Every production→type direction is dropped, so a fact type is reduced to
      ;; its identity plus the one direction of the hierarchy that is kept.
      (is (= {:name "a/one" :ns "a" :ancestors []}
             (get-in slim [:fact-types "a/one"]))))

    (testing "not one expanded reference is left, anywhere"
      (is (names-only? slim)))

    (testing ":known false is the one bit a name does not carry, so it is
              hoisted once instead of repeated per reference — including off
              :descendants, which the sweep crosses before the drop removes it"
      (is (= #{"y/ghost" "z/gone"} (set (get-in slim [:slim :unknown-fact-types]))))
      (is (= ["a/one" "z/gone"] (get-in slim [:fact-types "b/two" :ancestors]))))

    (testing "a pure name-keyed section passes through, minus its dropped keys"
      (is (= {"a.ns/producer" {:upstream #{}}
              "b.ns/consumer" {:upstream #{"a.ns/producer"}}}
             (:dep-graph slim)))
      (is (= (:unresolved analysis) (:unresolved slim))))

    (testing ":slim tells a cold reader where each dropped key is answered"
      (is (= (get-in slim [:slim :dropped])
             (set (keys (get-in slim [:slim :recover])))))
      (is (string? (get-in slim [:slim :references]))))

    (testing "idempotent — an absent key dissocs to nothing and a collapsed
              reference is a string, which nothing re-collapses"
      (is (= slim (slim/slim-rulebase-analysis slim))))))

(deftest condition-internal-keys-never-reach-disk-test
  (let [slim (slim/slim-rulebase-analysis analysis)
        lhs (get-in slim [:rules "b.ns/consumer" :lhs])]
    (testing "not one :raw-condition or ::normalized survives, at any depth, in
              any production — they are clara-rules-explorer's normalization
              bookkeeping and say nothing to a reader of this file"
      (let [found (volatile! [])]
        (walk/postwalk (fn [x]
                         (when (map? x)
                           (doseq [k slim/dropped-condition-keys]
                             (when (contains? x k) (vswap! found conj k))))
                         x)
                       (merge (:rules slim) (:queries slim)))
        (is (empty? @found))))

    (testing "everything the normalized shape says about a condition is kept:
              the group's kind, children and bindings, the accumulator's
              evaluated form and its :from, and each leaf's :bindings —
              including the optional third binding group"
      (is (= [{:type "b/two" :constraints "[\n]" :args "[\n{:keys [x]}\n]"
               :bindings {:binding-keys [] :new-bindings [:?x]}}
              {:condition-type :not
               :children [{:type "c/three" :constraints "[\n(= x ?x)\n]"
                           :bindings {:binding-keys [:?x] :new-bindings []}}]
               :bindings {:binding-keys [:?x] :new-bindings []}}
              {:accumulator {:form "(clara.rules.accumulators/all)" :some-initial-value? true}
               :from {:type "a/one" :constraints "[\n]"}
               :result-binding :?all
               :bindings {:binding-keys [] :new-bindings []}}
              {:type "b/two" :constraints "[\n(= ?x x)\n(< ?n n)\n]"
               :bindings {:binding-keys [:?x] :new-bindings []
                          :join-filter-join-bindings [:?n]}}
              {:condition-type :or
               :children [{:type "c/three" :constraints "[\n(= x ?x)\n(= k ?k)\n]"
                           :bindings {:binding-keys [:?x] :new-bindings [:?k]}}
                          {:type "a/one" :constraints "[\n(= x ?x)\n]"
                           :bindings {:binding-keys [:?x] :new-bindings []}}]
               :bindings {:binding-keys [:?x] :new-bindings [:?k]}}
              {:condition-type :exists
               :children [{:type "c/three" :constraints "[\n(= x ?x)\n]"
                           :bindings {:binding-keys [:?x] :new-bindings []}}]
               :bindings {:binding-keys [:?x] :new-bindings []}}
              {:condition-type :not
               :children [{:condition-type :and
                           :children [{:type "a/one" :constraints "[\n(= x ?x)\n]"
                                       :bindings {:binding-keys [:?x] :new-bindings []}}
                                      {:type "c/three" :constraints "[\n(= x ?x)\n]"
                                       :bindings {:binding-keys [:?x] :new-bindings []}}]
                           :bindings {:binding-keys [:?x] :new-bindings []}}]
               :bindings {:binding-keys [:?x] :new-bindings []}}]
             lhs)))

    (testing ":join-filter-join-bindings is a third, optional group inside
              :bindings, carrying the variables a non-equality unification joins
              on. It is not in any drop set and slim must not lose it — a
              condition coupled to a type through `(< ?n n)` is coupled just as
              really as one coupled through `(= ?n n)`"
      (is (= {:binding-keys [:?x] :new-bindings [] :join-filter-join-bindings [:?n]}
             (:bindings (nth lhs 3)))))

    (testing "dropping the duplicate subtree loses no fact type — every one in a
              :raw-condition is also in the node beside it, so :lhs-types and
              :unknown-fact-types are unchanged by the prune"
      (is (= ["b/two" "c/three" "a/one" "[:previous :domain/resource]"
              "acme.facts/SomeRecord"]
             (get-in slim [:rules "b.ns/consumer" :lhs-types])))
      (is (= #{"y/ghost" "z/gone"} (set (get-in slim [:slim :unknown-fact-types])))))))

(defn- condition-nodes
  "Every condition node in a `:lhs`, at any depth — groups, their nested
  children, leaves, and an accumulator's `:from` subtree."
  [lhs]
  (mapcat (fn walk [n]
            (when (map? n)
              (cons n (concat (mapcat walk (:children n)) (walk (:from n))))))
          lhs))

(defn- accumulator-from-nodes
  "The `:from` subtree of every accumulator in `lhs`, at any depth."
  [lhs]
  (into #{} (comp (filter :accumulator) (mapcat (comp condition-nodes vector :from)))
        (condition-nodes lhs)))

(deftest every-condition-node-carries-bindings-test
  (testing "clara-rules-explorer attaches binding info to EVERY condition node —
            leaves, test conditions, and :and / :or / :not / :exists groups
            alike, however deeply nested. A reader can therefore ask any node
            what it joins on, and `(get-in node [:bindings :binding-keys])`
            being empty genuinely means *joins on nothing* rather than *not
            analyzed*. Nothing in this repo needs a `contains?` guard, and
            nothing needs to report binding coverage the way it reports repo
            coverage.

            The one node without :bindings is an accumulator's :from subtree,
            and that is not an exception to the rule so much as a statement of
            what the unit is: an accumulator is one condition, :from is the
            source pattern inside it, and the accumulator node above carries the
            bindings for the pair. Upstream scopes the same invariant the same
            way.

            Pinned over the whole fixture rather than case by case, because the
            value of the invariant is that it holds everywhere — a caller that
            has to know which kinds are covered is back where the old deferral
            left it."
    (let [slim (slim/slim-rulebase-analysis analysis)]
      (doseq [[p-name p] (merge (:rules slim) (:queries slim))
              :let [froms (accumulator-from-nodes (:lhs p))]
              node (condition-nodes (:lhs p))
              :when (not (contains? froms node))]
        (is (contains? node :bindings)
            (str p-name " has a condition node without :bindings: " (pr-str node)))))))

(deftest group-bindings-are-the-union-of-their-children-test
  (testing "a group's :bindings is the componentwise union of its children's —
            one vocabulary for every node, no group-only keys, no second
            interpretation to pick between. It is therefore DERIVED, not
            additional: aggregate over leaves or read group summaries, never
            both, or every binding under a group is counted twice.

            Asserted structurally over every group in the fixture, since the
            union rule is the whole model rather than a property of any one
            group kind."
    (let [slim (slim/slim-rulebase-analysis analysis)
          binding-sets (fn [b] (update-vals (select-keys b [:binding-keys :new-bindings
                                                            :join-filter-join-bindings])
                                            set))
          union (fn [bs] (apply merge-with into
                                {:binding-keys #{} :new-bindings #{}}
                                (map binding-sets bs)))]
      (doseq [[p-name p] (merge (:rules slim) (:queries slim))
              group (filter :condition-type (condition-nodes (:lhs p)))]
        (is (= (union (map :bindings (:children group)))
               (merge {:binding-keys #{} :new-bindings #{}} (binding-sets (:bindings group))))
            (str p-name " " (:condition-type group)
                 " group's :bindings is not its children's union")))))

  (testing "the asymmetric :or is the case that proves it is a union rather than
            an intersection: :?k is bound on one branch only and still appears
            on the group"
    (let [or-group (-> (slim/slim-rulebase-analysis analysis)
                       (get-in [:rules "b.ns/consumer" :lhs])
                       (nth 4))]
      (is (= :or (:condition-type or-group)))
      (is (= [[:?k] []] (mapv #(get-in % [:bindings :new-bindings]) (:children or-group))))
      (is (= [:?k] (get-in or-group [:bindings :new-bindings])))))

  (testing "the fact types under a group are kept alongside its bindings, so
            :lhs-types and the condition kinds stay complete over the whole
            tree and consumer counts and polarity stay exact"
    (let [lhs (get-in (slim/slim-rulebase-analysis analysis) [:rules "b.ns/consumer" :lhs])]
      (is (= ["c/three" "a/one"] (mapv :type (:children (nth lhs 4)))))
      (is (= ["a/one" "c/three"]
             (mapv :type (get-in lhs [6 :children 0 :children]))))))

  (testing "no synthetic :?__exists__… binding is ever surfaced — it is an
            artifact of expanding an :exists into an accumulator, absent from
            the authored rule"
    (let [slim (slim/slim-rulebase-analysis analysis)]
      (doseq [[_ p] (merge (:rules slim) (:queries slim))
              node (condition-nodes (:lhs p))
              v (mapcat (:bindings node {}) [:binding-keys :new-bindings :join-filter-join-bindings])]
        (is (not (str/includes? (str v) "__exists__")))))))

(deftest slim-leaves-a-partial-analysis-alone-test
  (testing "a section that was not there is not conjured"
    (is (= #{:slim} (set (keys (slim/slim-rulebase-analysis {})))))
    (is (= #{:rules :slim} (set (keys (slim/slim-rulebase-analysis {:rules {}})))))))

(deftest fact-type-semantics-are-never-touched-test
  (testing "vector and symbol fact types survive verbatim, reference wrapper and
            all. Reproducing these offline needs ns-resolve against the
            production's namespace, so the only safe thing to do with them is
            carry the name through unread"
    (let [slim (slim/slim-rulebase-analysis analysis)]
      (is (= ["b/two" "c/three" "a/one" "[:previous :domain/resource]"
              "acme.facts/SomeRecord"]
             (get-in slim [:rules "b.ns/consumer" :lhs-types])))))

  (testing "the drop set names no key that would have to be re-serialized to get
            back — each of these is closed over the production's namespace, so
            dropping one is unrecoverable offline, whatever the file still holds"
    (is (empty? (filter #{:lhs-types :insert-types :retract-types :fact-types :ancestors}
                        (concat slim/dropped-production-keys
                                slim/dropped-fact-type-keys)))))

  (testing ":descendants is the one dropped key that names fact types at all, and
            it is droppable because recovering it interprets nothing: invert the
            :ancestors the file keeps. What inversion cannot reach is a ghost,
            which has no entry to be inverted out of — :unknown-fact-types is
            where that name survives"
    (let [slim (slim/slim-rulebase-analysis analysis)
          inverted (reduce-kv (fn [idx type-name {:keys [ancestors]}]
                                (reduce #(update %1 %2 (fnil conj #{}) type-name)
                                        idx
                                        ancestors))
                              {}
                              (:fact-types slim))]
      (is (= ["b/two" "y/ghost"]
             (mapv :name (get-in analysis [:fact-types "a/one" :descendants]))))
      (is (= #{"b/two"} (get inverted "a/one")))
      (is (contains? (set (get-in slim [:slim :unknown-fact-types])) "y/ghost")))))

(defn- ->descendant-index
  "`{type-name #{descendant-name}}` off the `:ancestors` the slim file keeps — a
  type is a descendant of every name in its own `:ancestors`."
  [fact-types]
  (reduce-kv (fn [idx type-name {:keys [ancestors]}]
               (reduce #(update %1 %2 (fnil conj #{}) type-name) idx ancestors))
             {}
             fact-types))

(defn- ->ancestor-index
  "`{type-name #{ancestor-name}}` straight off the `:ancestors` the slim file
  keeps."
  [fact-types]
  (into {} (map (fn [[type-name {:keys [ancestors]}]] [type-name (set ancestors)])) fact-types))

(defn- ->closure-inverse
  "The hierarchy-closure inverse of `type-key` over `productions`, where
  `closure` is `{type -> #{the other types this reaches}}`.

  Which index to pass is the whole subtlety, and it differs per key — see
  `slim`'s header comment, THE INVERSE PAIRS."
  [productions closure type-key]
  (reduce-kv (fn [idx p-name production]
               (reduce (fn [acc t]
                         (reduce #(update %1 %2 (fnil conj #{}) p-name)
                                 acc
                                 (cons t (get closure t))))
                       idx
                       (get production type-key)))
             {}
             productions))

(deftest dropped-directions-invert-back-test
  (testing "each dropped direction is the exact inverse of a kept one, so the
            file loses no edge by carrying only one side. This is the whole
            license for the drops — if an inversion here stops reproducing what
            the analysis stated, the key has to go back on disk."
    (let [slim (slim/slim-rulebase-analysis analysis)
          descendants (->descendant-index (:fact-types slim))
          ancestors (->ancestor-index (:fact-types slim))
          stated (fn [k] (into {} (for [[t r] (:fact-types analysis)
                                        :let [v (map :name (get r k))]
                                        :when (seq v)]
                                    [t (set v)])))
          ;; `:used-by-*` is a field ON a fact-type record, so it exists only
          ;; for types the analysis has an entry for. Inverting :lhs-types also
          ;; reaches types referenced without one — here the vector and symbol
          ;; types — so the reconstruction is scoped to :fact-types the way the
          ;; field it replaces is.
          on-known-types #(select-keys % (keys (:fact-types slim)))]

      (testing ":dep-graph :downstream is the transpose of the :upstream it keeps"
        (let [transposed (reduce-kv (fn [idx node {:keys [upstream]}]
                                      (reduce #(update %1 %2 (fnil conj #{}) node) idx upstream))
                                    {}
                                    (:dep-graph slim))]
          (is (= {"a.ns/producer" #{"b.ns/consumer"}} transposed))
          (doseq [[node {:keys [downstream]}] (:dep-graph analysis)]
            (is (= (set downstream) (get transposed node #{}))
                (str node " :downstream is not the transpose of :upstream")))))

      (testing ":used-by-rules is the ancestor-closure inverse of :lhs-types,
                and the closure is load-bearing — b/two is reached only through
                a.ns/producer matching its ancestor a/one"
        (is (= (stated :used-by-rules)
               (on-known-types (->closure-inverse (:rules slim) descendants :lhs-types)))))

      (testing ":used-by-queries is the same inverse over :queries"
        (is (= (stated :used-by-queries)
               (on-known-types (->closure-inverse (:queries slim) descendants :lhs-types)))))

      (testing "inverting :lhs-types WITHOUT the closure is not enough — the
                naive inverse misses every ancestor-matched rule, which is why
                `recovery` names :ancestors as part of the reconstruction"
        (is (not= (stated :used-by-rules)
                  (on-known-types (->closure-inverse (:rules slim) {} :lhs-types)))))

      (testing ":inserted-by-rules and :retracted-by-rules are one pair, inverted
                over ANCESTORS where the :used-by-* pair inverts over descendants.
                Both directions are pinned because getting one backwards is the
                only way to misread the header comment and still see green"
        (is (= (stated :inserted-by-rules)
               (on-known-types (->closure-inverse (:rules slim) ancestors :insert-types))))
        (is (= (stated :retracted-by-rules)
               (on-known-types (->closure-inverse (:rules slim) ancestors :retract-types)))))

      (testing "…and the two closures are genuinely different indexes here, so
                the assertions above are not passing by coincidence on a flat
                hierarchy"
        (is (not= ancestors descendants))
        (is (seq (stated :retracted-by-rules))
            "the fixture stopped exercising retraction — this test would pass vacuously")))))

(deftest cross-reference-detection-survives-a-sorted-map-test
  (testing "a map keyed by rule-name strings is not a cross-reference and must be
            recognized as one without being probed: `contains?` on a sorted map
            runs the comparator, and comparing a keyword to a string throws.
            The analysis carries such a map under :merged-annotations, and any
            kept key could grow one"
    (let [by-rule-name (into (sorted-map) {"a.ns/producer" {:some :annotation}})
          slim (slim/slim-rulebase-analysis (assoc-in analysis [:rules "a.ns/producer" :props]
                                                      by-rule-name))]
      (is (= by-rule-name (get-in slim [:rules "a.ns/producer" :props]))))))

(deftest unrecognized-reference-shapes-are-carried-whole-test
  (testing "if upstream adds a field to a reference it stops matching and comes
            through expanded. The file grows, which is the safe way to fail —
            silently dropping an unknown field would lose information"
    (let [odd {:name "a/one" :id "a-one-11111111" :known true :weight 3}
          slim (slim/slim-rulebase-analysis (assoc-in analysis [:rules "a.ns/producer" :lhs-types] [odd]))]
      (is (= [odd] (get-in slim [:rules "a.ns/producer" :lhs-types]))))))

;; ---------------------------------------------------------------------------

(deftest digest-counts-without-interpreting-test
  (let [d (digest/->rulebase-analysis-digest analysis)]
    (testing "the explorer's own summary, verbatim"
      (is (= {:rule-count 3 :query-count 1 :fact-type-count 3} (:summary d))))

    (testing "counts over what the explorer already computed"
      (is (= {:node-count 1 :dep-edge-count 1 :unresolved-count 1} (:counts d)))
      (is (= {:source-rule 2 :sink-rule 1 :unlinked-rule 1 :no-output-types 1}
             (:flag-counts d))))

    (testing "namespaces split rules from queries by which map they came from"
      (is (= {"a.ns" {:rules 1 :queries 0}
              "b.ns" {:rules 1 :queries 0}
              "c.ns" {:rules 1 :queries 0}
              "q.ns" {:rules 0 :queries 1}}
             (:namespaces d))))

    (testing "the two work lists carry names, everything else carries counts"
      (is (= #{"c.ns/unlinked"} (:unlinked-rules d)))
      (is (= #{"c.ns/unlinked"} (:no-output-rules d)))
      (is (= (:unresolved analysis) (:unresolved d))))))

(deftest digest-tolerates-a-partial-analysis-test
  (testing "every section of a RulebaseAnalysis is optional and `slim` is
            required to leave a partial one partial, so the digest of one
            answers with zeros. An absent :queries otherwise reaches
            `(comp nil :name)` and throws an NPE naming neither key nor caller"
    (is (= {:rule-count 0 :query-count 0 :fact-type-count 0}
           (:summary (digest/->rulebase-analysis-digest {}))))
    (is (= {:node-count 0 :dep-edge-count 0 :unresolved-count 0}
           (:counts (digest/->rulebase-analysis-digest {}))))
    (is (= {} (:namespaces (digest/->rulebase-analysis-digest {}))))
    (testing "and rules without queries is the shape that actually occurs"
      (let [d (digest/->rulebase-analysis-digest (select-keys analysis [:rules]))]
        (is (= 3 (get-in d [:summary :rule-count])))
        (is (= 0 (get-in d [:summary :query-count])))
        (is (= {"a.ns" {:rules 1 :queries 0}
                "b.ns" {:rules 1 :queries 0}
                "c.ns" {:rules 1 :queries 0}}
               (:namespaces d)))))))

(deftest digest-reads-the-pre-slim-analysis-test
  (testing "it must be built before slimming — :nodes is one of the counts, and a
            digest that depended on what slim left behind would be the coupling
            both namespaces exist to avoid"
    (is (= 1 (get-in (digest/->rulebase-analysis-digest analysis) [:counts :node-count])))
    (is (zero? (get-in (digest/->rulebase-analysis-digest (slim/slim-rulebase-analysis analysis))
                       [:counts :node-count])))))
