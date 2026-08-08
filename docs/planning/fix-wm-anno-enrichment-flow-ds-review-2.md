# Design Review #2 — Notes and Open Questions

## Overall

The two-phase split is the right call. Phase 1 addresses the actual root cause
of every round-1 defect (shard synchronization) rather than patching over it.
The system handle + pure transitions + single state atom is a clean design.
Phase 2 on that foundation is simpler and more obviously correct.

Below are the issues I found — one logical gap, several vague spots that need
resolution before implementation, and one naming concern.

---

## Issue 1 (logical gap): nil spec in `build-annotations` / `transition-reload`

Path C (`swap-session!` session-only) stores `:annotations-spec ← nil`. Path F
(`transition-reload`) then reads `(:annotations-spec state)` and passes it
to `build-annotations`:

```clojure
;; transition-reload (from Path F):
:annotations ← (build-annotations (:session state) spec (:annotations state))
```

When spec is `nil`, `build-annotations` receives a nil `annotations-spec` arg.
The `build-annotations` code shown in the plan normalizes the spec, destructures
`:source` and `:enrichment` from it, then dispatches on `enrichment` in a
`case`. With a nil spec, destructuring produces `{:source nil, :enrichment nil}`,
and `(case nil ...)` hits the `(:none nil)` branch, which returns `{}` when
source is nil. That chain works — but it's accidental: nowhere does the plan
state that `build-annotations` must accept nil. The doc says:

> `transition-swap` stores the `:annotations` argument as-given (nil for
> session-only swaps)

…but `build-annotations`'s parameter is described as "an AnnotationsSpec" in
the code comments, and nil is not a valid `AnnotationsSpec`. Either:

**(A)** Explicitly state that `build-annotations` accepts nil (treated as
         `{:source nil :enrichment nil}`) — document the contract, or
**(B)** Have `transition-reload` guard: `(if spec (build-annotations ...) {})`.

I recommend (B) — it keeps the `build-annotations` contract tight
(AnnotationsSpec | bare form, never nil) and makes the intentionality of
"no spec → empty annotations" explicit in the transition rather than relying
on the nil-behavior of a function designed for non-nil specs.

---

## Issue 2 (vague): transition function signatures not defined

The three transitions are the central API of Phase 1, but the plan only shows
what they *do* (via flow diagrams) — never their full signatures, contracts,
or return values. Key questions for each:

**`transition-start`**
- Takes `initial-state` and `config`. What is `initial-state`? `{}`? The plan
  says `(transition-start {} config)` in one flow trace — is the empty map the
  canonical seed?
- Does it validate/schema-check config, or does that stay in `start!`? The
  plan shows `start!` calling `s/validate` before `transition-start`, so the
  transition gets validated data — confirm.
- If the session is a rulebase (not a live session), does `build-static-layers`
  still call `props-layer`? The `props-layer` docstring says it needs a
  session. Does it degrade gracefully on a rulebase?

**`transition-swap`**
- Takes current state and opts. What is the shape of opts?
  `{:session s, :annotations spec, :warm-cache? true}`?
- When `:session` is nil (annotations-only swap), the current session is kept
  but `analyze-cache` should be cleared anyway (annotations changed →
  generated layer may differ). The plan's Path C flow shows `:analyze-cache ← {}`
  — is that unconditional on every swap, or only when session identity
  changes? If generative enrichment is in use, changing the sidecar source
  (without changing the session) should clear the kondo cache too.

**`transition-reload`**
- Takes current state. Returns new state with re-derived `:annotations`.
- Does it touch `:analyze-cache`? If the session hasn't changed, the kondo
  analysis is still valid — clearing it on reload is unnecessary churn.
  But if the sidecar file changed, the types it declares might differ, which
  affects the memory delta base. This doesn't affect kondo (kondo analyzes
  rule sources, not annotation files). So leaving `:analyze-cache` alone is
  correct. State this explicitly.

**Recommendation:** Add a "Transition contracts" subsection giving each
function's exact signature, preconditions, postconditions, and what it
mutates in the state map. This is the foundation Phase 2 builds on — it needs
to be unambiguous.

---

