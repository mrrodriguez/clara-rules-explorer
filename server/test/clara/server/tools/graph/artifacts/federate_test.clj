(ns clara.server.tools.graph.artifacts.federate-test
  "The federated index over the two checked-in `rules-annos/` units. They share
  the fact-type vocabulary around `:loan-app/application-outcome` without being
  one rulebase — `loan-app-ruleset` produces `ApplicationOutcome` (which derives
  the keyword), `loan-disposition-ruleset` matches the keyword. The index is
  where the cross-unit contract becomes queryable."
  (:require
   [clara.rules :as r]
   [clara.server.tools.graph.annotations.merge :as am]
   [clara.server.tools.graph.artifacts.federate :as federate]
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.registry :as registry]
   [clara.server.tools.graph.artifacts.store :as store]
   [clara.server.tools.graph.core :as core]
   [clara.server.tools.graph.edn-io :as edn-io]
   [clara.server.tools.graph.rules.loan-app-rules]
   [clara.server.tools.graph.rules.loan-doc-queries]
   [clara.server.tools.graph.rules.loan-doc-rules]
   [clara.server.tools.graph.rules.loan-outcome-notices]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def app-outcome "clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome")
(def keyword-outcome ":loan-app/application-outcome")
(def notice-approved "clara.server.tools.graph.rules.loan-outcome-notices/notice-approved-app")
(def notice-denied "clara.server.tools.graph.rules.loan-outcome-notices/notice-denied-app")

(defn- registry-root []
  (-> (io/resource "rules-annos/loan-app-ruleset/rules-inspect-manifest.edn")
      .getPath
      io/file
      .getParentFile
      .getParentFile
      .getPath))

(defn- ->index []
  (federate/->index (registry/discover {:root (registry-root)})
                    [{:repo "loan-app-ruleset"}
                     {:repo "loan-disposition-ruleset"}]))

