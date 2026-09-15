(ns clara.server.tools.graph.artifacts.parts-test
  "Pins the two properties that make splitting the analysis safe.

  **The split loses nothing.** `<-parts` reconstructs what `->parts` was given,
  exactly. The analysis is write-only from this repo, so nothing here would
  notice a field quietly falling between two parts — this is what notices.

  **An unrecognized field lands in the index.** `condition-keys` and
  `detail-keys` are closed sets and the index is everything else, so a key added
  to a production tomorrow is written to the scanned file rather than dropped.
  The file grows, which is the direction `..slim` also chooses to fail in.

  **An unrecognized top-level key is refused.** There are six files and none of
  them is a catch-all, so the same forgiveness one level up would drop the key
  silently — which is exactly how `:ns-deps` came to be missing from these
  artifacts without anything saying so."
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.parts :as parts]
   [clara.server.tools.graph.artifacts.slim :as slim]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(def ^:private analysis
  {:rules
   {"a.ns/producer"
    {:name "a.ns/producer" :ns "a.ns"
     :lhs [{:type "a/one" :constraints "[]"}]
     :lhs-types ["a/one"] :insert-types ["b/two"] :retract-types []
     :source-rule true :sink-rule false :unlinked-rule false
     :rhs-form "(insert! (->fact :b/two {}))\n" :doc "produces two"
     :props {} :notes nil}

    ;; no :doc, no :notes, and an empty :props — the detail projection has to
    ;; keep the keys that are present and not invent the ones that are not
    "b.ns/consumer"
    {:name "b.ns/consumer" :ns "b.ns"
     :lhs [{:type "b/two" :constraints "[]"}]
     :lhs-types ["b/two"] :insert-types [] :retract-types ["a/one"]
     :sink-rule true
     :rhs-form "(retract! ?f)\n"}

    ;; a production with nothing outside the index at all
    "c.ns/bare" {:name "c.ns/bare" :ns "c.ns" :unlinked-rule true}}

   :queries
   {"q.ns/all-twos"
    {:name "q.ns/all-twos" :ns "q.ns"
     :lhs [{:type "b/two" :constraints "[]" :fact-binding :?output}]
     :lhs-types ["b/two"] :params #{}}}

   :fact-types
   {"a/one" {:name "a/one" :ns "a" :ancestors [] :retracted-by-rules ["b.ns/consumer"]}
    "b/two" {:name "b/two" :ns "b" :ancestors ["a/one"]}}

   :dep-graph
   {"a.ns/producer" {:upstream #{}}
    "b.ns/consumer" {:upstream #{"a.ns/producer"}}}

   :unresolved [{:rule "c.ns/bare" :reason :no-detections}]
   :slim {:written-by "clara.server.tools.graph.artifacts.slim"
          :dropped #{:nodes :downstream}}})

(deftest split-round-trips-exactly-test
  (let [split (parts/->parts analysis)]
    (testing "every field comes back where it started"
      (is (= analysis (parts/<-parts split))))

    (testing "…including when a part is empty, which must not conjure a section
              the analysis never had"
      (let [rules-only (select-keys analysis [:rules])]
        (is (= rules-only (parts/<-parts (parts/->parts rules-only)))))
      (is (= {} (parts/<-parts (parts/->parts {})))))

    (testing "…and a section that is present but EMPTY comes back present and
              empty, not absent. `..slim` goes out of its way to leave a partial
              analysis partial (`update-present`), so the split may not quietly
              complete one"
      (doseq [empty-analysis [{:rules {}}
                              {:rules {} :queries {}}
                              {:fact-types {}}
                              {:dep-graph {}}
                              {:rules {} :fact-types {} :dep-graph {}}]]
        (is (= empty-analysis (parts/<-parts (parts/->parts empty-analysis)))
            (pr-str empty-analysis))))))

(deftest a-name-that-is-both-a-rule-and-a-query-is-refused-test
  (testing "`production-conditions.edn` and `production-details.edn` are keyed by
            production name alone, so a collision would hand one production the
            other's `:lhs` — silently, and the round trip above would not see it.
            Production names are fully-qualified var names and so never collide
            in practice, which is exactly why this must fail loudly rather than
            be trusted"
    (let [collided (assoc-in analysis [:queries "a.ns/producer"]
                             {:name "a.ns/producer" :ns "a.ns"
                              :lhs [{:type "z/other" :constraints "[]"}]})
          thrown (is (thrown? clojure.lang.ExceptionInfo (parts/->parts collided)))]
      (is (= ["a.ns/producer"] (:colliding-names (ex-data thrown)))))))

(deftest each-field-lands-in-exactly-one-part-test
  (let [{:keys [index conditions details fact-types dep-graph meta]} (parts/->parts analysis)]

    (testing "the index carries the scan projection and keeps rules apart from
              queries — that distinction is the only thing on a production
              saying which it is"
      (is (= #{:rules :queries} (set (keys index))))
      (is (= {:name "a.ns/producer" :ns "a.ns"
              :lhs-types ["a/one"] :insert-types ["b/two"] :retract-types []
              :source-rule true :sink-rule false :unlinked-rule false}
             (get-in index [:rules "a.ns/producer"])))
      (is (= ["q.ns/all-twos"] (keys (:queries index)))))

    (testing "conditions hold :lhs and nothing else, flat across rules and
              queries — production names are unique across the two"
      (is (= {:lhs [{:type "a/one" :constraints "[]"}]}
             (get conditions "a.ns/producer")))
      (is (contains? conditions "q.ns/all-twos")))

    (testing "details hold the RHS and the documentation"
      (is (= {:rhs-form "(insert! (->fact :b/two {}))\n" :doc "produces two"
              :props {} :notes nil}
             (get details "a.ns/producer")))
      (is (= {:rhs-form "(retract! ?f)\n"} (get details "b.ns/consumer"))))

    (testing "a production with nothing for a part is absent from it rather than
              present and empty"
      (is (not (contains? details "c.ns/bare")))
      (is (not (contains? conditions "c.ns/bare")))
      (is (contains? (:rules index) "c.ns/bare")))

    (testing "the graphs and the small stuff go whole"
      (is (= (:fact-types analysis) fact-types))
      (is (= (:dep-graph analysis) dep-graph))
      (is (= {:slim (:slim analysis) :unresolved (:unresolved analysis)} meta)))

    (testing "no production field is in two parts at once"
      (doseq [production-name (keys (:rules analysis))]
        (is (empty? (set/intersection
                     (set (keys (get conditions production-name)))
                     (set (keys (get details production-name)))))
            production-name)))))

(deftest an-unknown-field-goes-to-the-index-test
  (testing "`condition-keys` and `detail-keys` are closed and the index is
            everything else, so a field upstream adds is written rather than
            dropped. Growing the scanned file is the safe direction to fail"
    (let [with-new (assoc-in analysis [:rules "a.ns/producer" :some-new-upstream-key] 42)
          {:keys [index]} (parts/->parts with-new)]
      (is (= 42 (get-in index [:rules "a.ns/producer" :some-new-upstream-key])))
      (is (= with-new (parts/<-parts (parts/->parts with-new)))))))

(deftest an-unknown-top-level-key-is-refused-test
  (testing "a top-level key no part holds has nowhere to be written and cannot
            come back out of `<-parts`, so it is refused rather than dropped.
            Routing it to the index the way an unknown *production* field is
            routed is not available here: the index is a map of productions, and
            a graph or a marker is not one"
    (let [with-new (assoc analysis :ns-deps {'a.ns {:require []}})
          thrown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no part holds"
                                       (parts/->parts with-new)))]
      (is (= [:ns-deps] (:unplaceable-keys (ex-data thrown))))))

  (testing "the placeable set is exactly what `->parts` writes, so the check
            cannot drift from the split it guards"
    (is (= parts/placeable-top-level-keys (set (keys analysis)))))

  (testing "and the way out is a deliberate drop in `slim`, which is why
            `slim/dropped-top-level-keys` and this set do not overlap"
    (is (empty? (filter parts/placeable-top-level-keys slim/dropped-top-level-keys)))))

(deftest parts-are-sorted-test
  (testing "sorted at every level. Map iteration order is already a function of
            the key set rather than of insertion, so this is not a determinism
            fix — it makes a multi-megabyte EDN file bisectable and greppable,
            which it otherwise is not"
    (let [{:keys [index conditions details fact-types dep-graph]} (parts/->parts analysis)
          sorted? #(= (keys %) (sort (keys %)))]
      (is (sorted? (:rules index)))
      (is (sorted? conditions))
      (is (sorted? details))
      (is (sorted? fact-types))
      (is (sorted? dep-graph)))))
