(ns clara.explorer.test.rules.split-hierarchy-b
  "Split-hierarchy fixture, producer half: inserts `:split-hierarchy/a` on a
  `String` trigger. The test gives its session a custom hierarchy where
  `:split-hierarchy/a` derives from `:split-hierarchy/b`, which the consumer
  half's hierarchy continues to `:split-hierarchy/c`."
  (:require [clara.rules :as r]))

(r/defrule produce-a
  {:clara-rules/insert-types [:split-hierarchy/a]}
  [String]
  =>
  (r/insert! (with-meta {:x 1} {:type :split-hierarchy/a})))
