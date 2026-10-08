(ns clara.explorer.test.rules.refer-all-decoy
  "Decoy dependency for the `:refer :all` callee-misattribution fixture.

   Listed before `clara.explorer.test.rules.helpers` in the rule namespace's
   requires so clj-kondo attributes bare names from either refer-all'd namespace
   to this one. The public var exists only to give the namespace a refer-all
   surface; it is never used.")

(def marker
  "Public var giving this namespace a `:refer :all` surface."
  :marker)
