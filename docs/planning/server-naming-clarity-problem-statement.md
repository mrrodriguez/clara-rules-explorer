In this server-side clj app, one thing I'm finding particularly troubling is the top-level major entry points have inconsistent and clashing names that cause a lot of confusion.
I want you to help make a plan to remedy this. We are not concerned with backwards compatibility. This is greenfield.

This plan will go in @docs/planning/server-naming-clarity-plan.md

**Resolved by:** [`server-naming-clarity-plan.md`](./server-naming-clarity-plan.md).

1. When we refer to analysis of a clara-rules session working memory data, we call it a "snapshot".
   This is already confusing, but we have tried to clarify it in some contexts as "memory-snapshot".
   That is likely better, but still not ideal. The terminology should likely be

2. we have quite a bit of confusing naming going in a single ns - analyze - which is responsible for
   analyzing sessions to automatically generate annotations from unstructured clara-rules rule RHS
   forms or fall back to providing, hopefully some metadata about dynamic callsites being detected with
   hooks for consumer to try to help resolve.

Here are some problematic naming conventions:
a) clara.server.tools.graph.analyze/enrich-annotations-from-session - what does "enrich" mean? What
it is actually is doing is using working memory facts to detect more fact types that a rule is known
to insert that may or may not have been statically analyzed.
The CLJ idiom to "update" some arg is to have that arg come as the first arg in the sig - so the sig is backwards.

b) clara.server.tools.graph.analyze/add-auto-detected-annotations - the main problem is in the input arg,
called `session-analysis` since it is not clear what this is in contrast with something we will see
later, keep it in mind. The other problem though is it adds to the given `annotations`. The CLJ
idiom to "update" some arg is to have that arg come as the first arg in the sig - so the sig is backwards.
This also seems to have a very similar job/role as clara.server.tools.graph.analyze/enrich-annotations-from-session and it is confusing which is which and why.

c) clara.server.tools.graph.analyze/analyze-session-rules - this creates the annotations generation
helping analysis mentioned above as `session-analysis`. That may make sense here, but wait for later
points to contrast.

The analyze ns "session analysis" is likely lacking in semantics in its name and causing confusion.
A session in clara-rules is typically thought to be a rulebase + working memory. That is not what
analyze ns is doing. What it is doing is something more like a `->rule-source-analysis` potentially
in that it a "rule source" in clara-rules typically means the raw objects that are used to build
rulebases/sessions from. This is a ns about analyzing the namespaces underlying the rule sources of
a given session or rulebase starting point.

d) clara.server.tools.graph.analyze/generate-annotations-from-analysis it is not clear what is
different from this vs clara.server.tools.graph.analyze/add-auto-detected-annotations. Not by name
at least.

3. there is general inconsistent in function naming in analyze ns. There are functions taht say "build" and "build xxxx from" and then there are some that are just a noun alone of what it builds, like "analysis", and then we sometimes do the build ctor style idiom of ->thing.

4. within another ns, centered around a `rulebase-analysis` we have:

a) clara.server.tools.graph.core/rulebase-analysis creates one of these, should be named something
more consistent with what we do in analyze, like `analyze-rulebase`, but even that is lacking. it
could be `->rulebase-analysis` potentially. But this will be confusing with what `->session-analysis` would then be from `analyze-session-rules`.

Likely, `rulebase-analysis` is an ok name for what it is. It is an analysis of rulebase with
annotations given to help overlay what it knows from it's own non-RHS parsing analysis steps.

b) clara.server.tools.graph.core/analysis-result should say its the rulebase-analysis of some sort,
but it really is a truncated variation of it for the API. maybe `rulebase-analysis-external-view` to
just be clear and clara.server.tools.graph.core/rulebase-summary should be rulebase-counts or
something like that.

c) This ns is inconsistent with how "builder" functions are named. Sometmies just a noun and
sometimes with the -> ctor idiom. In general I prefer the -> idiom to not clash with local binding names.

4. clara.server.tools.graph.perf-test is a great example of how clashed these naming conventions
   are. EAch fn here is just really confusing what it does relative to the others.

5. clara.server.graph.server/build-annotations says "build" when we often say -> ctor idiom (prefer
   ctor idiom).

6. Several in memory ns:

a) clara.server.tools.graph.memory/session-snapshot-from-analysis again not a consistent name for a
builder. `->memory-analysis` is likely the better name with "memory-analysis" being the general
replacement name for "snapshot" or "memory-snapshot".

b) clara.server.tools.graph.memory/session-snapshot lack of consistent builder pattern name - prefer
-> ctor idiom.

7. clara.server.graph.cache/build-state inconsistent builder naming again. Also a bunch of noun
   functions that are more confusing like clara.server.graph.cache/analysis which should be
   get-rulebase-analysis if that is whawt it does and that aligns with "get-state" already there. Same
   as the "snapshot" noun fn - change to `get-memory-analysis`
