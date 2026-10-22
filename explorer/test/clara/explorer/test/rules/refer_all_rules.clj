(ns clara.explorer.test.rules.refer-all-rules
  "Fixture for the `:refer :all` callee-misattribution defect.

   Two namespaces are referred wholesale: `refer-all-decoy` (listed first) and
   `clara.explorer.test.rules.helpers` (provides `->fact`). clj-kondo attributes
   every name used from either to the first listed, so `->fact` is reported as
   the non-existent `refer-all-decoy/->fact` unless corrected. `clara.rules` is
   aliased rather than refer-all'd so this fixture also lints cleanly in a
   cold-cache run (third-party `:refer :all` is unresolvable without a cache)."
  ;; The fixture exists to exercise `:refer :all`; disable the project-wide
  ;; prefer-alias/refer lint for this namespace.
  {:clj-kondo/config '{:linters {:refer-all {:level :off}}}}
  (:require [clara.rules :as r]
            [clara.explorer.test.rules.refer-all-decoy :refer :all]
            [clara.explorer.test.rules.helpers :refer :all]))

(r/defrule refer-all-ctor-rule
  [?x <- :refer-all/target]
  =>
  (r/insert! (->fact :refer-all/out {:x ?x})))
