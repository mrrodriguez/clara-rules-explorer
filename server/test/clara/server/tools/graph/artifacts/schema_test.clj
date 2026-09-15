(ns clara.server.tools.graph.artifacts.schema-test
  "Where a schema restates something the code also states, this is what keeps the
  two in step.

  Every other test namespace here runs under `st/validate-schemas`, so the shapes
  reached by an actual call are already checked against real values. What that
  cannot catch is a schema *enumeration* drifting from the map it enumerates —
  `ArtifactKey` against `store/artifact-files`, `LayerArtifactKey` against
  `store/layer-artifacts` — because nothing fails until someone passes the key
  that was added and never listed. So those are asserted directly, off the
  schema's own values."
  (:require
   [clara.server.tools.graph.artifacts.parts :as parts]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.artifacts.store :as store]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [schema.core :as s]
   [schema.test :as st]))

(set! *warn-on-reflection* true)

(use-fixtures :once st/validate-schemas)

(defn- enum-values
  "The values a `s/enum` admits. Read off the schema rather than restated, so
  these tests compare the code against the schema and not against a third copy."
  [enum-schema]
  (set (:vs enum-schema)))

(deftest artifact-key-enumerates-every-artifact-test
  (testing "ArtifactKey is exactly the roles store has filenames for — a new
            artifact that is not in the enum is one no annotated fn will accept"
    (is (= (set (keys store/artifact-files)) (enum-values schema/ArtifactKey))))
  (testing "LayerArtifactKey is exactly the ones that are Layer files"
    (is (= (set (keys store/layer-artifacts)) (enum-values schema/LayerArtifactKey))))
  (testing "…and is a subset of ArtifactKey, since every layer is an artifact"
    (is (empty? (remove (enum-values schema/ArtifactKey)
                        (enum-values schema/LayerArtifactKey))))))

(deftest analysis-part-key-enumerates-every-part-test
  (testing "AnalysisPartKey is exactly the files the analysis directory holds"
    (is (= (set (keys parts/part-files)) (enum-values schema/AnalysisPartKey)))))

(deftest detection-dimension-covers-the-overlay-mapping-test
  (testing "DetectionDimension names exactly the dimensions the overlay can file
            a resolution under. A dimension the report emits but this enum omits
            would make every resolution for it fail validation"
    (is (= (set (keys schema/detection-keys-by-dimension))
           (enum-values schema/DetectionDimension)))))

(deftest session-or-rulebase-accepts-both-shapes-test
  (testing "a bare rulebase is recognized by :productions"
    (is (nil? (s/check schema/SessionOrRulebase {:productions []}))))
  (testing "and a map that is neither is not a session, however plausible"
    (is (some? (s/check schema/SessionOrRulebase {:rules []})))
    (is (some? (s/check schema/SessionOrRulebase ::not-a-session)))))

(deftest fact-type-admits-every-token-kind-test
  (testing "keywords, compound keyword-headed vectors, and the class-name forms
            clara's own types arrive as"
    (doseq [t [:loan/applicant
               [:previous :loan/applicant]
               [:acme/loan-data :vendor-data]
               'java.lang.String
               "a/one"
               String]]
      (is (nil? (s/check schema/FactType t)) (pr-str t))))
  (testing "but not nil, and not a map — a resolver that could not read a type
            reports no types at all rather than naming one of those as a type"
    (is (some? (s/check schema/FactType {:fact/type :a/one})))
    (is (some? (s/check schema/FactType nil)))
    (is (some? (s/check schema/ResolvedTypes {:resolved-types [nil]})))))

(deftest artifact-opts-stays-open-test
  (testing "the steps that resolve a dir also carry their own keys on the same
            map, so a closed ArtifactOpts would reject every real call"
    (is (nil? (s/check schema/ArtifactOpts
                       {:dir "/tmp/annos" :session {:productions []} :replace? true}))))
  (testing "the keys it does name are still checked"
    (is (some? (s/check schema/ArtifactOpts {:dir (java.io.File. "/tmp/annos")})))
    (is (some? (s/check schema/ArtifactOpts {:repo 'a-symbol})))))

(deftest manifest-requires-a-repo-test
  (testing "`:repo` is optional on ArtifactOpts — a run with an explicit :dir has
            no repo to name — but required here. A manifest silently describing
            the wrong repo, or none, is worse than one that refuses to be written"
    (is (some? (s/check schema/ManifestOptions
                        {:dir "/tmp/annos" :generated-by "t"})))
    (is (nil? (s/check schema/ManifestOptions
                       {:dir "/tmp/annos" :repo "some-ruleset" :generated-by "t"})))))

(deftest provenance-is-never-defaulted-test
  (testing "`:generated-by` is a claim about who wrote an artifact that outlives
            the process, so the schema refuses to let a caller leave it unsaid"
    (is (some? (s/check schema/ProvenanceOpts {})))
    (is (nil? (s/check schema/ProvenanceOpts {:generated-by "some-tool"})))))
