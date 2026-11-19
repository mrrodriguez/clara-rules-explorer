(ns clara.explorer.server.navigate-test
  "Pure tests for `clara.explorer.server.navigate/fact-type-provenance` (the N matrix).

  `navigate` has no live-session dependency: the analyses here are tiny slim maps pushed through
  `clara.explorer.artifacts.shared.rehydrate/rehydrate-analysis`, which is the real closure code
  (usage inverses, `:descendants`, dep-graph inverses), so the cases that the fixture rulebase
  cannot express — diamonds, cross-type cycles, insert-and-retract of one type, unlinked outputs —
  are cheap, deterministic, and schema-checked."
  (:require
   [clara.explorer.artifacts.shared.rehydrate :as shared-rehydrate]
   [clara.explorer.server.navigate :as navigate]
   [clara.explorer.server.schema :as schema]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.core :as s]
   [schema.test :as st]))

(use-fixtures :once st/validate-schemas)

(def ^:private runtime
  "A stub `schema/NavigateRuntime`: the token is already the fact-type name, there is no callsite
  linkage to resolve, and every source is absent (the bb posture)."
  {:resolve-token     (fn [_caller-ns token] token)
   :token->fq-sym     (fn [_caller-ns _token] nil)
   :production-source (fn [_p-name] {:var? false})})

(defn- ->analysis
  "A hand-built slim analysis rehydrated through the real closure code.

  `:fact-types` is `{name [ancestor-names]}`, `:rules`/`:queries` are
  `{name {:lhs [names] :insert [names] :retract [names] ...}}` with the extra production keys
  (`:no-output-types`, `:unlinked-rule`, `:unit`) passed through."
  [{:keys [fact-types rules queries]}]
  (let [->entry (fn [type-name ancestors]
                  {:name type-name :ns nil :ancestors (vec ancestors)})
        ->production (fn [p-name {:keys [lhs insert retract] :as production}]
                       (cond-> {:name p-name
                                :ns (namespace (symbol p-name))
                                :lhs-types (vec lhs)}
                         (contains? production :insert)
                         (assoc :insert-types (vec insert))

                         (contains? production :retract)
                         (assoc :retract-types (vec retract))

                         (:no-output-types production)
                         (assoc :no-output-types true)

                         (:unlinked-rule production)
                         (assoc :unlinked-rule {:downstream :unknown})

                         (:unit production)
                         (assoc :unit (:unit production))))]
    (shared-rehydrate/rehydrate-analysis
     {:rules (into (sorted-map) (map (fn [[p-name p]] [p-name (->production p-name p)])) rules)
      :queries (into (sorted-map) (map (fn [[p-name p]] [p-name (->production p-name p)])) queries)
      :fact-types (into (sorted-map) (map (fn [[t ancestors]] [t (->entry t ancestors)])) fact-types)
      :dep-graph {}
      :slim {:dropped #{} :unknown-fact-types #{}}})))

(defn- provenance
  [analysis input]
  (navigate/fact-type-provenance analysis runtime input))

(defn- cursor-input
  [token direction depth & [input]]
  {:node {:kind :cursor :input (merge {:token token} input)}
   :direction direction
   :depth depth
   :ancestors []})

(defn- child-names
  [node]
  (mapv :name (:children node)))

(defn- production-nodes
  "Every production node in the tree, depth-first."
  [node]
  (concat (when (= :production (:kind node)) [node])
          (mapcat production-nodes (:children node))))

;; ---------------------------------------------------------------------------
;; N1–N3: direct edges and hierarchy bridges
;; ---------------------------------------------------------------------------

(deftest n1-direct-producer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" #{}}
                              :rules {"t/p" {:lhs ["S"] :insert ["T"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer 0)))]
    (is (= :fact-type (:kind root)))
    (is (= "T" (:type root)))
    (is (= "T" (:type-short root)))
    (is (false? (:external? root)))
    (is (not (contains? root :vias)))
    (is (= ["t/p"] (child-names root)))
    (let [p (first (:children root))]
      (is (= :production (:kind p)))
      (is (= :insert (:via p)))
      (is (not (contains? p :vias)))
      (is (= [{:name "T" :short "T"}] (:matches p)))
      (is (false? (:bridge? p)))
      (is (false? (:cycle? p)))
      (is (false? (:seen? p)))
      (is (not (contains? p :children)) "depth 0 leaves it expandable"))))

(deftest n2-subtype-bridge-producer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "V" #{}}
                              :rules {"t/p" {:lhs ["V"] :insert ["S"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer 0)))
        p (first (:children root))]
    (is (= ["t/p"] (child-names root)))
    (is (= [{:name "S" :short "S"}] (:matches p)))
    (is (true? (:bridge? p)))))

(deftest n3-supertype-bridge-consumer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "V" #{}}
                              :rules {"t/c" {:lhs ["T"] :insert ["V"]}}})
        root (:node (provenance analysis (cursor-input "S" :consumer 0)))
        c (first (:children root))]
    (is (= ["t/c"] (child-names root)))
    (is (= :consume (:via c)))
    (is (= [{:name "T" :short "T"}] (:matches c)))
    (is (true? (:bridge? c)))))

