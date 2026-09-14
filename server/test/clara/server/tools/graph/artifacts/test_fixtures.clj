(ns clara.server.tools.graph.artifacts.test-fixtures
  "The temp-dir fixture and the stand-in generated layer that the layered
  persistence tests share.

  Everything here is data + file handling over a throwaway directory, so no test
  built on it needs a ruleset on disk. The generation side that does is
  exercised by the REPL walkthroughs in `dev/flows/`."
  (:require
   [clojure.java.io :as io]
   [clara.server.tools.graph.artifacts.overlay :as overlay]
   [clara.server.tools.graph.artifacts.store :as store]))

(set! *warn-on-reflection* true)

(def generated-by
  "The `:generated-by` every layer these tests write claims. Required rather
  than defaulted — see `clara.server.tools.graph.artifacts.schema/ProvenanceOpts`."
  "artifacts-test")

(def ^:dynamic *artifact-opts*
  "Persistence opts for the running test's own temp dir, bound by
  `temp-artifact-dir-fixture`."
  nil)

(defn- create-temp-artifact-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clara-artifacts-test"
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn temp-artifact-dir-fixture
  "`:each` fixture: run `f` with `*artifact-opts*` pointing at a fresh temp dir,
  deleted afterwards."
  [f]
  (let [dir (create-temp-artifact-dir)]
    (binding [*artifact-opts* {:dir dir :generated-by generated-by}]
      (try (f)
           (finally (delete-tree dir))))))

(def generated-annotations
  "A stand-in generated-layer payload with one of each shape the seeder has to
  discriminate. Callsites carry the discovery fields a real analyzer emits
  (`:ns-name-sym` + `:source-str` are the id basis; `:filename` /
  `:constructor-sym` are what a sparse overlay must not have to restate)."
  {"a.ns/full-rule"
   #:clara-rules{:insert-types [:a/one]
                 :dynamic-insert-types-detected
                 {:callsites [{:source-str "(->fact :a/one x)"
                               :ns-name-sym 'a.ns
                               :filename "a_ns.clj"
                               :constructor-sym 'acme.facts/->fact
                               :status :full
                               :resolved-types [:a/one]}]}}

   "a.ns/gap-rule"
   #:clara-rules{:dynamic-insert-types-detected
                 {:callsites [{:source-str "(->fact (type-for ?x) {:k v})"
                               :ns-name-sym 'a.ns
                               :filename "a_ns.clj"
                               :constructor-sym 'acme.facts/->fact
                               :status :none}]}}

   "a.ns/partial-rule"
   #:clara-rules{:insert-types [:a/three]
                 :dynamic-insert-types-detected
                 {:callsites [{:source-str "(->fact :a/three x)"
                               :ns-name-sym 'a.ns
                               :filename "a_ns.clj"
                               :status :full
                               :resolved-types [:a/three]}
                              {:source-str "(dissoc m :input)"
                               :ns-name-sym 'a.ns
                               :filename "a_ns.clj"
                               :status :none}]}}

   "a.ns/side-effect-rule"
   #:clara-rules{:no-output-types true}})

(def stub-rulebase
  "Stands in for a compiled session. `props-layer` takes a session *or* a
  rulebase, and a rulebase is just a map with `:productions` — so this is enough
  to exercise the props layer folding without building a real Clara session.
  Productions carry no `:props`, so the layer folds in empty: present in
  `:layers`, contributing nothing."
  {:productions [{:name "a.ns/full-rule"}
                 {:name "a.ns/gap-rule"}
                 {:name "a.ns/partial-rule"}
                 {:name "a.ns/side-effect-rule"}]})

(defn write-generated-layer!
  "Persist `generated-annotations` as the `:auto` layer — the starting state
  almost every persistence test needs."
  []
  (store/write-layer! :auto *artifact-opts*
                      (store/->generated-layer *artifact-opts* generated-annotations)))

(defn read-generated-layer []
  (store/read-layer :auto *artifact-opts*))

(defn discovered-callsite-id
  "The id the merge assigned to the `n`th callsite of `rule`'s insert dimension —
  the handle a curator writes against."
  [rule n]
  (-> (read-generated-layer)
      :annotations
      (get rule)
      :clara-rules/dynamic-insert-types-detected
      :callsites
      (nth n)
      :callsite-id))

(defn record-resolution!
  "What a curation pass records: one settled callsite, by id."
  [rule n types]
  (overlay/record-resolutions! [{:callsite-id (discovered-callsite-id rule n)
                                 :resolved-types types
                                 :note "read the source"}]
                               *artifact-opts*))

(defn merged-insert-types [rule]
  (-> (store/->merged-annotations *artifact-opts*)
      :annotations (get rule) :clara-rules/insert-types set))

(defn merged-insert-callsites [rule]
  (-> (store/->merged-annotations *artifact-opts*)
      :annotations (get rule)
      :clara-rules/dynamic-insert-types-detected :callsites))
