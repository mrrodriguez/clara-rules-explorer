1. clara.rules.dsl/build-rule-action is not a edge case. `defrule` compiles to various forms, one is
   a form that emits the rule structure itself. That is the one we need for analysis and what we
   find on the session. The other forms are for other types of interaction that we are not concerned with.

2. the fact-type-fn is configurable and so is the ancestors-fn that goes with it. We cannot assume
   they will be the defaults in the sessions we analyze. We must use what the session is configured
   to use when fact type hierarchies are concerned.

3. Your approach to pass an ignore kondo rule config for defrule/defquery does not seem sustainable.
   This only captures 2 built-in clara-rules macros we may encounter in a namespace. A consumer
   namespace may have various of their own macros that act very similar to defrule/defquery and we
   would not be ignoring them. Our logic must be robust to not being confued by macros like defrule/defquery.
   It actually seems that a custom macro that emits a defrule should be equally problematic, but
   perhaps you avoided it in our test example because kondo analyzed only the def part of the form.
   To make the caller have to pass kondo configs that explicitly always leave out defrule/defquery
   forms is going to be tedios and something I want to avoid as much as possible. Can we work around
   it by noticing where we have a fully qualified rule/query name from the session productions as our set
   of known vars we intend to analyze ourself explicitly. We could then some prune the clj-kondo
   analysis of these var usage details to be replaced with our own?

4. We should always be able to resolve symbols from RHS bodies to their fully qualified symbols
   since we have the live ns that they were originally defined in present. Something like: clara.server.tools.graph.serialize/resolve-type should serve as an example.
   If you think there are symbols that won't be able to be resolved, I want you to explain why.

5. We should vet the `:in-memory-sources`. It is a feature that I haven't used and I'm not convinced
   is useful or needed yet. If adds complexity to our core path here, I do not want it unless we can
   justify its use.

6. When you rebuild the ns from the live runtime ns object, why would you remove clojure.core and
   java.lang? we may need them to fully qualify symbols and I don't see why you arbitrarily need to
   select them off. The ns should tell us all the aliasing, imports, and refers it has present
   including the full ns-map and we must honor that. In this way clj-kondo can work against what the
   real ns structure looks like in full. clj-kondo should already know how to handle clojure.core and
   `java.lang.*` etc.

7. clara-rules is only meant to work under the `:clj` lang. We do not need `:cljs` or another lang
   support. So why would `:cljc` be needed? Without it would we not analyze .cljc files from the
   perspective of the clj-side?

8. On the topic of analyis caching: We only need to analyze one session at a time. We can assume
   that is the cache scope. If we want to analyze a new session in a same running instance again, we
   should just start a fresh new cache and drop the last. Factor that into your caching design plan.
   The cache I believe is only meant to help speed up redundant subtrees of the analysis.

9. On the name clara.server.tools.graph.rhs . This work should remain scoped to or under clara.server.tools.graph.analyze
   this means a name could be clara.server.tools.graph.analyze.rhs

10. Your resolve-aliased-sym proposed function seems redundant with symbol resolution we already
    have that I point out in my feedback point (4) above.

11. I am in general worried about your attempt to parse and analyze RHS forms yourself. Including
    let-bindings etc. There are many complex caveats to doing this and that is precisely what we are
    relying on clj-kondo to do for us. you need to rethink that in section 4.3 . This is a key rule
    of this design, we cannot be ad hoc hand rolling walking arbitrary CLJ forms in the RHS. We must
    utilize the much more robust approach clj-kondo already provides that is vetted and battle
    tested. CLJ syntax walking is notoriously hard and full of caveats.

12. For section 4.3 pertaining ot "result aggregation per rule" The purposee of `:callsites` and
    `:source-str` is to just capture literally the forms clj-kondo found where the final `insert!`
    or `retract!` was found, but the thing it inserted/retracted could not be determined by default and
    was a "dynamic detected" callsite. This gives a precise form for a consume to analyze either
    manually or with their supplied resolver. It is fine if it loses line breaks and adds commas
    that are ignored in terms of CLJ syntax anyways.

13. We do not need a phased breaking change plan. We are on a working branch of this work. This is
    all greenfield. I am evolving the API to fit needs of where I'm using it in practice. Our docs
    nad our design should not be designed around avoiding breaking change or phasing steps in a way that
    maintains some sort of backwards compatibility. When it comes to this analyze ns we just need to do
    it the correct way from first principles of what we are trying to accomplish. Obviously plenty of
    what we have so far is a good starting point. Our doc changes though also do not need to mention
    breaking changes, they should just be shaped to match our API and our design we end up with. The
    purpose of phasing this implementation is so that it can be done incrementally where you can
    test and check yourself at various points to know you are building up correctly from the principles
    of the design and abstractions are clearer segmented. If you rely on eveyrthing working at once or
    nothing at all, it is going to be easy to get stuck in a complexity spiral.

14. on section 9 "edge cases": I don't know what you are concerned with `build-rule-action` for.
    It should not cause trouble in what the productions `:rhs` form gives us. It should only be a
    construct associated with defrule/defquery or callers to parse-rule/query directly themself, but it
    is not part of the actual RHS form analysis. We do not have anywhere we will be analyzing
    pre-embedded objects or functions.
