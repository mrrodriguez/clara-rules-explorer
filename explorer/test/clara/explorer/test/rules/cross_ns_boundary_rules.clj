(ns clara.explorer.test.rules.cross-ns-boundary-rules
  (:require [clara.rules :as r]
            [clara.explorer.test.rules.cross-ns-boundary-helpers :as h]))

(r/defrule cross-ns-boundary-rule
  [?x <- :cross-ns-boundary/target]
  =>
  (h/insert-cross-ns-fact! ?x))
