1. Annotations generation should support an option should be able to take existing annotations
   optionally with merge rules.

2. Updates were made to ctor vs generic callsite resolution that need reviewed/refined and this is
   the explanation:
   ```
  The duplicate, in simple terms

  Two mechanisms were each independently allowed to explain the same insert:

  (r/defrule my-rule
    [Application (= ?app-id app-id)]
    =>
    (r/insert! (->fact :custom-fact-type {:app-id ?app-id})))

  - The constructor path scans the rule's reachable call subtree, finds the ->fact var-usage, and emits a callsite with :constructor-sym + :via.
  - The boundary path separately reads the argument of insert! — which is the same (->fact …) text — and hands it to :callsite-resolver-fn, which also resolves it.

  The merge step only dropped boundary callsites that came back :unresolved. A resolved one was kept and appended. I reproduced it against the core's own fixture:

  :callsites [{:source-str "(->fact :custom-fact-type {...})" :status :resolved
               :resolved-types [:custom-fact-type]
               :constructor-sym .../->fact}                      ;; constructor path
              {:source-str "(->fact :custom-fact-type {...})" :status :resolved
               :resolved-types [:custom-fact-type]}]             ;; boundary path — same callsite

  :insert-types was still correct (types are set-unioned); the callsite list was wrong.

  The core fix

  Your expectation was right, so I fixed it in the core rather than routing around it. Constructor resolution now runs first and owns what it explains:

  1. A boundary call whose own argument is a constructor call that resolved is attributed to that constructor — by lexical source-span containment (usage-encloses?),
  so it stays exact when one rule has several insert! calls. That boundary usage is removed before resolution, so :callsite-resolver-fn is never invoked for it. One
  entry, with provenance.
  2. Constructor callsites still supersede unresolved boundary callsites — the helper-chained (helper …) argument the generic path can't see into.
  3. Every other boundary argument reaches :callsite-resolver-fn normally, including in the same rule as a constructor insert.

  Verified: with both hooks wired, the direct insert now yields exactly one callsite and the generic resolver is called with a ->fact form zero times.

  Same-region fix while I was there: boundary-by-caller was a map built from usages, so a var with both insert! and insert-all! silently kept only one — :via's
  :boundary-var-name-sym could name the wrong boundary fn. Attribution is now per-usage. I also collapsed the three-branch :resolution aggregation into the same
  uniform rule the two resolvers already use (this makes all-unresolved report :none instead of :partial — more correct).

  New fixture rule-ctor-and-opaque-inserts (one ->fact insert + one with-meta insert in one rule) and test-constructor-resolver-overrules-callsite-resolver lock all
  of this down. 78 tests / 520 assertions, 0 failures.

  Residual, documented in rule-annotations.md: (let [f (->fact :t m)] (insert! f)) isn't lexically enclosed, so a :callsite-resolver-fn that also resolves
  constructors from the locals-traced form could still add an entry. The guidance is not to resolve constructors of interest in the generic hook — that's what the
  pair is for.
   ```

Then I looked at a specific case and had some additions performed:
```
  The constructor type resolver does get to resolve it. With example config:

  insert-types [:demo/local-bound] | resolution :full
    :resolved [:demo/local-bound] "(->fact :demo/local-bound {:id ?app-id})"
              [ctor] via [rule-ctor-bound-to-local ->fact]

  Full type, promoted, with provenance. Nothing was ever missed.

  And no — it does not depend on locals analysis at all. That's the distinction worth drawing, because the two mechanisms are genuinely separate:

  ┌─────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────────┐
  │                 │                                         finds it how                                          │                    role                    │
  ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────┤
  │ Constructor     │ kondo :var-usages — (->fact :t m) is a call site in the rule's body, wherever in the body it  │ resolves the type, builds :via             │
  │ path            │ sits                                                                                          │                                            │
  ├─────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────┤
  │ Boundary path   │ kondo :locals — trace-arg-form traces the argument f back to its binding's init form          │ sees the same constructor call a second    │
  │                 │                                                                                               │ time                                       │
  └─────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────────┘

  The constructor path never looks at the insert! argument, so the let is invisible to it — it just sees a ->fact call in the reachable subtree. The locals tracing is
  purely the boundary path's own route to the same form. So the residual was "two independent mechanisms both see it," not "the type is lost."

  Closed it anyway

  You'd said duplicates shouldn't happen, and this was one, so I fixed it rather than documenting it away. The traced form is structurally equal to the constructor's
  form — a clean, position-free join:

  :constructor-arg-forms  ; resolved constructor call forms
  ;; in resolve-boundary-callsites: trace first, and if the traced form is one of
  ;; them, skip the argument entirely — no resolver call, no callsite.

  Before/after with a :callsite-resolver-fn that also reads ->fact:

  before:  :resolved [:demo/local-bound] "(->fact ...)"  [ctor]
           :resolved [:demo/local-bound] "f"             [boundary]   ← duplicate

  after:   :resolved [:demo/local-bound] "(->fact ...)"  [ctor]

  Note the duplicate's :source-str was "f" — which is why neither span containment nor source-string comparison would have caught it. Only the traced form does.

  There are now three ownership routes, one per way an argument can be written — none subsumes the others:

  ┌─────────────────────────────┬────────────────────────────────────────────────────────────────────┐
  │      The argument is…       │                             Matched by                             │
  ├─────────────────────────────┼────────────────────────────────────────────────────────────────────┤
  │ the constructor call itself │ source-span containment                                            │
  ├─────────────────────────────┼────────────────────────────────────────────────────────────────────┤
  │ a call that leads to it     │ a call inside the boundary call names a link on the :via callstack │
  ├─────────────────────────────┼────────────────────────────────────────────────────────────────────┤
  │ a local bound to it         │ the locals-traced form equals the resolved constructor form        │
  └─────────────────────────────┴────────────────────────────────────────────────────────────────────┘

  Alias-discovered callsites (:fact-type-spec-fn) are excluded from skipping, since they're deliberately never auto-resolved.
```

