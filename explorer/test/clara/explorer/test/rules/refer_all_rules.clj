(ns clara.explorer.test.rules.refer-all-rules
  "Fixture for the `:refer :all` callee-misattribution defect.

   Two namespaces are referred wholesale: `clara.rules` (provides `defrule`,
   `insert!`) and `clara.explorer.test.rules.helpers` (provides `->fact`).
   clj-kondo attributes every name used from either to the first listed —
   `clara.rules` — so `->fact` is reported as the non-existent
   `clara.rules/->fact` unless corrected."
  ;; The fixture exists to exercise `:refer :all`; disable the project-wide
  ;; prefer-alias/refer lint for this namespace.
  {:clj-kondo/config '{:linters {:refer-all {:level :off}}}}
  (:require [clara.rules :refer :all]
            [clara.explorer.test.rules.helpers :refer :all]))

(defrule refer-all-ctor-rule
  [?x <- :refer-all/target]
  =>
  (insert! (->fact :refer-all/out {:x ?x})))
