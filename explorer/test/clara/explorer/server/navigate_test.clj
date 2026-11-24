(ns clara.explorer.server.navigate-test
  "Pure tests for `navigate/fact-type-provenance`.

  `navigate` has no live-session dependency: the analyses here are tiny slim maps pushed through
  `shared-rehydrate/rehydrate-analysis`, which is the real closure code
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

(def ^:private callsite-runtime
  "A runtime whose token resolves to `caller-ns/token`, so a shared constructor reaches the
  serialized callsite linkage."
  {:resolve-token     (fn [_caller-ns token] token)
   :token->fq-sym     (fn [caller-ns token] (symbol (str caller-ns) token))
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
                         (assoc :unit (:unit production))

                         (:dynamic-insert-types-detected production)
                         (assoc :dynamic-insert-types-detected
                                (:dynamic-insert-types-detected production))

                         (:dynamic-retract-types-detected production)
                         (assoc :dynamic-retract-types-detected
                                (:dynamic-retract-types-detected production))))]
    (shared-rehydrate/rehydrate-analysis
     {:rules (into (sorted-map) (map (fn [[p-name p]] [p-name (->production p-name p)])) rules)
      :queries (into (sorted-map) (map (fn [[p-name p]] [p-name (->production p-name p)])) queries)
      :fact-types (into (sorted-map) (map (fn [[t ancestors]] [t (->entry t ancestors)])) fact-types)
      :dep-graph {}
      :slim {:dropped #{} :unknown-fact-types #{}}})))

(defn- answer-provenance
  [analysis input]
  (navigate/fact-type-provenance analysis runtime input))

(defn- ->cursor-input
  [token direction depth & [input]]
  {:node {:kind :cursor :input (merge {:token token} input)}
   :direction direction
   :depth depth
   :ancestors []})

(defn- collect-child-names
  [node]
  (mapv :name (:children node)))

(defn- collect-production-nodes
  "Every production node in the tree, depth-first, as a concrete vector."
  [node]
  (cond-> (if (= :production (:kind node)) [node] [])
    (seq (:children node)) (into (mapcat collect-production-nodes) (:children node))))

;; ---------------------------------------------------------------------------
;; Direct edges and hierarchy bridges
;; ---------------------------------------------------------------------------

(deftest provenance-direct-producer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" #{}}
                              :rules {"t/p" {:lhs ["S"] :insert ["T"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer 0)))]
    (is (= :fact-type (:kind root)))
    (is (= "T" (:type root)))
    (is (= "T" (:type-short root)))
    (is (false? (:external? root)))
    (is (not (contains? root :vias)))
    (is (= ["t/p"] (collect-child-names root)))
    (let [p (first (:children root))]
      (is (= :production (:kind p)))
      (is (= :insert (:via p)))
      (is (not (contains? p :vias)))
      (is (= [{:name "T" :short "T"}] (:matches p)))
      (is (false? (:bridge? p)))
      (is (false? (:cycle? p)))
      (is (false? (:seen? p)))
      (is (not (contains? p :children)) "depth 0 leaves it expandable"))))

(deftest provenance-subtype-bridge-producer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "V" #{}}
                              :rules {"t/p" {:lhs ["V"] :insert ["S"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer 0)))
        p (first (:children root))]
    (is (= ["t/p"] (collect-child-names root)))
    (is (= [{:name "S" :short "S"}] (:matches p)))
    (is (true? (:bridge? p)))))

(deftest provenance-supertype-bridge-consumer-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "V" #{}}
                              :rules {"t/c" {:lhs ["T"] :insert ["V"]}}})
        root (:node (answer-provenance analysis (->cursor-input "S" :consumer 0)))
        c (first (:children root))]
    (is (= ["t/c"] (collect-child-names root)))
    (is (= :consume (:via c)))
    (is (= [{:name "T" :short "T"}] (:matches c)))
    (is (true? (:bridge? c)))))