## Issue 3 (vague): `analyze-cache` — atom or value in the state map?

This is the murkiest open question in the plan. The state map has:

```clojure
{:analyze-cache {}}   ;; per-ns kondo memoization
```

But `analyze-session-rules` (called by `build-static-layers`) takes
`:cache-atom` — an atom, because it mutates the cache as it goes. The plan
acknowledges this tension in a parenthetical:

> `analyze-session-rules` takes an atom; the transition holds the new cache
> value and the swap commits it — pass an atom created per-build or keep
> `:analyze-cache` as an atom value inside the state map; either way its
> lifecycle is owned by the transitions

This hand-waves at two very different implementations:

**(A) State map stores a plain value, create a temporary atom per build.**
    ```clojure
    ;; In build-static-layers:
    (let [cache-atom (atom analyze-cache)]
      (analyze/analyze-session-rules {... :cache-atom cache-atom})
      ;; ... use @cache-atom for the result, but how does it get back
      ;;     into the state map?
      )
    ```
    Problem: `build-static-layers` returns layers, not state. It can't update
    `:analyze-cache` in the state map. The new cache value gets lost unless
    `build-auto-detect-annotations` threads it back through a return-value
    side channel.

**(B) State map stores an atom.**
    ```clojure
    {:analyze-cache (atom {})}
    ```
    Then `analyze-session-rules` mutates it directly. But the plan says
    "transitions must stay pure-ish" (swap functions may retry). An atom
    inside the state breaks that — mutations during a retried swap persist.
    In practice the retry is fine because `analyze-session-rules`' mutations
    are idempotent (same session → same cached result), but the principle is
    violated.

**(C) Store the atom outside the state map, owned by the system handle.**
    This keeps the state map pure but puts the atom back as a separate
    mutable reference — exactly the sharding Phase 1 set out to eliminate.

This needs a decision. I lean toward (A) with a caveat: `analyze-cache` is
both an input to and output from `build-auto-detect-annotations`, so it
should be threaded explicitly. Something like:

```clojure
;; build-auto-detect-annotations returns {:annotations ... :analyze-cache ...}
;; and the transition destructures both into the new state map.
```

But that changes the signature of a function that currently just returns a
bare annotations map. The plan shows `build-auto-detect-annotations` returning
the annotations value directly. If it also needs to return the updated cache,
that's a structural change not reflected in the current code snippets.

**Recommendation:** Pick (A) or (B) explicitly, update the code snippets and
state map schema to match, and explain how the cache value round-trips from
the state map into `analyze-session-rules` and back.

---

## Issue 4 (vague): `SwapSessionOpts` schema

Mentioned in passing but never defined:

```clojure
(let [{:keys [session annotations warm-cache?]
       :or {warm-cache? true}} (s/validate SwapSessionOpts opts)]
```

What are the full keys? Are `:session` and `:annotations` both optional (with
the explicit nil-check after validation)? Is `warm-cache?` the only additional
key? A schema definition matching `StartOpts` in level of detail would help.

---

## Issue 5 (minor): multi-instance `default-system` semantics

The plan says:

> The 1-arity forms operate on the `default-system` (set by the most recent
> `start!`), preserving every existing callsite … The 2-arity forms enable
> isolated per-fixture systems in tests.

But `start!` itself `reset!`s `default-system`. If tests call `start!` (1-arity
path) for the default-system convenience, each call overwrites the global.
The Phase 1 test says "Two systems from two `start!` calls hold independent
state (drive via 2-arity `swap-session!`; assert no cross-talk)" — this works
because they capture the returned system handle and use 2-arity mutations.
But the test's `start!` calls still touch the shared default-system atom.

Is the intent that tests **must** use the 2-arity forms for assertions and
only use the default-system facade for the setup convenience? Or should
`start!` *not* auto-register the default-system when called from tests (e.g.
via an explicit `:register-as-default?` flag)?

This is a test-isolation concern — if one test's `start!` clobbers the
default-system while another test's 1-arity call is in flight, assertions
could silently target the wrong system. Not a correctness problem for the
plan (the plan acknowledges this and points at 2-arity forms for safety),
but worth a sentence on the recommended test pattern.

