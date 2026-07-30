(ns clara.server.graph.main-test
  (:require [clara.server.graph.main :as main]
            [clara.server.graph.server :as server]
            [clojure.tools.cli :as cli]
            [clojure.test :refer [deftest is testing]]))

(defn dummy-load-session [session-path facts-path]
  {:dummy-session true :session-path session-path :facts-path facts-path})

(deftest test-validate-server-options
  (testing "validate-server-options with default fressian deserializer"
    (let [temp-session (doto (java.io.File/createTempFile "session" ".bin")
                         .deleteOnExit)
          temp-facts (doto (java.io.File/createTempFile "session" ".bin.facts")
                       .deleteOnExit)
          session-path (.getAbsolutePath temp-session)
          facts-path (.getAbsolutePath temp-facts)]

      ;; 1. Missing session option
      (is (contains? (@#'clara.server.graph.main/validate-server-options {}) :error))

      ;; 2. Missing session file
      (is (contains? (@#'clara.server.graph.main/validate-server-options {:session "nonexistent-session.bin"}) :error))

      ;; 3. Session file exists, but facts file missing
      (is (contains? (@#'clara.server.graph.main/validate-server-options {:session session-path}) :error))

      ;; 4. Both exist
      (let [res (@#'clara.server.graph.main/validate-server-options {:session session-path :facts facts-path})]
        (is (= facts-path (:facts-path res))))))

  (testing "validate-server-options with custom load-session-state-fn"
    (let [temp-session (doto (java.io.File/createTempFile "session-custom" ".bin")
                         .deleteOnExit)
          session-path (.getAbsolutePath temp-session)
          ;; Even if facts file does not exist, validation succeeds because of custom load fn
          res (@#'clara.server.graph.main/validate-server-options
               {:session session-path
                :load-session-state-fn 'clojure.core/identity})]
      (is (not (contains? res :error)))
      ;; facts-path is resolved anyway
      (is (= (str session-path ".facts") (:facts-path res))))))

(deftest test-load-session-state
  (testing "load-session-state delegates to custom function when provided as a symbol"
    (let [res (main/load-session-state "mock-session.bin" "mock-facts.bin" 'clara.server.graph.main-test/dummy-load-session)]
      (is (= {:dummy-session true :session-path "mock-session.bin" :facts-path "mock-facts.bin"} res))))

  (testing "load-session-state accepts actual function"
    (let [res (main/load-session-state "mock-session.bin" "mock-facts.bin" dummy-load-session)]
      (is (= {:dummy-session true :session-path "mock-session.bin" :facts-path "mock-facts.bin"} res))))

  (testing "load-session-state fails for unresolvable symbols"
    (is (thrown? IllegalArgumentException
                 (main/load-session-state "mock-session.bin" "mock-facts.bin" 'nonexistent.ns/nonexistent-fn)))))

(deftest test-run-explorer-server-custom-loader
  (let [start-called? (atom false)
        start-opts (atom nil)
        temp-session (doto (java.io.File/createTempFile "session-run" ".bin")
                       .deleteOnExit)
        session-path (.getAbsolutePath temp-session)]
    (with-redefs [server/start! (fn [opts]
                                  (reset! start-called? true)
                                  (reset! start-opts opts))]
      (main/run-explorer-server
       {:session session-path
        :port 8888
        :load-session-state-fn 'clara.server.graph.main-test/dummy-load-session}
       "some-facts.bin")
      (is @start-called?)
      (is (= 8888 (:port @start-opts)))
      (is (= {:dummy-session true :session-path session-path :facts-path "some-facts.bin"}
             (:session @start-opts))))))

(deftest test-cli-options-validation
  (testing "cli-options parsing validations"
    (let [parse-fn (fn [& args] (cli/parse-opts args main/cli-options))]
      ;; Valid port
      (is (nil? (:errors (parse-fn "-s" "s.bin" "-p" "8080"))))
      ;; Port too high
      (is (some? (:errors (parse-fn "-s" "s.bin" "-p" "70000"))))
      ;; Port negative
      (is (some? (:errors (parse-fn "-s" "s.bin" "-p" "-1"))))
      ;; Port non-numeric
      (is (some? (:errors (parse-fn "-s" "s.bin" "-p" "abc")))))))

(deftest test-main-generate-analysis
  (testing "-main with --generate-analysis routes to run-generate-analysis"
    (let [gen-called? (atom false)
          gen-opts (atom nil)]
      (with-redefs [clara.server.graph.main/run-generate-analysis (fn [options]
                                                                    (reset! gen-called? true)
                                                                    (reset! gen-opts options))]
        (main/-main "--generate-analysis" "out" "-s" "session.bin")
        (is @gen-called?)
        (is (= "out" (:generate-analysis-dir @gen-opts)))
        (is (= "session.bin" (:session @gen-opts)))))))

(deftest test-main-generate-annotations-flag-removed
  (testing "the removed -g/--generate-annotations flag is rejected as unknown"
    (let [{:keys [errors]} (cli/parse-opts ["-g" "src/my_rules.clj"] main/cli-options)]
      (is (seq errors)))))

(deftest test-main-custom-loader-missing
  (testing "-main with unresolvable custom loader exits with 1"
    (let [exit-code (atom nil)
          temp-session (doto (java.io.File/createTempFile "session-missing" ".bin")
                         .deleteOnExit)
          session-path (.getAbsolutePath temp-session)]
      (with-redefs [clara.server.graph.main/exit (fn [code] (reset! exit-code code))]
        (main/-main "-s" session-path "--load-session-state-fn" "nonexistent.ns/nonexistent-fn")
        (is (= 1 @exit-code))))))
