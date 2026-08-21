1. production summary view when on "fact types" tab and in the "LHS types (input)" expanding a LHS
   condition to "show upstream/downstream" then clicking "show type matches" popover with a fact
   type hierarchy "satisfies" chain, has broken layout where the navigation icon button gets wrapped
   over the text instead of having room to the right where it is supposed to stay per line.
2. production summary In "fact types" tab in the "LHS types (input)" the expand tooltip is "show
   upstream/downstream" while the collapse tooltip is "collapse dependencies". The expand should
   just mirror the collapse as "expand dependencies".
3. DONE improve elisp tests. do we really have to mock clojure-mode to the extent of a syntax table etc?
4. DONE elisp not handling defrule/defquery metadata on var name like `(defrule ^:my-meta my-rule-name =>
:foo)`