;; ---------------------------------------------------------------------------
;; N4–N7: dedupe, cycles, and bounded `:full`
;; ---------------------------------------------------------------------------

(deftest n4-insert-and-retract-one-type-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "S2" ["T"]}
                              :rules {"t/both" {:lhs ["T"] :insert ["S"] :retract ["S2"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer 0)))]
    (is (= ["t/both"] (child-names root)) "one node, not one per via")
    (let [p (first (:children root))]
      (is (= :retract (:via p)))
      (is (= [:insert :retract] (:vias p)))
      (is (= ["S" "S2"] (mapv :name (:matches p)))
          "matches unions the insert and retract sides"))))

(deftest n5-self-loop-cycle-test
  (let [analysis (->analysis {:fact-types {"T" #{}}
                              :rules {"t/p" {:lhs ["T"] :insert ["T"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer 1)))
        p (first (:children root))
        back-type (first (:children p))
        back (:children back-type)]
    (is (false? (:cycle? p)) "not a cycle at level 1: ancestors is empty at a cursor root")
    (is (contains? p :children))
    (is (= "T" (:type back-type)))
    (is (true? (:cycle? (first back))))
    (is (not (contains? (first back) :children)))))

(deftest n6-cross-type-cycle-terminates-test
  (let [analysis (->analysis {:fact-types {"T" #{} "U" #{}}
                              :rules {"t/a" {:lhs ["T"] :insert ["U"]}
                                      "t/b" {:lhs ["U"] :insert ["T"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer :full)))
        b-nodes (filter #(= "t/b" (:name %)) (production-nodes root))]
    (is (= 2 (count b-nodes)) "the cycle closes on the second t/b")
    (is (false? (:cycle? (first b-nodes))))
    (is (true? (:cycle? (second b-nodes))))))

(deftest n7-diamond-stays-linear-test
  (let [analysis (->analysis {:fact-types {"R" #{} "T" #{} "S" #{}}
                              :rules {"t/a" {:lhs ["S"] :insert ["T"]}
                                      "t/b" {:lhs ["T"] :insert ["R"]}
                                      "t/c" {:lhs ["T"] :insert ["R"]}}})
        root (:node (provenance analysis (cursor-input "R" :producer :full)))
        a-nodes (filter #(= "t/a" (:name %)) (production-nodes root))]
    (is (= 2 (count a-nodes)) "t/a is reached through both parents but expanded once")
    (is (false? (:seen? (first a-nodes))))
    (is (true? (:seen? (second a-nodes))))
    (is (not (contains? (second a-nodes) :children)))
    (is (not-any? :cycle? (production-nodes root)))))

;; ---------------------------------------------------------------------------
;; N8–N10: depth, leaf kinds, external
;; ---------------------------------------------------------------------------

(def ^:private chain-analysis
  (->analysis {:fact-types {"A" #{} "B" #{} "C" #{} "D" #{}}
               :rules {"t/p1" {:lhs ["A"] :insert ["B"]}
                       "t/p2" {:lhs ["B"] :insert ["C"]}
                       "t/p3" {:lhs ["C"] :insert ["D"]}}}))

(deftest n8-depth-semantics-test
  (testing "a :cursor root: n + 1 production levels below the fact type"
    (is (= ["t/p3"]
           (child-names (:node (provenance chain-analysis (cursor-input "D" :producer 0))))))
    (is (= ["t/p3" "t/p2"]
           (mapv :name (production-nodes (:node (provenance chain-analysis
                                                            (cursor-input "D" :producer 1)))))))
    (is (= ["t/p3" "t/p2" "t/p1"]
           (mapv :name (production-nodes (:node (provenance chain-analysis
                                                            (cursor-input "D" :producer :full))))))))
  (testing "a :production root: depth 0 is the root plus one level below it"
    (let [root (fn [depth]
                 (:node (provenance chain-analysis {:node {:kind :production :name "t/p3"}
                                                    :direction :producer
                                                    :depth depth
                                                    :ancestors []})))]
      (is (= ["t/p3" "t/p2"] (mapv :name (production-nodes (root 0)))))
      (is (= ["t/p3" "t/p2" "t/p1"] (mapv :name (production-nodes (root 1)))))
      (is (= ["t/p3" "t/p2" "t/p1"] (mapv :name (production-nodes (root :full))))
          "the chain ends at an external leaf"))))

(deftest n9-leaf-kinds-test
  (let [analysis (->analysis {:fact-types {"T" #{} "X" #{}}
                              :queries {"t/q" {:lhs ["T"]}}
                              :rules {"t/noout" {:lhs ["T"] :no-output-types true}
                                      "t/unlinked" {:lhs ["T"] :unlinked-rule true}
                                      "t/z" {:lhs [] :insert ["X"]}}})
        consumer-root (:node (provenance analysis (cursor-input "T" :consumer 0)))
        by-name (into {} (map (juxt :name identity)) (:children consumer-root))]
    (is (= [] (:children (by-name "t/q"))))
    (is (= "query" (:type (by-name "t/q"))))
    (is (= [] (:children (by-name "t/noout"))))
    (is (not (contains? (by-name "t/noout") :outputs)))
    (is (= [] (:children (by-name "t/unlinked"))))
    (is (= :unknown (:outputs (by-name "t/unlinked"))))
    (let [producer-root (:node (provenance analysis (cursor-input "X" :producer 0)))
          z (first (filter #(= "t/z" (:name %)) (:children producer-root)))]
      (is (= [] (:children z)) "a zero-LHS rule is a producer leaf"))))

(deftest n10-external-retract-only-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" #{}}
                              :rules {"t/ret" {:lhs ["S"] :retract ["T"]}}})
        root (:node (provenance analysis (cursor-input "T" :producer 0)))
        p (first (:children root))]
    (is (true? (:external? root)) "nothing inserts T, even though a rule retracts it")
    (is (= ["t/ret"] (child-names root)))
    (is (= :retract (:via p)))))

;; ---------------------------------------------------------------------------
;; N11–N12: ordering and the error contract
;; ---------------------------------------------------------------------------

(deftest n11-ordering-test
  (testing "production children are name-sorted; consumer types are declaration-ordered"
    (let [analysis (->analysis {:fact-types {"T" #{} "V" #{} "Z" #{} "A" #{} "M" #{}}
                                :rules {"t/p" {:lhs ["T"] :insert ["Z" "A"] :retract ["M"]}
                                        "t/zz" {:lhs ["V"] :insert ["T"]}
                                        "t/aa" {:lhs ["V"] :insert ["T"]}}})
          producer-root (:node (provenance analysis (cursor-input "T" :producer 0)))
          consumer-root (:node (provenance analysis (cursor-input "T" :consumer :full)))
          p (first (:children consumer-root))]
      (is (= ["t/aa" "t/zz"] (child-names producer-root)))
      (is (= ["Z" "A" "M"] (mapv :type (:children p)))
          "insert-types in declaration order, then the remaining retract-types")
      (is (= [[:insert] [:insert] [:retract]] (mapv :vias (:children p))))))
  (testing ":matches is name-sorted"
    (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "U" #{}}
                                :rules {"t/c" {:lhs ["T" "S"] :insert ["U"]}}})
          root (:node (provenance analysis (cursor-input "S" :consumer 0)))
          c (first (:children root))]
      (is (= ["S" "T"] (mapv :name (:matches c)))))))

(deftest n12-error-contract-test
  (let [analysis (->analysis {:fact-types {"T" #{}}
                              :rules {"t/p" {:lhs ["T"] :insert ["T"]}}})]
    (testing "an unknown production root"
      (is (= {:error "no production named t/nope"}
             (provenance analysis {:node {:kind :production :name "t/nope"}
                                   :direction :producer :depth 0 :ancestors []}))))
    (testing "an ancestor naming no production"
      (is (= {:error "no production named t/nope"}
             (provenance analysis (assoc-in (cursor-input "T" :producer 0)
                                            [:ancestors] ["t/nope"])))))
    (testing "a token that resolves to nothing"
      (is (string? (:error (provenance analysis (cursor-input "" :producer 0))))))
    (testing "a negative depth"
      (is (string? (:error (provenance analysis (cursor-input "T" :producer -1))))))
    (testing "shape violations are the schema's job, not the operation's"
      (is (some? (s/check schema/FactTypeProvenanceInput
                          {:node {:kind :fact-type}
                           :direction :sideways
                           :depth 0
                           :ancestors []})))
      (is (some? (s/check schema/FactTypeProvenanceInput
                          {:node {:kind :cursor :input {:token "T"}}
                           :direction :producer
                           :depth 0
                           :ancestors "not-a-vector"}))))))
