(ns clara.explorer.artifacts.status-report-test
  " `clara.explorer.artifacts.status-report` over hand-built report maps: reason
  fragments, source rows, field order, verdict counts, notes, and checkout parsing."
  (:require
   [clara.explorer.artifacts.status-report :as report]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest reason-summary-test
  (testing "each library check renders"
    (is (= "sha-drift aaa1111 -> bbb2222"
           (report/reason-summary {:check :sha-drift :recorded "aaa1111aaa" :current "bbb2222bbb"})))
    (is (= "checkout on feature-x, unit recorded main"
           (report/reason-summary {:check :checkout-off-ref :recorded "main" :current "feature-x"})))
    (is (= "sha not compared" (report/reason-summary {:check :sha-not-compared})))
    (is (= "aggregate without per-source shas (no verdict)"
           (report/reason-summary {:check :aggregate-no-sources}))))
  (testing "a host method is used, nested under :unit-stale too"
    (defmethod report/reason-summary :config-slice
      [{:keys [was now]}]
      (format "config-slice %s -> %s" was now))
    (is (= "config-slice a -> b"
           (report/reason-summary {:check :config-slice :was "a" :now "b"})))
    (is (= "repo-a: unit config-slice a -> b; sha-drift aaa1111 -> bbb2222"
           (report/reason-summary {:check :unit-stale :unit-key "repo-a"
                                   :reasons [{:check :config-slice :was "a" :now "b"}
                                             {:check :sha-drift
                                              :recorded "aaa1111" :current "bbb2222"}]}))))
  (testing ":default prints the reason as data"
    (is (= "{:check :future-check}" (report/reason-summary {:check :future-check}))))
  (testing ":unit-stale leaves out the informational ones"
    (is (= "repo-a: unit sha-drift aaa1111 -> bbb2222"
           (report/reason-summary {:check :unit-stale :unit-key "repo-a"
                                   :reasons [{:check :sha-drift
                                              :recorded "aaa1111" :current "bbb2222"}
                                             {:check :checkout-dirty
                                              :checkout "/co"
                                              :informational true}]})))))

(deftest source-rows-test
  (let [report {:verdict :stale
                :reasons [{:check :unit-stale :unit-key "a"
                           :reasons [{:check :sha-drift :recorded "111" :current "999"}]}
                          {:check :source-missing :unit-key "b"}
                          {:check :unit-unknown :unit-key "c"
                           :reasons [{:check :source-cycle :unit-key "c"}]}
                          {:check :unit-unknown :unit-key "d"
                           :reasons [{:check :unit-report-missing :unit-key "d"}]}]
                :sources [{:unit-key "a"
                           :composition {:verdict :current}
                           :unit-status {:verdict :stale
                                         :reasons [{:check :sha-drift
                                                    :recorded "111" :current "999"}]}}
                          {:unit-key "b"
                           :composition {:verdict :stale}}
                          {:unit-key "c"
                           :composition {:verdict :current}}
                          {:unit-key "d"
                           :composition {:verdict :current}}]}]
    (is (= [["a" "composition current" "unit stale (sha-drift 111 -> 999)"]
            ["b" "composition stale" "unit missing"]
            ["c" "composition current" "unit not checked (composition cycle)"]
            ["d" "composition current" "unit report missing"]]
           (report/->source-rows report)))))

(deftest report-fields-test
  (testing "order, verdict counts, and notes"
    (let [fields (report/->report-fields
                  {:dir "/reg/pair" :repo "_compose/pair" :kind :aggregate :mode :compose
                   :source {:sha "d4e5f6a" :branch "main" :working-tree "clean"}
                   :updated "2026-10-30"
                   :staleness {:policy "review-when-any-source-sha-drifts"}
                   :verdict :stale
                   :reasons [{:check :unit-stale :unit-key "repo-a"
                              :reasons [{:check :sha-drift :recorded "aaa" :current "bbb"}]}
                             {:check :unit-stale :unit-key "repo-b"
                              :reasons [{:check :sha-drift :recorded "ccc" :current "ddd"}]}
                             {:check :source-sha-drift :unit-key "repo-c"
                              :recorded "eee" :current "fff"}]
                   :sources [{:unit-key "repo-a" :composition {:verdict :current}
                              :unit-status {:verdict :stale
                                            :reasons [{:check :sha-drift
                                                       :recorded "aaa" :current "bbb"}]}}
                             {:unit-key "repo-b" :composition {:verdict :current}
                              :unit-status {:verdict :stale
                                            :reasons [{:check :sha-drift
                                                       :recorded "ccc" :current "ddd"}]}}
                             {:unit-key "repo-c" :composition {:verdict :stale}}]})]
      (is (= ["unit" "kind" "source" "policy" "sources" "" "" "verdict"]
             (mapv first fields)))
      (is (= "stale | unit-stale 2, source-sha-drift 1"
             (second (last fields))))
      (is (not (some #(= "notes" (first %)) fields)) "no informational, no notes")))
  (testing "compared and notes"
    (let [fields (report/->report-fields
                  {:dir "/co" :repo "aaa" :kind :source
                   :source {:sha "aaa1111" :branch "main" :working-tree "clean"}
                   :updated "2026-10-30"
                   :staleness {:policy "review-when-sha-drifts"}
                   :compared {:checkout "/co" :ref "HEAD" :sha "aaa1111"}
                   :verdict :current
                   :reasons [{:check :checkout-dirty :checkout "/co" :informational true}]})]
      (is (= ["unit" "kind" "source" "compared" "policy" "verdict" "notes"]
             (mapv first fields)))
      (is (= "current" (second (nth fields 5))))
      (is (str/includes? (second (last fields)) "checkout-dirty"))))
  (testing "insert-fields-after splices host fields in"
    (let [fields [["unit" "a"] ["kind" "b"] ["verdict" "c"]]
          out (report/insert-fields-after fields "kind" [["coords" "x"]])]
      (is (= [["unit" "a"] ["kind" "b"] ["coords" "x"] ["verdict" "c"]] out)))))

(defmethod report/reason-summary ::note
  [{:keys [text]}]
  text)

(deftest notes-test
  (let [note (fn [text] {:check ::note :text text :informational true})
        fields (report/->report-fields
                {:dir "/reg/pair" :repo "_compose/pair" :kind :aggregate :mode :compose
                 :source {:sha "d4e5f6a" :branch "main" :working-tree "clean"}
                 :updated "2026-10-30"
                 :staleness {:policy "review-when-any-source-sha-drifts"}
                 :verdict :stale
                 :reasons [(note "own")
                           {:check :unit-stale :unit-key "a"
                            :reasons [{:check :sha-drift :recorded "111" :current "999"}
                                      (note "shared")]}]
                 :sources [{:unit-key "a" :composition {:verdict :current}
                            :unit-status {:verdict :stale
                                          :reasons [{:check :sha-drift
                                                     :recorded "111" :current "999"}
                                                    (note "shared")]}}
                           {:unit-key "b" :composition {:verdict :current}
                            :unit-status {:verdict :current
                                          :reasons [(note "shared") (note "only b")]}}]})]
    (is (= "own; shared (2 sources); b: only b"
           (second (last fields)))
        "a source's note names it, a shared one is counted, and a stale source's notes are
        read once, not again from the :unit-stale reason that nests them")))

(deftest print-fields-test
  (let [out (with-out-str
              (report/print-fields! [["unit" "x"]
                                     ["sources" ["a-long-key" "composition current" "unit  two spaces"]]
                                     ["" ["b" "composition stale" "unit missing"]]
                                     ["" "a host field"]
                                     ["verdict" "stale"]]))]
    (is (= ["unit    x"
            "sources a-long-key  composition current  unit  two spaces"
            "        b           composition stale    unit missing"
            "        a host field"
            "verdict stale"]
           (str/split-lines out))
        "cells align by column whatever spaces they hold; a plain value is printed as is")))

(deftest parse-source-checkouts-test
  (is (= {"a" "/x" "b" "/y"} (report/parse-source-checkouts ["a=/x" "b=/y"])))
  (doseq [values [["oops"] ["=./checkout"] ["a="] ["a=/one" "a=/two"]]]
    (try
      (report/parse-source-checkouts values)
      (is false (str "should throw for " (pr-str values)))
      (catch clojure.lang.ExceptionInfo e
        (is (true? (:clara.explorer.artifacts.status/invalid-args (ex-data e)))
            (pr-str values))))))
