1. IN PROGRESS Convert primary analyze path to use objects from an in-memory clara session.

Currently, the clara.server.tools.graph.analyze rulebase analysis functions are fundamentally based
on pure static analysis of the namespaces connected to a rulebase. clj-kondo hooks for analyze-call
are depended upon for accurate clara-rules defrule macro analysis to understand what part is the LHS
vs the RHS where the LHS is ignored since the analysis concerns detecting insert/retract types done
on the more ad hoc, unstructured RHS of rules. The LHS analysis is already statically introspectible
due to the constrained DSL.

This was originally done due to a desire to possibly point the analyzer as a Java main function that
could just take file paths and used clj-kondo static analysis on those files to attempt to annotate
rules that were missing annotations already. This is too limiting and not even the intended use case.
To get more accurate and automated annotation results, we actually want to have full access to the
runtime classpath associated with a clara-rules session object. On this session, we can determine
the rule constructs themself - as data structure, independent of macro expansion. Custom macros can
be written that emit rule constructs, and we do not want to have to have clj-kondo hooks added for
every one of those types of cases. A setup demonstrated this in: clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta
 using the rule emiting macro clara.server.tools.graph.rules.helpers/def-fact-fn

@/Users/mrrodriguez/Projects/clara-rules-explorer/server/docs/analyze-clj-kondo-notes.md explains
our clj-kondo interaction in place more thoroughly. Notable, if we give clj-kondo a snippet of
source code and a ns-name to resolve against, we should be able to parse it in pieces. This is
exactly what we'd want given a clara-rules in memor rules structure as you'd see from (clara.server.graph.smoke-test/run-rules), eg:
```
(->> (run-rules) (clara.rules.engine/components) :rulebase :productions (take 3))

({:ns-name clara.server.tools.graph.rules.loan-doc-rules,
  :lhs [],
  :rhs
  (do
   (clojure.core/let
    [resolved__49581__auto__ #'extract-doc-meta]
    (clara.rules/insert! resolved__49581__auto__))),
  :name "clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule",
  :handler clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule}
 {:ns-name clara.server.tools.graph.rules.loan-doc-rules,
  :lhs
  [{:type clara.server.tools.graph.rules.loan_app_facts.Application,
    :constraints [(= ?app-id app-id)]}
   {:accumulator (clara.rules.accumulators/all),
    :from
    {:type clara.server.tools.graph.rules.loan_app_facts.GivenDocument,
     :constraints [(= ?app-id app-id)]},
    :result-binding :?docs}
   {:type :extract-doc-meta, :constraints [], :fact-binding :?extract-doc-meta}],
  :rhs
  (do
   (let
    [doc-metas (mapv ?extract-doc-meta ?docs)]
    (r/insert! (laf/map->AllGivenDocumentsMeta {:app-id ?app-id, :doc-metas doc-metas})))),
  :name "clara.server.tools.graph.rules.loan-doc-rules/collect-doc-meta",
  :handler clara.server.tools.graph.rules.loan-doc-rules/collect-doc-meta}
 {:ns-name clara.server.tools.graph.rules.loan-doc-rules,
  :lhs
  [{:type clara.server.tools.graph.rules.loan_app_facts.Application,
    :constraints [(= ?app-id app-id)]}
   {:accumulator (clara.rules.accumulators/all),
    :from
    {:type clara.server.tools.graph.rules.loan_app_facts.GivenDocument,
     :constraints [(= ?app-id app-id) (= doc-type :id-card)]},
    :result-binding :?docs}],
  :rhs (do (r/insert! (map->AllIdCardGivenDocuments {:app-id ?app-id, :docs ?docs}))),
  :name "clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs",
  :handler clara.server.tools.graph.rules.loan-doc-rules/collect-app-id-card-given-docs})

```

With this change, our analysis in @server/test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn should be
able to see the rule details coming from what was generated in clara.server.tools.graph.rules.helpers/def-fact-fn

From our docs @server/docs/rule-annotations.md I want the implementation to be focused in on the paths
"#### 2. Generate annotations from a live session" and "#### 3. Generate full static analysis from a live session"

We do not need to support a session-less analysis from files or namespaces directly. Later, we may
support a main method for this, but it would require the classpath to already be suitable to load a
session from a caller supplied function that is presumed to exist on the claspath that returns the
clara session to be analyzed.

We will also be looking to extend the API to handle the caller giving a callsite resolver function
that can be passed callsites that cannot be automatically introspected. We can call with the
callsite details and the ns-name it was found in and perhaps other useful surrounding details and
this function can return the resolution and the types if any are found.

Let's phase this work appropriately. You're goal right now is to only explore what is needed to do
this work and created a detailed plan of action that can be taken in steps.

TAke into account what tests need to be updated, which docs will need updated, and which namespaces
are involved. Do not guess at clj-kondo impl details or clara-rules impl details. We have the source
code for these libraries directly available to explore.
* /Users/mrrodriguez/Projects/gateless/clara-rules
* /Users/mrrodriguez/Projects/clj-kondo

Make use of a running clara-rules-explorer server clj-nrepl-eval repl instance that is available to
be sure to understand the API and the data structures involved. We have @server/README.md that may
help and also any docs/* directory details relevant. 

Special note: I am working on another orthogonal feature in parallel right now that involves a few functions:
* clara.server.graph.api/enriched-annotations
* clara.server.tools.graph.analyze/enrich-annotations-from-session
* clara.server.tools.graph.analyze/add-auto-detected-annotations

I want you to not focus on them in your implementation. They are in flux and subject to change. They
are only concerned with deriving new annotations from working memor fact instance information (when
available). This is unrelated to the goals scope of the work I've presented for you to plan here.


2. IN PROGRESS Setup an example of a special "functions as facts" to show how that can be discovered and tracked.

3. IN PROGRESS Consider an extension to analyze that can take a given callsite and call a helper function to
   resolve the type. Maybe use kondo for this too.

4. clara.server.tools.graph.rules.loan-doc-rules/dynamic-insert-audit-trail does not show a callsite when facts are in memory - does this wipe out callsites? make a smoke test with no facts in memory to double check.