More cases I found and we addressed:

```
You say provenance can be assured here, but I think that would not be the case for ctor cases if we had multiple inserts right - or even more exterme, the local was never used in an insert at all. eg.:


Case (1) What would callsites look like here?

```
(let [f (->fact :x)]
(insert! (->fact :y))
(insert! (some-other-fn))
(insert! f))
```

Case (2) What would callsites look like here?

```
(let [f (->fact :x)]
(insert! (some-other-fn))
)
```
```

Agent results for changes:
```
  Case 1 — before

  insert-types [:demo/inline-y :demo/local-x]   resolution :full
    :resolved [:demo/local-x]  "(->fact :demo/local-x ...)"   [ctor]
    :resolved [:demo/inline-y] "(->fact :demo/inline-y ...)"  [ctor]

  (insert! (some-other-fn)) was erased — no callsite, no type — and :resolution claimed :full over three inserts, one of which was unexplained. Cause: the inherited
  "constructor callsites supersede unresolved boundary callsites" rule. That was a blunt instrument from before ownership was precise; with three ownership routes it
  was pure dead weight. Removing it broke nothing (534 assertions unchanged).

  There was also a second bug you'd have hit: (->fact :x) in the let was being attributed to (insert! (->fact :y)), because my chain set included the constructor
  symbol itself, so a different ->fact call inside that insert matched it. The chain now carries only the intermediate vars; the constructor may only match by usage
  identity.

  Case 2 — before

  insert-types [:demo/never-inserted]   resolution :full
    :resolved [:demo/never-inserted] "(->fact ...)" [ctor] via=[rule ->fact]

  A pure false positive with fabricated :via, and the real insert erased too. Per your call, ownership now decides what counts as an insert:

  Both, now

  Case 1:  [:demo/inline-y :demo/local-x]   :partial
    :resolved   [:demo/local-x]  "(->fact :demo/local-x ...)"   [ctor]   ← via the traced local
    :resolved   [:demo/inline-y] "(->fact :demo/inline-y ...)"  [ctor]   ← lexically
    :unresolved nil              "(opaque-fact ?app-id)"        [boundary]

  Case 2:  nil   :none
    :unresolved nil "(opaque-fact ?app-id)" [boundary]

  The restructure: boundary arguments are read and locals-traced once up front, then the constructor path claims the ones it can prove it reaches, and the boundary
  path resolves the rest. That replaced two ad-hoc mechanisms (:covered-boundary-usages + :constructor-arg-forms) with one :owned-arg-idxs, and made ownership
  per-argument instead of per-usage.

  Two further consequences worth knowing before your live run:

  - A constructor the resolver can't type is no longer emitted — the argument falls through to the boundary path rather than being reported twice.
  - The reachable-subtree over-attribution caveat is retired. The scan now produces candidates; ownership decides which are real. On mortgage-loan-ruleset, where
  rules share large helper subtrees, this likely moves numbers more than anything else in this change — types previously credited because a ->fact merely sat
  somewhere reachable will now drop unless an insert reaches it. Worth splitting out in your delta report.

  The accepted trade-off, documented in both repos: indirection none of the three routes can see ((insert-all! (apply f args))) loses a type it previously kept — but
  the boundary callsite stays :unresolved, which is the honest signal, rather than a guess.
```
