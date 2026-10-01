(ns clara.explorer.artifacts.layout-test
  "The unit → directory mapping (and its inverse) that the JVM store and the
  babashka `status` report share, plus the variant segment encoding both read."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(deftest segment-encoding-round-trips-test
  (testing "each reserved character round-trips"
    (doseq [[c encoded] {"%" "%25" "/" "%2F" "@" "%40" "+" "%2B"}]
      (is (= c (layout/decode-value (layout/encode-value c))))
      (is (= encoded (layout/encode-value c))))
    (is (= "feature%2Fnew-tax" (layout/encode-value "feature/new-tax")))
    (is (= "feature/new-tax" (layout/decode-value "feature%2Fnew-tax"))))
  (testing "a value containing '=' survives the segment split"
    (is (= "a=b" (layout/decode-value (layout/encode-value "a=b")))))
  (testing "a literal %2F is not confused with an encoded slash"
    (is (= "%2F" (layout/decode-value (layout/encode-value "%2F"))))))

(deftest variant-path-round-trips-test
  (testing "variant-path encodes each pair as <axis>=<encoded value>"
    (is (= "region=eu/tier=gold/ref=feature%2Fnew-tax"
           (layout/variant-path [[:region "eu"] [:tier "gold"] [:ref "feature/new-tax"]]))))
  (testing "path->variant is its inverse, splitting on the first '='"
    (is (= [[:region "eu"] [:tier "gold"] [:ref "feature/new-tax"]]
           (layout/path->variant "region=eu/tier=gold/ref=feature%2Fnew-tax")))
    (is (= [[:axis "a=b"]] (layout/path->variant "axis=a=b"))))
  (testing "a segment without '=' is refused"
    (is (thrown? clojure.lang.ExceptionInfo (layout/path->variant "nope")))))

(deftest unit-dir-test
  (testing "explicit :dir wins outright; a variant nests under _variants/<repo>/"
    (is (= "/r/loan-app"
           (layout/unit-dir {:root "/r" :repo "loan-app"})))
    (is (= "/r/_variants/loan-app/ref=main"
           (layout/unit-dir {:root "/r" :repo "loan-app" :variant [[:ref "main"]]})))
    (is (= "/r/_variants/loan-app/region=eu/tier=gold/ref=feature%2Fnew-tax"
           (layout/unit-dir {:root "/r" :repo "loan-app"
                             :variant [[:region "eu"] [:tier "gold"] [:ref "feature/new-tax"]]})))
    (is (= "/x/y"
           (layout/unit-dir {:dir "/x/y" :root "/r" :repo "loan-app"})))
    (is (= "/x/y"
           (layout/unit-dir {:dir "/x/y" :root "/r" :repo "loan-app" :variant [[:ref "f"]]})))
    (is (= "/r/_variants/loan-app/ref=feature%2Fvc-income"
           (layout/unit-dir {:root "/r/" :repo "loan-app" :variant [[:ref "feature/vc-income"]]})))))

(deftest default-root-test
  (testing "the manifest's :repo (or _variants/<repo>/<variant path>) strips off the end"
    (is (= "/r"
           (layout/default-root "/r/loan-app" {:repo "loan-app"})))
    (is (= "/r"
           (layout/default-root "/r/_variants/loan-app/ref=main"
                                {:repo "loan-app" :variant [[:ref "main"]]})))
    (is (= "/r"
           (layout/default-root "/r/_variants/loan-app/ref=main/"
                                {:repo "loan-app" :variant [[:ref "main"]]})))
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
