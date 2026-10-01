(ns clara.explorer.artifacts.layout-test
  "The unit → directory mapping (and its inverse) that the JVM store and the
  babashka `status` report share."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(deftest unit-dir-test
  (testing "explicit :dir wins; :branch nests under branches/ either way"
    (is (= "/r/loan-app"
           (layout/unit-dir {:root "/r" :repo "loan-app"})))
    (is (= "/r/loan-app/branches/alt"
           (layout/unit-dir {:root "/r" :repo "loan-app" :branch "alt"})))
    (is (= "/x/y"
           (layout/unit-dir {:dir "/x/y" :root "/r" :repo "loan-app"})))
    (is (= "/x/y/branches/f"
           (layout/unit-dir {:dir "/x/y" :root "/r" :repo "loan-app" :branch "f"})))
    (is (= "/r/loan-app/branches/feature/vc-income"
           (layout/unit-dir {:root "/r/" :repo "loan-app" :branch "feature/vc-income"})))))

(deftest default-root-test
  (testing "the manifest's :repo (and branches/<label>) strips off the end"
    (is (= "/r"
           (layout/default-root "/r/loan-app" {:repo "loan-app"})))
    (is (= "/r"
           (layout/default-root "/r/loan-app/branches/alt"
                                {:repo "loan-app" :branch "alt"})))
    (is (= "/r"
           (layout/default-root "/r/loan-app/branches/alt/"
                                {:repo "loan-app" :branch "alt"})))
    (is (= "/r"
           (layout/default-root "/r/org/loan-app"
                                {:repo "org/loan-app"}))))

  (testing "anything else comes back (trimmed) unchanged, so lookups under it
            report missing rather than throwing"
    (is (= "/elsewhere"
           (layout/default-root "/elsewhere" {:repo "loan-app"})))
    (is (= "/loan-app"
           (layout/default-root "/loan-app" {:repo "loan-app"}))))

  (testing "a repo name that merely ends the same way does not strip"
    (is (= "/r/my-loan-app"
           (layout/default-root "/r/my-loan-app" {:repo "loan-app"})))))