(deftest provenance-display-short-names-test
  (testing "only class-like names shorten; keywords, strings, tuples, and maps stay whole"
    (let [analysis (->analysis {:fact-types {"com.example.DocumentCheck" #{}
                                             ":loan/status" #{}
                                             "\"a.b\"" #{}
                                             "[:loan/status \"a.b\"]" #{}
                                             "{:k :a.b}" #{}}})
          short-of (fn [type-name]
                     (-> (answer-provenance analysis (->cursor-input type-name :producer 0))
                         :node :type-short))]
      (is (= "DocumentCheck" (short-of "com.example.DocumentCheck")))
      (is (= ":loan/status" (short-of ":loan/status")))
      (is (= "\"a.b\"" (short-of "\"a.b\"")))
      (is (= "[:loan/status \"a.b\"]" (short-of "[:loan/status \"a.b\"]")))
      (is (= "{:k :a.b}" (short-of "{:k :a.b}")))))
  (testing "a bridge match carries the short form of the bridged type"
    (let [analysis (->analysis {:fact-types {"com.example.Parent" #{}
                                             "com.example.Child" ["com.example.Parent"]
                                             "com.example.Other" #{}}
                                :rules {"t/p" {:lhs ["com.example.Other"]
                                               :insert ["com.example.Child"]}}})
          p (first (:children (:node (answer-provenance
                                      analysis
                                      (->cursor-input "com.example.Parent" :producer 0)))))]
      (is (= [{:name "com.example.Child" :short "Child"}] (:matches p)))
      (is (true? (:bridge? p))))))

;; ---------------------------------------------------------------------------
;; Dedupe, cycles, and bounded depth
;; ---------------------------------------------------------------------------

(deftest provenance-insert-and-retract-one-type-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "S2" ["T"]}
                              :rules {"t/both" {:lhs ["T"] :insert ["S"] :retract ["S2"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer 0)))]
    (is (= ["t/both"] (collect-child-names root)) "one node, not one per via")
    (is (false? (:external? root)) "an insert+retract producer still inserts T")
    (let [p (first (:children root))]
      (is (= :retract (:via p)))
      (is (= [:insert :retract] (:vias p)))
      (is (= ["S" "S2"] (mapv :name (:matches p)))
          "matches unions the insert and retract sides"))))

(deftest provenance-self-loop-cycle-test
  (let [analysis (->analysis {:fact-types {"T" #{}}
                              :rules {"t/p" {:lhs ["T"] :insert ["T"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer 1)))
        p (first (:children root))
        back-type (first (:children p))
        back (:children back-type)]
    (is (false? (:cycle? p)) "not a cycle at level 1: ancestors is empty at a cursor root")
    (is (contains? p :children))
    (is (= "T" (:type back-type)))
    (is (true? (:cycle? (first back))))
    (is (not (contains? (first back) :children)))))

(deftest provenance-production-root-in-ancestors-is-a-cycle-test
  (testing "a production root that is already on the path reports a cycle"
    (let [analysis (->analysis {:fact-types {"T" #{}}
                                :rules {"t/p" {:lhs ["T"] :insert ["T"]}}})
          node (:node (answer-provenance analysis {:node {:kind :production :name "t/p"}
                                                   :direction :producer
                                                   :depth 0
                                                   :ancestors ["t/p"]}))]
      (is (= :production (:kind node)))
      (is (true? (:cycle? node)))
      (is (false? (:seen? node)))
      (is (not (contains? node :children))))))

(deftest provenance-cross-type-cycle-terminates-test
  (let [analysis (->analysis {:fact-types {"T" #{} "U" #{}}
                              :rules {"t/a" {:lhs ["T"] :insert ["U"]}
                                      "t/b" {:lhs ["U"] :insert ["T"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer :full)))
        b-nodes (filter #(= "t/b" (:name %)) (collect-production-nodes root))]
    (is (= 2 (count b-nodes)) "the cycle closes on the second t/b")
    (is (false? (:cycle? (first b-nodes))))
    (is (true? (:cycle? (second b-nodes))))))

(defn- ->chained-diamond-analysis
  "`k` levels of `T<i-1> → {t/p<i>, t/q<i>} → T<i>`: two productions per level insert the same type
  from the same predecessor, so the tree reconverges at every level."
  [k]
  (->analysis
   {:fact-types (into {"T0" #{}}
                      (map (fn [i] [(str "T" i) #{}]))
                      (range 1 (inc k)))
    :rules (into {}
                 (mapcat (fn [i]
                           [[(str "t/p" i) {:lhs [(str "T" (dec i))]
                                            :insert [(str "T" i)]}]
                            [(str "t/q" i) {:lhs [(str "T" (dec i))]
                                            :insert [(str "T" i)]}]]))
                 (range 1 (inc k)))}))

(deftest provenance-diamond-stays-linear-test
  (let [analysis (->analysis {:fact-types {"R" #{} "T" #{} "S" #{}}
                              :rules {"t/a" {:lhs ["S"] :insert ["T"]}
                                      "t/b" {:lhs ["T"] :insert ["R"]}
                                      "t/c" {:lhs ["T"] :insert ["R"]}}})
        root (:node (answer-provenance analysis (->cursor-input "R" :producer :full)))
        a-nodes (filter #(= "t/a" (:name %)) (collect-production-nodes root))]
    (is (= 2 (count a-nodes)) "t/a is reached through both parents but expanded once")
    (is (false? (:seen? (first a-nodes))))
    (is (true? (:seen? (second a-nodes))))
    (is (not (contains? (second a-nodes) :children)))
    (is (not-any? :cycle? (collect-production-nodes root)))))

(deftest provenance-chained-diamond-stays-linear-test
  (let [root (fn [k]
               (:node (answer-provenance (->chained-diamond-analysis k)
                                         (->cursor-input (str "T" k) :producer :full))))
        counts (mapv #(count (collect-production-nodes (root %))) [2 3 4 5 6])]
    (is (apply = (map - (rest counts) counts))
        "each extra diamond level adds a fixed number of production nodes, not a doubling")
    (is (< (last counts) 40) "the six-level tree stays small")
    (let [prods (collect-production-nodes (root 6))]
      (is (= 12 (count (filter #(seq (:children %)) prods)))
          "each production expands at most once in one response")
      (is (every? #(or (seq (:children %)) (:seen? %)) prods)
          "a node that does not expand is a :seen? repeat")
      (is (not-any? :cycle? prods) "a DAG reconvergence is not a path cycle"))))

;; ---------------------------------------------------------------------------
;; Depth, leaf kinds, and external types
;; ---------------------------------------------------------------------------

(def ^:private chain-analysis
  (->analysis {:fact-types {"A" #{} "B" #{} "C" #{} "D" #{}}
               :rules {"t/p1" {:lhs ["A"] :insert ["B"]}
                       "t/p2" {:lhs ["B"] :insert ["C"]}
                       "t/p3" {:lhs ["C"] :insert ["D"]}}}))

(deftest provenance-depth-semantics-test
  (testing "a :cursor root: n + 1 production levels below the fact type"
    (is (= ["t/p3"]
           (collect-child-names (:node (answer-provenance chain-analysis (->cursor-input "D" :producer 0))))))
    (is (= ["t/p3" "t/p2"]
           (mapv :name (collect-production-nodes (:node (answer-provenance chain-analysis
                                                                           (->cursor-input "D" :producer 1)))))))
    (is (= ["t/p3" "t/p2" "t/p1"]
           (mapv :name (collect-production-nodes (:node (answer-provenance chain-analysis
                                                                           (->cursor-input "D" :producer :full))))))))
  (testing "a :production root: depth 0 is the root plus one level below it"
    (let [root (fn [depth]
                 (:node (answer-provenance chain-analysis {:node {:kind :production :name "t/p3"}
                                                           :direction :producer
                                                           :depth depth
                                                           :ancestors []})))]
      (is (= ["t/p3" "t/p2"] (mapv :name (collect-production-nodes (root 0)))))
      (is (= ["t/p3" "t/p2" "t/p1"] (mapv :name (collect-production-nodes (root 1)))))
      (is (= ["t/p3" "t/p2" "t/p1"] (mapv :name (collect-production-nodes (root :full))))
          "the chain ends at an external leaf"))))

(deftest provenance-leaf-kinds-test
  (let [analysis (->analysis {:fact-types {"T" #{} "X" #{}}
                              :queries {"t/q" {:lhs ["T"]}}
                              :rules {"t/noout" {:lhs ["T"] :no-output-types true}
                                      "t/unlinked" {:lhs ["T"] :unlinked-rule true}
                                      "t/z" {:lhs [] :insert ["X"]}}})
        consumer-root (:node (answer-provenance analysis (->cursor-input "T" :consumer 0)))
        by-name (into {} (map (juxt :name identity)) (:children consumer-root))]
    (is (= [] (:children (by-name "t/q"))))
    (is (= "query" (:type (by-name "t/q"))))
    (is (= [] (:children (by-name "t/noout"))))
    (is (not (contains? (by-name "t/noout") :outputs)))
    (is (= [] (:children (by-name "t/unlinked"))))
    (is (= :unknown (:outputs (by-name "t/unlinked"))))
    (let [producer-root (:node (answer-provenance analysis (->cursor-input "X" :producer 0)))
          z (first (filter #(= "t/z" (:name %)) (:children producer-root)))]
      (is (= [] (:children z)) "a zero-LHS rule is a producer leaf"))))

(deftest provenance-leaves-survive-depth-expansion-test
  (testing "an unlinked consumer leaf keeps :outputs :unknown at every depth"
    (let [analysis (->analysis {:fact-types {"T" #{}}
                                :rules {"t/unlinked" {:lhs ["T"] :unlinked-rule true}}})
          leaf (fn [depth]
                 (first (:children (:node (answer-provenance
                                           analysis
                                           (->cursor-input "T" :consumer depth))))))]
      (doseq [depth [0 1 :full]]
        (testing (str "depth " depth)
          (is (= :unknown (:outputs (leaf depth))))
          (is (= [] (:children (leaf depth))))
          (is (false? (:seen? (leaf depth))))))))
  (testing "a repeated leaf is never marked :seen?"
    (let [analysis (->analysis {:fact-types {"X" #{} "A" #{} "B" #{}}
                                :rules {"t/x" {:lhs ["A" "B"] :insert ["X"]}
                                        "t/z" {:lhs [] :insert ["A" "B"]}}})
          root (:node (answer-provenance analysis (->cursor-input "X" :producer :full)))
          z-nodes (filter #(= "t/z" (:name %)) (collect-production-nodes root))]
      (is (= 2 (count z-nodes)) "once under A and once under B")
      (is (every? #(= [] (:children %)) z-nodes))
      (is (not-any? :seen? z-nodes))))
  (testing "a leaf production root answers terminally"
    (let [analysis (->analysis {:fact-types {"T" #{}}
                                :queries {"t/q" {:lhs ["T"]}}
                                :rules {"t/unlinked" {:lhs ["T"] :unlinked-rule true}}})
          root (fn [p-name direction]
                 (:node (answer-provenance analysis {:node {:kind :production :name p-name}
                                                     :direction direction
                                                     :depth 0
                                                     :ancestors []})))]
      (is (= [] (:children (root "t/unlinked" :consumer))))
      (is (= :unknown (:outputs (root "t/unlinked" :consumer))))
      (is (= [] (:children (root "t/q" :consumer))))
      (is (= "query" (:type (root "t/q" :consumer)))))))

(deftest provenance-external-retract-only-test
  (let [analysis (->analysis {:fact-types {"T" #{} "S" #{}}
                              :rules {"t/ret" {:lhs ["S"] :retract ["T"]}}})
        root (:node (answer-provenance analysis (->cursor-input "T" :producer 0)))
        p (first (:children root))]
    (is (true? (:external? root)) "nothing inserts T, even though a rule retracts it")
    (is (= ["t/ret"] (collect-child-names root)))
    (is (= :retract (:via p)))))

;; ---------------------------------------------------------------------------
;; Ordering and the error contract
;; ---------------------------------------------------------------------------

(deftest provenance-ordering-test
  (let [analysis (->analysis {:fact-types {"T" #{} "V" #{} "W" #{} "Z" #{} "A" #{} "M" #{}}
                              :queries {"t/ab" {:lhs ["T"]}}
                              :rules {"t/p" {:lhs ["T"] :insert ["Z" "A"] :retract ["M" "A"]}
                                      "t/lhs" {:lhs ["Z" "A"] :insert ["W"]}
                                      "t/zz" {:lhs ["V"] :insert ["T"]}
                                      "t/aa" {:lhs ["V"] :insert ["T"]}}})
        producer-root (:node (answer-provenance analysis (->cursor-input "T" :producer 0)))
        consumer-root (:node (answer-provenance analysis (->cursor-input "T" :consumer 1)))
        p (first (filter #(= "t/p" (:name %)) (:children consumer-root)))
        lhs-producer (first (:children (:node (answer-provenance
                                               analysis
                                               (->cursor-input "W" :producer 1)))))]
    (testing "production children of a fact type are name-sorted, rules and queries interleaved"
      (is (= ["t/aa" "t/zz"] (collect-child-names producer-root)))
      (is (= ["t/ab" "t/p"] (collect-child-names consumer-root))))
    (testing "a producer's fact-type children keep LHS declaration order"
      (is (= ["Z" "A"] (mapv :type (:children lhs-producer)))))
    (testing "consumer children are inserts in declaration order, then remaining retracts"
      (is (= ["Z" "A" "M"] (mapv :type (:children p))))
      (is (= [[:insert] [:insert :retract] [:retract]] (mapv :vias (:children p)))
          "a type both inserted and retracted appears once, in insert order"))
    (testing ":matches is name-sorted"
      (let [bridge-analysis (->analysis {:fact-types {"T" #{} "S" ["T"] "U" #{}}
                                         :rules {"t/c" {:lhs ["T" "S"] :insert ["U"]}}})
            root (:node (answer-provenance bridge-analysis (->cursor-input "S" :consumer 0)))
            c (first (:children root))]
        (is (= ["S" "T"] (mapv :name (:matches c))))))))

(deftest provenance-scoped-rhs-callsites-beat-the-global-scan-test
  (let [callsite (fn [type-name]
                   {:callsites [{:constructor-sym "t/shared"
                                 :resolved-types [{:name type-name}]}]})
        analysis (->analysis {:fact-types {"S" #{} "X" #{} "Y" #{}}
                              :rules {"t/a" {:lhs ["S"] :insert ["X"]
                                             :dynamic-insert-types-detected (callsite "X")}
                                      "t/b" {:lhs ["S"] :insert ["Y"]
                                             :dynamic-insert-types-detected (callsite "Y")}
                                      ;; no callsite of its own: scoped RHS resolution finds nothing
                                      "t/c" {:lhs ["S"] :insert ["X"]}}})
        root-type (fn [input]
                    (-> (navigate/fact-type-provenance analysis callsite-runtime
                                                       {:node {:kind :cursor :input input}
                                                        :direction :consumer
                                                        :depth 0
                                                        :ancestors []})
                        :node :type))]
    (is (= "X" (root-type {:caller-ns "t" :token "shared"}))
        "globally the shared constructor resolves to both types, sorted first")
    (is (= "Y" (root-type {:production "t/b" :side :rhs
                           :caller-ns "t" :token "shared"}))
        "scoped to t/b, its own callsite wins")
    (is (= "X" (root-type {:production "t/c" :side :rhs
                           :caller-ns "t" :token "shared"}))
        "scoped to t/c, which has no callsite, so the global scan is consulted")
    (is (= "X" (root-type {:production "t/a" :side :lhs :token "shared"}))
        "an omitted :caller-ns falls back to the enclosing production's namespace")))

(deftest provenance-error-contract-test
  (let [analysis (->analysis {:fact-types {"T" #{}}
                              :rules {"t/p" {:lhs ["T"] :insert ["T"]}}})]
    (testing "an unknown production root"
      (is (= {:error "no production named t/nope"}
             (answer-provenance analysis {:node {:kind :production :name "t/nope"}
                                          :direction :producer :depth 0 :ancestors []}))))
    (testing "an ancestor naming no production"
      (is (= {:error "no production named t/nope"}
             (answer-provenance analysis (assoc-in (->cursor-input "T" :producer 0)
                                                   [:ancestors] ["t/nope"])))))
    (testing "a token that resolves to nothing"
      (is (string? (:error (answer-provenance analysis (->cursor-input "" :producer 0))))))
    (testing "a negative depth"
      (is (string? (:error (answer-provenance analysis (->cursor-input "T" :producer -1))))))
    (testing "a depth that is neither a non-negative integer nor :full is refused, not read as 0"
      ;; `st/validate-schemas` is on for this namespace, so a bad `:depth` normally becomes an
      ;; `ExceptionInfo` from the schema rather than an `{:error …}` return. The guard matters
      ;; where validation is off (the production JVM shell, `editor-client.bb`), so pin that
      ;; posture explicitly instead of relying on it.
      (is (s/fn-validation?) "the namespace fixture enables schema fn validation")
      (s/set-fn-validation! false)
      (try
        (doseq [bad-depth [:bogus "1" 1.5 nil]]
          (testing (pr-str bad-depth)
            (is (string? (:error (answer-provenance
                                  analysis
                                  (->cursor-input "T" :producer bad-depth)))))))
        (finally
          (s/set-fn-validation! true)))
      (is (s/fn-validation?) "validation is restored for the rest of the namespace"))
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