**Recommendation:** Add a note: "Tests that run concurrently should use
2-arity forms exclusively after setup. The default-system defonce is a REPL
convenience, not a concurrency-safe handle."

---

## Issue 6 (naming): `annotations-spec` vs `annotations` — confusion risk

The state map has both:

```clojure
{:annotations-spec  ...   ;; the AnnotationsSpec (e.g. {:source "a.edn" :enrichment :auto-detect})
 :annotations       ...}  ;; the built bare annotations map (merge-layers result)
```

This is correct but the name `annotations` is used in two contexts throughout
the codebase: as an `AnnotationsSpec` (in `start!` opts, `swap-session!` opts,
CLI args) and as a bare annotations map (in the state, in cache signatures).
The plan relies on context to disambiguate, which is fine in prose but risks
confusion in code.

Consider whether the local variable names in transitions could differentiate:
e.g. `annotations-spec` for the spec everywhere, and `annotations` or
`built-annotations` for the map. The plan already does this in most places
but not all:

```clojure
;; In transition-start flow:
:annotations ← (build-annotations s spec {})   ;; result is the map, not the spec
```

The arrow notation helps but the variable names in actual code won't have
arrows.

This isn't a defect — just a flag for the implementation phase. The spec vs.
result distinction is the thing that caused review issue #1; it needs to stay
crystal clear in variable names.

---

## Issue 7 (question): `transition-reload` and cache warming — when the answer is the same

```clojure
(defn reload-annotations! [system]
  (let [new-state (swap! (:state-atom system) transition-reload)]
    (cache/warm! (:cache system) (:session new-state) (:annotations new-state))
    (:annotations new-state)))
```

What if reload produces the exact same annotations value? e.g. the spec is
`{:enrichment :none}`, the source file hasn't changed, and `coerce-to-bare-annotations`
returns a structurally-equal but not `identical?` map. The cache uses
identity-based invalidation, so even a `=` map forces a re-warm. This is fine
(benign inefficiency), but the plan says cache invalidation is by `identical?`
— the new annotations map will be a fresh reference, so it counts as a
mutation. Confirming: the intent is that reload *always* re-warms, even if
the annotations are semantically unchanged. This is acceptable for an
in-memory operation.

---

## Issue 8 (minor): `build-annotations` `case` default throws — what about `:reuse`?

```clojure
(case enrichment
  :reuse ...
  (:none nil) ...
  (:auto-detect-from-rulebase :auto-detect-from-memory :auto-detect) ...
  (throw (IllegalArgumentException. ...)))
```

The `case` enumerates all valid modes. If a caller passes `:reuse` with a nil
source, `current-annotations` is returned. If `current-annotations` is `{}`
(startup), reuse returns `{}` — correct. No issue here, just confirming the
`case` is exhaustive.

What's the `default` expression? In Clojure, `case` without a default throws
`IllegalArgumentException` with its own message. Adding an explicit throw
with a better message is fine, but the Clojure `case` already throws on
unmatched values. The explicit throw is still worth keeping for the message
quality.

---

## Summary

| # | Severity | What | Recommendation |
|---|----------|------|----------------|
| 1 | Logic gap | nil spec → build-annotations path is accidental | Guard in `transition-reload`: `(if spec (build-annotations ...) {})` |
| 2 | Vague | Transition function signatures/contracts not defined | Add "Transition contracts" subsection |
| 3 | Vague | `analyze-cache` atom-vs-value unresolved | Pick a design (A/B/C), update code snippets |
| 4 | Vague | `SwapSessionOpts` schema not defined | Add schema definition |
| 5 | Minor | `default-system` race in concurrent tests | Add recommended test pattern note |
| 6 | Naming | `annotations` overloaded (spec vs map) | Flag for implementation, no plan change needed |
| 7 | Clarify | Reload always re-warms cache | State explicitly (benign, acceptable) |
| 8 | Minor | `case` exhaustiveness | Confirm: the explicit throw is deliberate (message quality) |

The only blocking issue is #3 (`analyze-cache` design) — everything else is
either a clarification or a small guard. The overall architecture is solid.
