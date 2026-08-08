(ns clara.server.graph.server-test
  "Tests for server lifecycle and swap-session! annotation building.
   Organized by the build-annotations decision tree paths."
  (:require [clara.rules :as r]
            [clara.rules.engine :as eng]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.rules.loan-app-facts :as laf]
            [clara.server.tools.graph.rules.loan-app-rules]
            [clara.server.tools.graph.rules.loan-doc-rules]
            [clj-http.client :as client]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as st]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *port* 19003)

(defn- ->url [path]
  (format "http://localhost:%s/v1%s" *port* path))

(defn- ->test-session
  "A bare session (no facts inserted) over the loan-doc + loan-app rulesets."
  []
  (r/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                'clara.server.tools.graph.rules.loan-app-rules))

(defn- ->test-session-with-facts
  "A session with a single Application inserted and rules fired."
  []
  (-> (->test-session)
      (r/insert (laf/map->Application {:app-id "app-1"}))
      (r/fire-rules)))

(defn- ->test-session-with-wm-enrichment
  "A session with facts that trigger rules without explicit :props insert-types,
   so WM enrichment has new runtime-derived types to detect."
  []
  (-> (->test-session)
      (r/insert (laf/map->Application {:app-id "app-1"})
                (laf/map->GivenDocument {:app-id "app-1" :doc-type :paystub}))
      (r/fire-rules)))

(defn- ->rulebase
  "Extracts the raw rulebase from a session."
  [session]
  (-> session eng/components :rulebase))

(defn- start-server!
  "Starts a server on *port* with the given session and annotation layers.
   When layers is empty the props layer is still folded in (via {:source []})."
  ([session layers]
   (server/start! {:port *port*
                   :session session
                   :annotations {:source (vec layers)}
                   :working-memory-enabled true}))
  ([session]
   (start-server! session [])))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(def ^:private test-session
  "A bare session (no facts) for fixture setup."
  (delay (->test-session)))

(def ^:private test-system
  "The system returned by start-server! — captured so the fixture can stop it."
  (atom nil))

(defn- server-fixture
  "Start a server once, run tests, then stop."
  [f]
  (let [system (start-server! @test-session)]
    (reset! test-system system)
    (try
      (f)
      (finally
        (server/stop! system)))))

(use-fixtures :once st/validate-schemas server-fixture)

;; ---------------------------------------------------------------------------
;; HTTP helper
;; ---------------------------------------------------------------------------

(defn- parse-body [resp]
  (json/read-value (:body resp) (json/object-mapper {:decode-key-fn true})))

(defn- http-annotations
  "Returns the current annotations map via the HTTP API."
  []
  (-> (client/get (->url "/annotations") {:accept :json})
      parse-body))

;; ---------------------------------------------------------------------------
;; Known rule names for assertions
;; ---------------------------------------------------------------------------

(def ^:private rule-app-outcome
  "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?")

(def ^:private rule-collect-given-docs
  "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs")

(def ^:private rule-dynamic-compliance
  "clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-compliance-review")

;; ---------------------------------------------------------------------------
;; 1. Error cases
;; ---------------------------------------------------------------------------

(deftest test-swap-session-requires-args
  (testing "empty opts"
    (is (thrown? IllegalArgumentException (server/swap-session! {}))))
  (testing ":warm-cache? alone is not enough"
    (is (thrown? IllegalArgumentException (server/swap-session! {:warm-cache? false})))))

;; ---------------------------------------------------------------------------
;; 2. Legacy normalization — bare forms wrapped as {:source <form>}
;; ---------------------------------------------------------------------------

(deftest test-swap-session-legacy-bare-map
  (testing "bare map -> {:source map}, enrichment nil -> source as-is"
    (let [annos {"some-rule" {:clara-rules/notes "hello"}}
          result (server/swap-session! {:annotations annos})]
      (is (= annos result) "returned annotations match input")
      (is (= {:some-rule {:clara-rules/notes "hello"}} (http-annotations))
          "HTTP endpoint reflects the annotations"))))

(deftest test-swap-session-legacy-file-source
  (let [res-path "clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn"
        sidecar-rule "clara.server.tools.graph.rules.loan-doc-rules/collect-app-given-docs"]
    (testing "source as string path"
      (let [path (some-> (io/resource res-path) .getPath)
            result (server/swap-session! {:annotations {:source path}})]
        (is (map? result))
        (is (contains? result sidecar-rule))))
    (testing "source as java.io.File"
      (let [f (io/file (io/resource res-path))
            result (server/swap-session! {:annotations {:source f}})]
        (is (map? result))
        (is (contains? result sidecar-rule))))))

;; ---------------------------------------------------------------------------
;; 3. No enrichment (:none or nil) — source as-is, clear when absent
;; ---------------------------------------------------------------------------

(deftest test-swap-session-no-enrichment
  (testing "explicit :none with source returns source as-is"
    (let [annos {"r" {:notes "x"}}
          result (server/swap-session! {:annotations {:source annos :enrichment :none}})]
      (is (= annos result))))
  (testing ":none without source clears to {}"
    (let [result (server/swap-session! {:annotations {:enrichment :none}})]
      (is (= {} result))
      (is (= {} (http-annotations)))))
  (testing "nil enrichment (implicit) without source clears to {}"
    (let [result (server/swap-session! {:session (->test-session)
                                        :annotations nil})]
      (is (= {} result) "nil annotations-spec clears annotations"))))

;; ---------------------------------------------------------------------------
;; 4. :reuse — keep current, source takes priority
;; ---------------------------------------------------------------------------

(deftest test-swap-session-reuse
  (testing ":reuse with no source keeps current annotations"
    (let [current {"keep-me" {:clara-rules/notes "survives"}}
          _ (server/swap-session! {:annotations current})
          result (server/swap-session! {:annotations {:enrichment :reuse}})]
      (is (= current result))))
  (testing ":reuse with source — source takes priority over current"
    (let [_ (server/swap-session! {:annotations {"existing" {:notes "old"}}})
          source {"override" {:notes "wins"}}
          result (server/swap-session! {:annotations {:source source :enrichment :reuse}})]
      (is (= source result) "source alone, no merge with existing"))))

;; ---------------------------------------------------------------------------
;; 5. :auto-detect-from-rulebase — props + static analysis
;; ---------------------------------------------------------------------------

(deftest test-swap-session-auto-detect-from-rulebase
  (testing "with working memory — props + static analysis run"
    (let [sess (->test-session-with-facts)
          result (server/swap-session! {:session sess
                                        :annotations {:enrichment :auto-detect-from-rulebase}})]
      (is (map? result))
      (is (seq result) "non-empty annotations")
      ;; Props layer: rules with :clara-rules/insert-types in their :props
      (is (contains? result rule-app-outcome)
          "rule with explicit :props insert-types present")
      ;; Static analysis: detects record-constructor inserts from RHS
      (is (contains? result rule-collect-given-docs)
          "static analysis discovered rule without explicit :props insert-types")
      ;; Static analysis captures dynamic/unresolved callsites
      (is (contains? result rule-dynamic-compliance)
          "dynamic insertion rule captured by static analysis")))

  (testing "with source overlay — source annotations merge with static analysis"
    (let [source {rule-app-outcome {:clara-rules/notes "custom-note"}}
          sess (->test-session-with-facts)
          result (server/swap-session! {:session sess
                                        :annotations {:source source
                                                      :enrichment :auto-detect-from-rulebase}})]
      (is (= "custom-note" (get-in result [rule-app-outcome :clara-rules/notes]))
          "source annotation preserved")
      (is (contains? result rule-collect-given-docs)
          "static analysis annotation also present")))

  (testing "without working memory (rulebase) — props + static analysis still run"
    (let [rulebase (->rulebase (->test-session))
          result (server/swap-session! {:session rulebase
                                        :annotations {:enrichment :auto-detect-from-rulebase}})]
      (is (map? result))
      (is (seq result) "non-empty — static analysis works without WM")
      (is (contains? result rule-app-outcome)
          "rule with :props still present")
      (is (contains? result rule-collect-given-docs)
          "static analysis discovers rules even without WM"))))

;; ---------------------------------------------------------------------------
;; 6. :auto-detect-from-memory — props + WM enrichment
;; ---------------------------------------------------------------------------

(deftest test-swap-session-auto-detect-from-memory
  (testing "with working memory — WM enrichment populates insert-types"
    ;; Use a session where a rule without explicit :props insert-types fires,
    ;; so WM enrichment has truly-new runtime-derived types to detect.
    (let [sess (->test-session-with-wm-enrichment)
          result (server/swap-session! {:session sess
                                        :annotations {:enrichment :auto-detect-from-memory}})]
      (is (map? result))
      (is (seq result))
      (is (contains? result rule-app-outcome)
          "rule from rulebase present")
      ;; collect-app-given-docs has no :clara-rules/insert-types in :props.
      ;; At runtime it inserted AllGivenDocuments — WM enrichment detects this.
      (is (some? (get-in result [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
          "WM enrichment added dynamic-insert-types-detected for rule without :props insert-types")
      (is (seq (get-in result [rule-collect-given-docs :clara-rules/insert-types]))
          "WM enrichment merged truly-new types into insert-types")))

  (testing "without working memory (rulebase) — warns, returns props only"
    (let [rulebase (->rulebase (->test-session))
          result (server/swap-session! {:session rulebase
                                        :annotations {:enrichment :auto-detect-from-memory}})]
      (is (map? result))
      ;; Only props layer contributes; no static analysis, no WM enrichment
      (is (contains? result rule-app-outcome)
          "rule with :props present")
      ;; A rule without explicit :props should NOT appear since nothing ran
      ;; to discover it (no static analysis, no WM enrichment)
      (is (not (contains? result rule-dynamic-compliance))
          "dynamic rule not discovered — no static analysis or WM enrichment"))))

;; ---------------------------------------------------------------------------
;; 7. :auto-detect — props + static analysis + WM enrichment
;; ---------------------------------------------------------------------------

(deftest test-swap-session-auto-detect
  (testing "with working memory — all three layers: props + static + WM"
    ;; Use the WM-enrichment session so WM has new types to detect beyond static analysis.
    (let [sess (->test-session-with-wm-enrichment)
          result (server/swap-session! {:session sess
                                        :annotations {:enrichment :auto-detect}})]
      (is (map? result))
      (is (seq result))
      ;; Props layer
      (is (contains? result rule-app-outcome))
      ;; Static analysis layer
      (is (contains? result rule-collect-given-docs))
      (is (contains? result rule-dynamic-compliance))
      ;; WM enrichment layer — dynamic-insert-types for rules without :props types
      (is (some? (get-in result [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
          "WM enrichment added dynamic-insert-types-detected")
      (is (seq (get-in result [rule-collect-given-docs :clara-rules/insert-types]))
          "WM enrichment merged runtime types into insert-types")))

  (testing "with source overlay — all three layers plus source"
    (let [source {rule-app-outcome {:clara-rules/notes "custom"}}
          sess (->test-session-with-facts)
          result (server/swap-session! {:session sess
                                        :annotations {:source source
                                                      :enrichment :auto-detect}})]
      (is (= "custom" (get-in result [rule-app-outcome :clara-rules/notes]))
          "source annotation preserved in merged result")
      (is (contains? result rule-collect-given-docs)
          "static analysis present alongside source")))

  (testing "without working memory (rulebase) — static analysis only, no WM layer"
    (let [rulebase (->rulebase (->test-session))
          result (server/swap-session! {:session rulebase
                                        :annotations {:enrichment :auto-detect}})]
      (is (map? result))
      (is (seq result))
      (is (contains? result rule-app-outcome))
      (is (contains? result rule-collect-given-docs)
          "static analysis ran even without WM")
      ;; No WM enrichment — dynamic-insert-types should NOT be present
      ;; for rules that ONLY get WM detection (not static)
      )))

;; ---------------------------------------------------------------------------
;; 8. Session-only swap — annotations cleared
;; ---------------------------------------------------------------------------

(deftest test-swap-session-session-clears-annotations
  (testing ":session only clears annotations to {}"
    (let [session-b (->test-session-with-facts)
          ;; Set some annotations first
          _ (server/swap-session! {:annotations {"old-rule" {:notes "x"}}})
          result (server/swap-session! {:session session-b :warm-cache? true})]
      (is (= {} result) "annotations cleared")
      (is (= {} (http-annotations)) "HTTP endpoint returns empty annotations"))
    ;; Session was swapped — verify WM is now available
    (let [summary (-> (client/get (->url "/rulebase-summary") {:accept :json})
                      :body
                      (json/read-value (json/object-mapper {:decode-key-fn true})))]
      (is (true? (:working-memory-available summary))))))

;; ---------------------------------------------------------------------------
;; 9. Combined session + annotations swap
;; ---------------------------------------------------------------------------

(deftest test-swap-session-combined
  (testing ":session + :annotations together — session swapped, then annotations built"
    (let [sess (->test-session-with-facts)
          annos {"my-rule" {:clara-rules/notes "combined-test"}}
          result (server/swap-session! {:session sess :annotations annos})]
      (is (= annos result) "annotations match input")
      (is (= {:my-rule {:clara-rules/notes "combined-test"}} (http-annotations))
          "HTTP reflects the annotations"))
    (testing ":session + :annotations with enrichment on the new session"
      (let [sess (->test-session-with-facts)
            result (server/swap-session! {:session sess
                                          :annotations {:enrichment :auto-detect}})]
        (is (map? result))
        (is (seq result))
        (is (contains? result rule-app-outcome)
            "auto-detect ran against the new session")))))

;; ---------------------------------------------------------------------------
;; 10. Return value
;; ---------------------------------------------------------------------------

(deftest test-swap-session-returns-annotations
  (testing "swap-session! returns the new @annotations-atom value"
    (let [annos {"r1" {:clara-rules/notes "return-test"}}
          result (server/swap-session! {:annotations annos})]
      (is (= annos result) "return value matches input"))
    (testing "reflected in HTTP endpoint"
      (is (= "return-test"
             (get-in (http-annotations) [:r1 :clara-rules/notes]))))))

;; ---------------------------------------------------------------------------
;; Unknown enrichment mode throws
;; ---------------------------------------------------------------------------

(deftest test-build-annotations-unknown-enrichment
  (testing "unknown enumeration value throws (schema validation catches it first)"
    (let [sess (->test-session)]
      ;; Schema validation catches unknown enum values before the case throw.
      (is (thrown? Exception
                   (server/build-annotations sess
                                             {:enrichment :auto-dectect}
                                             nil))
          "typo in enrichment mode throws")))
  (testing "completely bogus enrichment also throws"
    (let [sess (->test-session)]
      (is (thrown? Exception
                   (server/build-annotations sess
                                             {:enrichment :some-future-mode}
                                             nil))
          "totally unknown enrichment mode throws"))))

;; ---------------------------------------------------------------------------
;; build-annotations with :auto-detect and working memory
;; ---------------------------------------------------------------------------

(deftest test-build-annotations-auto-detect-with-memory
  (testing "with :auto-detect — WM enrichment detected"
    (let [sess (->test-session-with-wm-enrichment)
          result (server/build-annotations sess
                                           {:enrichment :auto-detect}
                                           nil)]
      (is (map? result))
      (is (seq result) "non-empty annotations")
      (is (contains? result rule-collect-given-docs)
          "rule discovered by static analysis")
      ;; WM enrichment should add dynamic-insert-types for rules that
      ;; only get insertions at runtime.
      (is (some? (get-in result [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
          "WM enrichment detected runtime insert types")
      (is (seq (get-in result [rule-collect-given-docs :clara-rules/insert-types]))
          "WM enrichment merged runtime types into insert-types")))

  (testing "with :auto-detect-from-memory — WM enrichment only (no static analysis)"
    (let [sess (->test-session-with-wm-enrichment)
          result (server/build-annotations sess
                                           {:enrichment :auto-detect-from-memory}
                                           nil)]
      (is (map? result))
      ;; Props layer always present
      (is (contains? result rule-app-outcome)
          "rule with explicit :props always present")
      ;; collect-app-given-docs has no :props insert-types but fires at runtime;
      ;; WM enrichment detects the runtime insertions.
      (is (some? (get-in result [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
          "WM enrichment detected runtime-only insert types")))

  (testing "without WM (rulebase only) — :auto-detect still runs static analysis"
    (let [rulebase (->rulebase (->test-session))
          result (server/build-annotations rulebase
                                           {:enrichment :auto-detect}
                                           nil)]
      (is (map? result))
      (is (seq result) "non-empty — static analysis runs even without WM")
      (is (contains? result rule-collect-given-docs)
          "static analysis found rules"))))

;; ---------------------------------------------------------------------------
;; start-system! with :auto-detect enrichment
;; ---------------------------------------------------------------------------

(deftest test-start-auto-detect-enrichment
  (let [port 19004
        sess (->test-session-with-wm-enrichment)
        system (server/start-system!
                {:port port
                 :session sess
                 :annotations {:enrichment :auto-detect}
                 :working-memory-enabled true})]
    (try
      (testing "state atom reflects WM enrichment"
        (let [{:keys [annotations]} @(:state-atom system)]
          (is (map? annotations))
          (is (seq annotations))
          (is (contains? annotations rule-app-outcome)
              "rule with :props always present")
          (is (contains? annotations rule-collect-given-docs)
              "rule discovered by static analysis")
          (is (some? (get-in annotations [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
              "WM enrichment detected runtime insert types")
          (is (contains? annotations rule-dynamic-compliance)
              "dynamic insertion rule captured by static analysis")))

      (testing "annotations via HTTP match state atom"
        (let [url (fn [path] (format "http://localhost:%s/v1%s" port path))
              resp (client/get (url "/annotations") {:accept :json})
              http-annos (json/read-value (:body resp)
                                          (json/object-mapper {:decode-key-fn identity}))]
          (is (map? http-annos))
          (is (contains? http-annos rule-app-outcome)
              "HTTP annotations include props rules")))
      (finally
        (server/stop! system)))))

;; ---------------------------------------------------------------------------
;; Reload semantics
;; ---------------------------------------------------------------------------

(deftest test-reload-after-session-only-swap
  (let [sess (->test-session-with-facts)
        _ (server/swap-session! {:session sess
                                 :annotations {:enrichment :auto-detect}})]
    (testing "session-only swap clears annotations"
      (let [result (server/swap-session! {:session (->test-session)})]
        (is (= {} result) "session-only swap clears annotations to {}")))

    (testing "reload after session-only swap re-derives from nil spec → {}"
      (let [result (server/reload-annotations!)]
        (is (= {} result) "reload with nil spec returns {}")
        (is (= {} (http-annotations)) "HTTP reflects empty annotations")))))

(deftest test-reload-after-swap-with-spec
  (let [sess (->test-session-with-facts)
        spec {:enrichment :auto-detect}]
    (testing "swap with auto-detect enrichment"
      (let [result (server/swap-session! {:session sess
                                          :annotations spec})]
        (is (map? result))
        (is (seq result))
        (is (contains? result rule-collect-given-docs)
            "static analysis present after swap")))

    (testing "reload re-derives same enriched annotations from stored spec"
      (let [result (server/reload-annotations!)]
        (is (map? result))
        (is (seq result) "non-empty after reload")
        (is (contains? result rule-collect-given-docs)
            "static analysis still present after reload")
        ;; Session has working memory from the swap, so WM enrichment should persist.
        (is (some? (get-in result [rule-collect-given-docs :clara-rules/dynamic-insert-types-detected]))
            "WM enrichment present after reload")))))

;; ---------------------------------------------------------------------------
;; 12. :reuse reload regression — ensures the B1 fix is pinned
;; ---------------------------------------------------------------------------

(deftest test-reload-reuse-preserves-annotations
  (testing ":reuse reload keeps current annotations unchanged"
    (let [annos {"keep-me" {:clara-rules/notes "survives-reload"}}
          _ (server/swap-session! {:annotations {:enrichment :reuse}})
          _ (server/swap-session! {:annotations annos})
          _ (server/swap-session! {:annotations {:enrichment :reuse}})
          result (server/reload-annotations!)]
      (is (= annos result)
          "reload after :reuse swap preserves current annotations"))))

;; ---------------------------------------------------------------------------
;; 13. File-backed reload — edits to sidecar file are re-read on reload
;; ---------------------------------------------------------------------------

(deftest test-reload-rereads-file-source
  (let [sess (->test-session-with-facts)
        tmp-file (java.io.File/createTempFile "reload-test" ".edn")]
    (try
      ;; Write initial annotations to temp file in Layer format
      (spit tmp-file "{:id :source :annotations {\"test/rule-a\" {:clara-rules/notes \"v1\"}}}")

      (testing "swap with file-backed source"
        (let [result (server/swap-session! {:session sess
                                            :annotations
                                            {:source (.getAbsolutePath tmp-file)
                                             :enrichment :auto-detect}})]
          (is (map? result))
          (is (contains? result "test/rule-a")
              "rule from sidecar present")
          (is (= "v1" (get-in result ["test/rule-a" :clara-rules/notes]))
              "initial annotation notes match")))

      ;; Modify the file on disk
      (spit tmp-file "{:id :source :annotations {\"test/rule-a\" {:clara-rules/notes \"v2-modified\"}}}")

      (testing "reload re-reads the modified file"
        (let [result (server/reload-annotations!)]
          (is (map? result))
          (is (= "v2-modified"
                 (get-in result ["test/rule-a" :clara-rules/notes]))
              "reload picked up the modified notes from disk")))

      (finally
        (.delete tmp-file)))))
