# Callsite resolution: record-ctor precedence and `::` keywords

Status: **proposal**. Scope: `explorer/` analysis (`analyze`, `analyze.kondo`,
`analyze.callsite`, `analyze.ctor`). Only artifact values change: resolved
`:insert-types` / `:retract-types` and callsite `:resolved-types` get more
accurate.

## 1. Problem

Two ways the analysis reports the wrong fact types for an insert, where the
correct answer is available to it:

1. **A record constructor unrelated to the fact wins.** When a rule's insert path
   goes through a helper that also builds an unrelated record, that record's type
   can be credited as the insert type. Then the type the host's fact builder
   resolves is lost. A typical case is a validation helper that constructs a
   schema library's internal records (e.g. `malli.core/->Tag`) on its way to
   building the fact. The rule's `:insert-types` then reads
   `[malli.core.Tag malli.core.Tags]` rather than its real type.
2. **`::auto-resolved` keywords read in the wrong namespace.** `analyze.kondo`
   reads callsite text with `read-string` (`read-boundary-args`, `read-ctor-form`,
   `read-init-form`) without binding `*ns*`. `::type` then resolves against
   whatever `*ns*` is at analysis time instead of the callsite's namespace, and
   `::alias/type` throws, so the argument is silently dropped (the `catch`
   returns nil). A host resolver that receives `:arg-form` sees the wrong keyword
   or no argument at all.

Both put a wrong or missing type in the generated layer. A curated overlay can
correct them only one callsite at a time, and only if someone notices.

## 2. Record-ctor precedence

### 2.1 What the design already promises

`clara.explorer.analyze/extract-insert-types` states the order: caller-driven
resolution (constructor-of-interest via `:fact-constructors`, then the boundary
chain and `:callsite-resolver-fn`) runs first and is never displaced. The
record-ctor scan (`inserter-type-map`) is a heuristic fallback labeled
`:via {:source :record-ctor-scan}`, credited to a direct-inserter var only when no
caller-driven path accounted for any of that var's boundary arguments.

The symptom breaks that promise, so the first step is to find which path emits
the stray type.

### 2.2 Candidate paths

- **Fallback scan credited when it shouldn't be.** `handled-vars` in
  `compute-heuristic-fallback-callsites` is keyed by the var holding the boundary
  usage. If the helper that builds the unrelated record is a different
  direct-inserter var from the one whose argument was resolved, its scan types are
  still credited. The scan is subtree-wide and name-shape based, so it picks up
  `->Tag` from any call in that body.
- **Ctor chain on a traced form.** `resolve-traced-arg` tries
  `ctor/resolve-ctor-form` before `:callsite-resolver-fn`. If locals tracing lands
  on a form whose head is an unrelated record ctor (the value bound in a `let` is
  a validation result, not the fact), the ctor chain answers first and the
  resolver never sees the argument.
- **Constructor-of-interest ownership.** An argument a declared fact constructor
  should own may not be marked in `:owned-arg-idxs` when the constructor is
  reached through a wrapper, which leaves it to the paths above.

### 2.3 Approach

1. **Reproduce** with a test ruleset under `explorer/test/clara/explorer/test/rules/`:
   a local record standing in for the library's internal record, a validation
   helper that constructs it, and a host fact builder (a `->fact`-style fn that is
   not a record ctor) whose result is inserted. Declare the builder through
   `:fact-constructors` in one variant and resolve it through
   `:callsite-resolver-fn` in another. Assert that `:insert-types` is the builder's
   type only, and read `:via` on each callsite to see which path emitted the stray
   type.
2. **Fix at the path the reproduction points to**, keeping the stated order:
   - fallback: a var's scan types are ceded when any boundary argument that is
     reachable through it was handled, not only arguments held by that var;
   - ctor chain: a record ctor answers only when it is the traced argument form
     itself, not a form nested inside it or reached through another call;
   - ownership: a wrapper that forwards to a declared constructor transfers
     ownership of the argument.
3. If the scan still has to run for a var, keep a way to scope it
   (`:dynamic-type-fallback-resolution` and the index's type filter). Excluding
   types by namespace is a host decision, and the explorer shouldn't hard-code any
   library.

## 3. `::` keywords

### 3.1 Change

Every `read-string` over callsite source text in `analyze.kondo` runs with `*ns*`
bound to the callsite's namespace:

```clojure
(binding [*ns* (or (find-ns from) *ns*)]
  (read-string call-str))
```

- `read-boundary-args` and `read-ctor-form` use the usage's `:from`;
  `read-init-form` uses its `ns-sym`.
- The namespace is live during analysis (the explorer resolves ctor vars through
  it already), so `::alias/type` resolves through that namespace's aliases.
- When the namespace isn't loaded, the read falls back to the current behavior,
  and the callsite is marked unresolved rather than being given a wrong keyword.

`analyze.synth` reads its own printed output, not user source, so it is
unchanged.

### 3.2 Interaction with canonicalized gensyms

The readers already canonicalize gensyms in their results
(`canonicalize-gensyms-plan.md`). The `*ns*` binding wraps the read, and
canonicalization runs on its result, so the two are independent. `:source-str`
is the source text and doesn't change, so `:callsite-id` is stable across this
change.

## 4. Changes

| File | Change |
|---|---|
| `explorer/src/clara/explorer/analyze/kondo.clj` | bind `*ns*` around each callsite read (§3.1) |
| `explorer/src/clara/explorer/analyze.clj` and/or `analyze/callsite.clj`, `analyze/ctor.clj` | the §2.3 fix, wherever the reproduction points |
| `explorer/test/clara/explorer/test/rules/` | test rules for both cases |
| `explorer/test/clara/explorer/analyze_test.clj` | tests below |
| `explorer/docs/analyze-pipeline-concepts.md` | resolution order: state the ownership / ceding rule as fixed |

## 5. Tests

- **Ctor precedence**: the §2.3 reproduction, in both the `:fact-constructors`
  and `:callsite-resolver-fn` variants, asserts the builder's type only and no
  `:record-ctor-scan` callsite for the unrelated record. A control case (a direct
  `(insert! (->Rec …))` with no host resolution) still resolves through the ctor
  chain.
- **`::` keywords**: a test namespace that inserts a fact typed `::local-type`, and
  one that uses `::alias/type` through an `:as` alias. Both resolve to the
  fully-qualified keyword. A resolver receiving `:arg-form` sees the qualified
  keyword. A read under an unloaded namespace yields an unresolved callsite, not
  a wrong type.
- Existing `analyze_test` expectations are unchanged except where they pinned the
  old wrong behavior.

## 6. Consumer impact

- Regenerated layers may change `:insert-types` / `:retract-types` for rules that
  hit either case. Those are corrections.
- Curated overlay entries that patched either case become redundant.
  `clara.explorer.annotations.report`'s lint shows where the overlay now agrees
  with the generated layer.

## 7. Acceptance

- A rule that inserts through a helper that also builds an unrelated record
  reports only the fact's type.
- A rule that inserts `::type` or `::alias/type` reports the keyword qualified
  in the callsite's own namespace.
