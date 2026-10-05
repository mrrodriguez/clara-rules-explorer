(ns clara.explorer.test.rules.split-hierarchy-a
  "Split-hierarchy fixture, consumer half: matches `:split-hierarchy/c` but
  declares no hierarchy itself. The test gives its session a custom hierarchy
  where `:split-hierarchy/b` derives from `:split-hierarchy/c`, so analysis
  must record the declared-but-unused `:split-hierarchy/b` edge."
  (:require [clara.rules :as r]))

(r/defrule consume-c
  [?x <- :split-hierarchy/c]
  =>
  (println (str "consumed " ?x)))
