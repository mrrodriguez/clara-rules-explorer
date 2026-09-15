(ns clara.server.tools.graph.artifacts.compact-test
  "Pins the one property the whole encoding rests on: `<-compact` of `->compact`
  is the identity, so storing the merge by reference gives up nothing.

  The fixture is built to break a lazy implementation. It carries a rule the
  generated layer accounts for whole (the common case, 99% of a real artifact); a
  rule two layers combined, which has to be inlined; a rule only the
  higher-precedence layer has; and a rule whose merged value came from a layer
  with no file, standing in for `:props`."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clara.server.tools.graph.artifacts.compact :as compact]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(def ^:private generated :clara.tools.graph.analyze/generated)

(defn- callsite
  [id extra]
  (merge {:callsite-id id
          :status :full
          :ns-name-sym 'a.ns
          :filename "a_ns.clj"
          :constructor-sym 'acme.facts/->fact
          :resolved-types [:a/one]
          :via {:boundary-in-var 'a.ns/caller}
          :source-str "(->fact :a/one {:x ?x})"}
         extra))

(def ^:private generated-annotations
  {"a.ns/plain"
   {:clara-rules/insert-types [:a/one]}

   ;; the common case: detections, so the merged copy differs from this one by
   ;; the `:from-layer` stamp alone
   "a.ns/detected"
   {:clara-rules/insert-types [:a/one]
    :clara-rules/dynamic-insert-types-detected
    {:resolution :full :callsites [(callsite "a:->fact:aaaa1111:0" {})]}}

   ;; the memory layer adds a type to this one, so the fold really combined
   "a.ns/combined"
   {:clara-rules/insert-types [:a/one]
    :clara-rules/dynamic-insert-types-detected
    {:resolution :full :callsites [(callsite "a:->fact:bbbb2222:0" {})]}}

   "a.ns/vanished"
   {:clara-rules/insert-types [:a/gone]}})

(def ^:private memory-annotations
  {"a.ns/combined" {:clara-rules/insert-types [:a/two]}
   "a.ns/memory-only" {:clara-rules/insert-types [:a/three]}})

(def ^:private layers
  [[generated generated-annotations]
   [:memory memory-annotations]])

(defn- stamped
  "`annotation` as the fold emits it when one layer accounted for the rule."
  [layer-id annotation]
  (cond-> annotation
    (get-in annotation [:clara-rules/dynamic-insert-types-detected :callsites])
    (update-in [:clara-rules/dynamic-insert-types-detected :callsites]
               (partial mapv #(assoc % :from-layer layer-id)))))

(def ^:private merged
  {:layers [{:id generated :source "auto-gen-annotations.edn"}
            {:id :memory :source "memory-annotations.edn"}]
   :annotations
   (into (sorted-map)
         {"a.ns/plain" (get generated-annotations "a.ns/plain")
          "a.ns/detected" (stamped generated (get generated-annotations "a.ns/detected"))
          ;; union of both layers' types — matches neither layer alone
          "a.ns/combined" (assoc (stamped generated (get generated-annotations "a.ns/combined"))
                                 :clara-rules/insert-types [:a/one :a/two])
          "a.ns/memory-only" (get memory-annotations "a.ns/memory-only")
          "a.ns/vanished" (get generated-annotations "a.ns/vanished")
          ;; no layer file holds this — the props layer's contribution
          "a.ns/from-props" {:clara-rules/insert-types [:a/props] :salience -1000}})
   :provenance
   {"a.ns/plain" {:clara-rules/insert-types [generated]}
    "a.ns/detected" {:clara-rules/insert-types [generated]
                     :clara-rules/dynamic-insert-types-detected [generated]}
    "a.ns/combined" {:clara-rules/insert-types [generated :memory]
                     :clara-rules/dynamic-insert-types-detected [generated]}
    "a.ns/memory-only" {:clara-rules/insert-types [:memory]}
    "a.ns/vanished" {:clara-rules/insert-types [generated]}
    "a.ns/from-props" {:clara-rules/insert-types :props :salience :props}}})

(deftest round-trip-is-the-identity-test
  (testing "the entire license for storing the merge by reference"
    (is (= merged (compact/<-compact (compact/->compact merged layers) layers))))

  (testing "compacting is stable — the same merge encodes the same way twice, so
            an unchanged rulebase rewrites a byte-identical file"
    (is (= (compact/->compact merged layers) (compact/->compact merged layers))))

  (testing "expanding is idempotent through a second compaction"
    (let [once (compact/<-compact (compact/->compact merged layers) layers)]
      (is (= once (compact/<-compact (compact/->compact once layers) layers))))))

(deftest only-what-no-layer-holds-is-written-test
  (let [{:keys [annotations verbatim]} (compact/->compact merged layers)]

    (testing "a rule a layer accounts for whole is not restated, even when the
              fold stamped `:from-layer` onto its callsites"
      (is (not (contains? annotations "a.ns/plain")))
      (is (not (contains? annotations "a.ns/detected"))))

    (testing "a rule the fold COMBINED is inlined whole — there is nothing to
              point at, and it keeps its evidence"
      (is (contains? annotations "a.ns/combined"))
      (is (= [:a/one :a/two] (get-in annotations ["a.ns/combined" :clara-rules/insert-types])))
      (is (= "(->fact :a/one {:x ?x})"
             (get-in annotations ["a.ns/combined"
                                  :clara-rules/dynamic-insert-types-detected
                                  :callsites 0 :source-str]))))

    (testing "a rule from a layer with no FILE is inlined too — the props layer
              has nothing on disk for a reference to resolve against"
      (is (= {:clara-rules/insert-types [:a/props] :salience -1000}
             (get annotations "a.ns/from-props"))))

    (testing "the dominant layer is the default and the odd one out is named"
      (is (= generated (:default verbatim)))
      (is (= {"a.ns/memory-only" :memory} (:except verbatim))))

    (testing "the layer's extra rule is accounted for — `a.ns/vanished` is in the
              generated layer and in the merge, so it round-trips like any other"
      (is (contains? (:annotations (compact/<-compact (compact/->compact merged layers) layers))
                     "a.ns/vanished")))))

(deftest refuses-to-compact-a-merge-missing-a-layer-rule-test
  (testing "expansion enumerates rules from the layers, so a layer rule the merge
            does not have would come back from the dead. Rather than carry an
            exclusion list in every file for a case `merge-layers` has never
            produced, the writer refuses and names the rules"
    (let [incomplete (update merged :annotations dissoc "a.ns/vanished")
          thrown (try (compact/->compact incomplete layers) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= ["a.ns/vanished"] (:missing-rules (ex-data thrown)))))))

(deftest provenance-says-nothing-about-a-by-reference-rule-test
  (let [{:keys [annotations provenance]} (compact/->compact merged layers)]

    (testing "the template is what a by-reference rule's origins look like, per
              key — the layer is known from `:verbatim`, so all this adds is the
              shape clara-rules-explorer writes each key's origin in"
      (is (= {:clara-rules/insert-types [generated]
              :clara-rules/dynamic-insert-types-detected [generated]}
             (:verbatim provenance))))

    (testing "a rule stored by reference has NO provenance entry. It came whole
              from one layer; saying so again per key is noise"
      (is (not (contains? (:except provenance) "a.ns/plain")))
      (is (not (contains? (:except provenance) "a.ns/detected"))))

    (testing "the rules that DO carry provenance are the inlined ones, plus any
              by-reference rule whose origins name a layer the template does not.
              In both real artifacts that second group is empty and the two lists
              coincide exactly (23 of 23, 68 of 68); here `a.ns/memory-only` is
              built to populate it"
      (is (= (conj (set (keys annotations)) "a.ns/memory-only")
             (set (keys (:except provenance)))))
      (is (= {:clara-rules/insert-types [:memory]}
             (get-in provenance [:except "a.ns/memory-only"]))))

    (testing "and they carry it whole, not as an overlay on the template, so
              there is no precedence to reason about when reading one"
      (is (= {:clara-rules/insert-types [generated :memory]
              :clara-rules/dynamic-insert-types-detected [generated]}
             (get-in provenance [:except "a.ns/combined"])))
      (is (= {:clara-rules/insert-types :props :salience :props}
             (get-in provenance [:except "a.ns/from-props"]))))

    (testing "sorted, so the file is stable across runs"
      (is (= (keys (:except provenance)) (sort (keys (:except provenance))))))))

(deftest tolerates-a-layer-that-is-no-longer-there-test
  (testing "a reference to a missing layer drops its rule rather than throwing —
            the same posture as the rest of the store, where a missing artifact
            is an absent one"
    (let [compacted (compact/->compact merged layers)
          without-memory (compact/<-compact compacted [[generated generated-annotations]])]
      (is (not (contains? (:annotations without-memory) "a.ns/memory-only")))
      (testing "…and everything the surviving layer answers still resolves"
        (is (= (stamped generated (get generated-annotations "a.ns/detected"))
               (get-in without-memory [:annotations "a.ns/detected"])))
        (is (contains? (:annotations without-memory) "a.ns/combined"))))))

(deftest empty-and-layerless-merges-test
  (testing "an empty merge encodes and decodes without inventing a default"
    (let [empty-merge {:layers [] :annotations (sorted-map) :provenance {}}]
      (is (nil? (get-in (compact/->compact empty-merge []) [:verbatim :default])))
      (is (= empty-merge (compact/<-compact (compact/->compact empty-merge []) [])))))

  (testing "a merge no layer file backs inlines everything, which is the
            degenerate case rather than a failure"
    (let [compacted (compact/->compact merged [])]
      (is (= (:annotations merged) (:annotations compacted)))
      (is (= merged (compact/<-compact compacted []))))))