(defn- ->reference-analysis
  "The monolithic truth: the loan-doc/app session with the downstream notices
  ruleset wired on, the same session `integration-test/run-loan-outcome-notices`
  builds."
  []
  (let [session (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                              'clara.server.tools.graph.rules.loan-app-rules
                              'clara.server.tools.graph.rules.loan-doc-queries
                              'clara.server.tools.graph.rules.loan-outcome-notices)]
    (core/->rulebase-analysis session (am/annotations (am/merge-layers [(am/->props-layer session)])))))

(deftest index-records-the-cross-unit-contract-test
  (let [index (->index)]
    (testing "the hierarchy carries the derive from the producing unit"
      (is (contains? (get-in index [:hierarchy :ancestors app-outcome])
                     keyword-outcome))
      (is (contains? (get-in index [:hierarchy :descendants keyword-outcome])
                     app-outcome)))

    (testing "the shared type names its producers and consumers by unit"
      (let [entry (get-in index [:fact-types keyword-outcome])]
        (is (= #{"loan-app-ruleset"} (:producers entry))
            "the keyword is produced by the unit inserting a derived type")
        (is (= #{"loan-disposition-ruleset"} (:consumers entry)))
        (is (= {"loan-disposition-ruleset" #{notice-approved notice-denied}}
               (:matched-by entry)))))

    (testing "the unit edge is the fact a per-unit artifact cannot contain"
      (is (= {:via #{keyword-outcome} :rules 2}
             (get (:unit-edges index) ["loan-app-ruleset" "loan-disposition-ruleset"]))))

    (testing "provenance carries each unit's manifest sha"
      (let [manifest (edn-io/read-edn-file
                      (io/file (registry-root) "loan-app-ruleset" "rules-inspect-manifest.edn"))]
        (is (= (get-in manifest [:source :sha])
               (get-in index [:provenance "loan-app-ruleset" :sha])))))))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-federate-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- write-manifest!
  ([dir repo] (write-manifest! dir repo {}))
  ([dir repo analysis-run]
   (let [file (io/file dir (:manifest layout/artifact-files))]
     (io/make-parents file)
     (edn-io/write-edn-file! file {:repo repo
                                   :generated-by "federate-test"
                                   :created "2025-01-01"
                                   :analysis-run (merge {:layer-ids store/layer-artifacts}
                                                        analysis-run)
                                   :history []})
     (str file))))

(defn- write-analysis! [dir rules fact-types]
  (store/write-analysis-parts!
   {:dir (str dir)}
   {:rules rules
    :queries {}
    :fact-types fact-types
    :dep-graph {}
    :unresolved []
    :slim {:written-by "clara.server.tools.graph.artifacts.slim"
           :dropped #{:nodes :id}
           :references "test"
           :unknown-fact-types #{}
           :recover {}}}))

(deftest query-fns-answer-over-the-index-test
  (let [index (->index)]
    (testing "impact-of names the downstream rules that break"
      (let [impact (federate/impact-of index keyword-outcome)]
        (is (= {"loan-disposition-ruleset" #{notice-approved notice-denied}}
               (:matched-by impact)))))

    (testing "producers-of names the inserting rules (direct insert, so empty here)"
      (is (= {} (federate/producers-of index keyword-outcome))))

    (testing "dependents-of and paths-between expose the unit dependency"
      (is (= {"loan-disposition-ruleset" {:via #{keyword-outcome} :rules 2}}
             (federate/dependents-of index "loan-app-ruleset")))
      (is (= #{["loan-app-ruleset" "loan-disposition-ruleset"]}
             (federate/paths-between index "loan-app-ruleset" "loan-disposition-ruleset"))))

    (testing "coverage reports no unknown namespaces between the two units"
      (is (= [] (:unknown-namespaces (federate/coverage-report index)))))))

(deftest entry-points-and-orphans-are-fact-types-not-lhs-vectors-test
  (let [index (->index)
        entries (:entry-points index)
        orphans (:orphans index)]
    (testing "every entry point and orphan is a fact-type name, not an :lhs-types vector"
      (is (every? (fn [[_ fts]] (every? #(contains? (:fact-types index) %) fts)) entries))
      (is (every? (fn [[_ fts]] (every? #(contains? (:fact-types index) %) fts)) orphans)))
    (testing "the consuming unit has no entry points or orphans: its consumed
              type is produced by the other unit through the hierarchy"
      (is (not (contains? entries "loan-disposition-ruleset")))
      (is (not (contains? orphans "loan-disposition-ruleset"))))
    (testing "the producer unit keeps only the types nothing in scope satisfies"
      (is (= 6 (count (get entries "loan-app-ruleset"))))
      (is (= 6 (count (get orphans "loan-app-ruleset")))))))

(deftest namespace-filter-narrows-productions-not-just-scope-test
  (let [index (federate/->index (registry/discover {:root (registry-root)})
                                [{:repo "loan-app-ruleset"
                                  :namespaces ["clara.server.tools.graph.rules.loan-doc-rules"]}
                                 {:repo "loan-disposition-ruleset"}])]
    (testing "the fact-type map stays whole (keyed by type, not namespace)"
      (is (= 38 (count (:fact-types index)))))
    (testing "the cross-unit edge disappears once its producer is filtered out"
      (is (not (contains? (:unit-edges index)
                          ["loan-app-ruleset" "loan-disposition-ruleset"]))))
    (testing "the type the excluded producer supplied becomes an entry point"
      (is (contains? (get-in index [:entry-points "loan-disposition-ruleset"])
                     keyword-outcome)))
    (testing "scope still reports the narrowed namespace"
      (is (= ["clara.server.tools.graph.rules.loan-doc-rules"]
             (get-in index [:scope :namespaces "loan-app-ruleset"]))))))

(deftest retract-couples-units-but-does-not-supply-test
  (let [dir (temp-dir)]
    (try
      (let [retractor (io/file dir "retractor")
            consumer (io/file dir "consumer")]
        (write-manifest! retractor "retractor")
        (write-analysis! retractor
                         {"a.ns/retract-t" {:ns "a.ns" :name "a.ns/retract-t"
                                            :lhs-types [] :insert-types [] :retract-types ["T"]}}
                         {"T" {:name "T" :ns nil :ancestors []}})
        (write-manifest! consumer "consumer")
        (write-analysis! consumer
                         {"b.ns/consume-t" {:ns "b.ns" :name "b.ns/consume-t"
                                            :lhs-types ["T"] :insert-types [] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})

        (let [index (federate/->index (registry/discover {:root dir})
                                      [{:repo "retractor"} {:repo "consumer"}])]
          (testing "a retract couples the units, so the edge exists"
            (is (= {:via #{"T"} :rules 1}
                   (get (:unit-edges index) ["retractor" "consumer"]))))
          (testing "a retract does not supply the type, so it is still an entry point"
            (is (= #{"T"} (get-in index [:entry-points "consumer"]))))
          (testing "a retract-only rule produces no orphan"
            (is (not (contains? (:orphans index) "retractor"))))
          (testing "the type records the retract apart from its producers"
            (is (= #{} (get-in index [:fact-types "T" :producers])))
            (is (= {"retractor" #{"a.ns/retract-t"}}
                   (get-in index [:fact-types "T" :retracted-by]))))))
      (finally (delete-tree dir)))))

(deftest aggregate-unit-beside-its-sources-is-refused-test
  (let [dir (temp-dir)]
    (try
      (let [src-a (io/file dir "src-a")
            src-b (io/file dir "src-b")
            agg (io/file dir "agg")]
        (write-manifest! src-a "src-a")
        (write-analysis! src-a
                         {"a.ns/insert-t" {:ns "a.ns" :name "a.ns/insert-t"
                                           :lhs-types [] :insert-types ["T"] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})
        (write-manifest! src-b "src-b")
        (write-analysis! src-b
                         {"b.ns/consume-t" {:ns "b.ns" :name "b.ns/consume-t"
                                            :lhs-types ["T"] :insert-types [] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})
        (write-manifest! agg "agg" {:mode :compose
                                    :units [{:repo "src-a"} {:repo "src-b"}]})
        (write-analysis! agg {} {"T" {:name "T" :ns nil :ancestors []}})
        (let [reg (registry/discover {:root dir})
              selection [{:repo "src-a"} {:repo "src-b"} {:repo "agg"}]]
          (testing "an aggregate selected beside its sources is refused outright"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"is composed from selected"
                                  (federate/->index reg selection))))
          (testing "the sources alone keep the one cross-unit edge"
            (is (= 1 (count (:unit-edges
                             (federate/->index reg [{:repo "src-a"} {:repo "src-b"}]))))))))
      (finally (delete-tree dir)))))

(deftest aggregate-source-mix-is-refused-test
  (let [dir (temp-dir)]
    (try
      (let [src (io/file dir "src")
            agg (io/file dir "agg")]
        (write-manifest! src "src")
        (write-analysis! src
                         {"a.ns/insert-t" {:ns "a.ns" :name "a.ns/insert-t"
                                           :lhs-types [] :insert-types ["T"] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})
        ;; a captured whole-rulebase unit: an aggregate naming no composed-from
        (write-manifest! agg "agg" {:mode :captured-session})
        (write-analysis! agg {} {"T" {:name "T" :ns nil :ancestors []}})
        (let [reg (registry/discover {:root dir})
              selection [{:repo "src"} {:repo "agg"}]]
          (testing "aggregate + source is never one question"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"mixes 1 aggregate unit"
                                  (federate/->index reg selection))))
          (testing "an aggregate alone indexes normally"
            (is (= ["agg"]
                   (get-in (federate/->index reg [{:repo "agg"}])
                           [:coverage :units]))))))
      (finally (delete-tree dir)))))

(deftest grade-against-a-composed-reference-test
  (let [index (->index)
        graded (federate/grade index (->reference-analysis))]
    (testing "the reference confirms the cross-unit edge"
      (is (contains? (set (:confirmed-unit-edges graded))
                     ["loan-app-ruleset" "loan-disposition-ruleset"])))
    (testing "no index edge is contradicted"
      (is (= [] (:contradicted-unit-edges graded))))
    (testing "every reference namespace is covered by a unit"
      (is (= [] (:uncovered-namespaces graded))))
    (testing "the union had no entry point the reference actually produces"
      (is (empty? (:reference-produced-entry-points graded))))))

(deftest digest-and-persist-round-trip-test
  (let [index (->index)
        digest (federate/->digest index)]
    (testing "->digest reduces the index to counts + the work lists"
      (is (= 2 (get-in digest [:summary :unit-count])))
      (is (= 1 (get-in digest [:summary :unit-edge-count])))
      (is (= #{keyword-outcome}
             (:via (get (:unit-edges digest) ["loan-app-ruleset" "loan-disposition-ruleset"]))))
      (is (string? (:more digest))))

    (testing "persist! writes both files and they read back equal"
      (let [dir (temp-dir)]
        (try
          (let [{written-index :index written-digest :digest} (federate/persist! index {:dir dir})]
            (is (= (->index) (edn-io/read-edn-file (io/file written-index))))
            (is (= (federate/->digest (->index)) (edn-io/read-edn-file (io/file written-digest)))))
          (finally (delete-tree dir)))))))

(deftest read-index-read-digest-and-label-test
  (let [label "what does loan-disposition consume?"
        index (federate/->index (registry/discover {:root (registry-root)})
                                [{:repo "loan-app-ruleset"}
                                 {:repo "loan-disposition-ruleset"}]
                                {:label label})]
    (testing "->index records the label in :scope"
      (is (= label (get-in index [:scope :label]))))
    (testing "->digest surfaces the same scope"
      (is (= label (get-in (federate/->digest index) [:scope :label]))))
    (let [dir (temp-dir)]
      (try
        (federate/persist! index {:dir dir})
        (testing "read-index returns the persisted index value"
          (is (= index (federate/read-index {:dir dir})))
          (is (= label (get-in (federate/read-index {:dir dir}) [:scope :label]))))
        (testing "read-digest returns the digest with the scope label"
          (is (= label (get-in (federate/read-digest {:dir dir}) [:scope :label]))))
        (testing "persist! can label an unlabelled index at write time"
          (let [unlabelled (federate/->index (registry/discover {:root (registry-root)})
                                             [{:repo "loan-app-ruleset"}
                                              {:repo "loan-disposition-ruleset"}])]
            (federate/persist! unlabelled {:dir dir :label "late label"})
            (is (= "late label"
                   (get-in (federate/read-index {:dir dir}) [:scope :label])))))
        (finally (delete-tree dir))))))

(deftest diff-of-an-index-against-itself-is-empty-test
  (let [index (->index)]
    (is (= {:units {:added [] :removed [] :rebased []}
            :unit-edges {:added [] :removed [] :changed {}}
            :fact-types {}
            :entry-points {:added {} :resolved {}}
            :orphans {:added {} :resolved {}}
            :hierarchy {:conflicts-added [] :conflicts-resolved []}}
           (federate/diff index index)))))

(deftest diff-branch-variant-reports-only-touched-edges-and-types-test
  (let [dir (temp-dir)]
    (try
      (let [src-a (io/file dir "src-a")
            src-a-branch (io/file dir "src-a" "branches" "feature-x")
            src-b (io/file dir "src-b")]
        (write-manifest! src-a "src-a")
        (write-analysis! src-a
                         {"a.ns/insert-t" {:ns "a.ns" :name "a.ns/insert-t"
                                           :lhs-types [] :insert-types ["T"] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})
        (write-manifest! src-a-branch "src-a")
        (write-analysis! src-a-branch
                         {"a.ns/insert-t2" {:ns "a.ns" :name "a.ns/insert-t2"
                                            :lhs-types [] :insert-types ["T2"] :retract-types []}}
                         {"T2" {:name "T2" :ns nil :ancestors []}})
        (write-manifest! src-b "src-b")
        (write-analysis! src-b
                         {"b.ns/consume-t" {:ns "b.ns" :name "b.ns/consume-t"
                                            :lhs-types ["T"] :insert-types [] :retract-types []}}
                         {"T" {:name "T" :ns nil :ancestors []}})

        (let [reg (registry/discover {:root dir})
              before (federate/->index reg [{:repo "src-a"} {:repo "src-b"}])
              after (federate/->index reg [{:repo "src-a" :branch "feature-x"}
                                           {:repo "src-b"}])
              d (federate/diff before after)]
          (testing "the selection reports the branch swap alongside the raw add/remove"
            (is (= [{:repo "src-a" :from ["src-a"] :to ["src-a@feature-x"]}]
                   (get-in d [:units :rebased])))
            (is (= ["src-a@feature-x"] (get-in d [:units :added])))
            (is (= ["src-a"] (get-in d [:units :removed]))))
          (testing "only the touched edge disappears"
            (is (= [["src-a" "src-b"]] (get-in d [:unit-edges :removed])))
            (is (= [] (get-in d [:unit-edges :added])))
            (is (= {} (get-in d [:unit-edges :changed]))))
          (testing "the touched fact types record the producer/consumer change"
            (is (= {"T" {:producers {:added #{} :removed #{"src-a"}}}
                    "T2" {:producers {:added #{"src-a@feature-x"} :removed #{}}}}
                   (:fact-types d))))
          (testing "the downstream entry point appears and the orphan moves"
            (is (= {"src-b" #{"T"}} (get-in d [:entry-points :added])))
            (is (= {} (get-in d [:entry-points :resolved])))
            (is (= {"src-a@feature-x" #{"T2"}} (get-in d [:orphans :added])))
            (is (= {} (get-in d [:orphans :resolved]))))
          (testing "hierarchy conflicts did not change"
            (is (= {:conflicts-added [] :conflicts-resolved []}
                   (:hierarchy d))))))
      (finally (delete-tree dir)))))

(deftest namespace-filter-narrows-scope-and-reports-unknowns-test
  (let [index (federate/->index (registry/discover {:root (registry-root)})
                                [{:repo "loan-app-ruleset" :namespaces ["no.such.ns"]}
                                 {:repo "loan-disposition-ruleset"}])]
    (testing "a requested namespace the unit does not cover is reported, not in scope"
      (is (= ["no.such.ns"] (get-in index [:coverage :unknown-namespaces])))
      (is (= [] (get-in index [:scope :namespaces "loan-app-ruleset"]))))
    (testing "a unit without a filter keeps its full namespace scope"
      (is (seq (get-in index [:scope :namespaces "loan-disposition-ruleset"]))))))
