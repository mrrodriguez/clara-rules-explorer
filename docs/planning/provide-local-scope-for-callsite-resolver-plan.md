# Give callsite and constructor resolvers a sound view of local scope

Status: **proposal**. Scope: `explorer/src/clara/explorer/analyze/callsite.clj`,
`clara.explorer.analyze.kondo`, `clara.explorer.artifacts.schema` (the resolver context schemas),
and the analyzer test fixtures.

## 1. Problem

A `:callsite-resolver-fn` and a constructor `:type-resolver-fn` receive `:arg-form`: the argument
read from source as data. They receive no lexical scope. Many insertions are unresolved only
because the answer sits one binding away:

```clojure
(let [type->fact-type {"a" :t/a, "b" :t/b}]
  (when-let [fact-type (type->fact-type ?kind)]
    (insert! (make-fact fact-type m))))     ; fact-type: a local, value from a literal map

(let [fact-type :t/a]
  (insert! (make-fact fact-type m)))        ; fact-type: a local holding a literal
```

`trace-local-form` already follows a local to its init form, but only for the boundary argument
itself, and only for the whole argument. A local nested inside the argument (here, the first
argument of a constructor call) is never followed, and a resolver cannot ask.

### 1.1 Verified facts that shape the design

Checked against clj-kondo `2026.05.25` and the current `clara.explorer.analyze.kondo/read-init-form`.

- **A call through a local is not a var usage.** With `(let [make-fact (partial f x)] (insert!
  (make-fact :t {})))`, kondo reports `insert!` and `partial`, and no usage of `make-fact`. A
  constructor spec can never match such a call. It reaches the boundary pass and
  `:callsite-resolver-fn` as a list whose head is a local symbol.
- **`read-init-form` does not check what kind of binding it was given.** It reads the next form
  after the binding symbol. For a `let` that is the init. For a fn parameter it is the *next
  parameter*: given `(defn helper [fact-type m] …)`, the binding `fact-type` reads as the symbol
  `m`. The same holds for any non-`let` binding (destructuring, `loop`, `for`, `doseq`).
  `trace-local-form` therefore follows a parameter to a form that is not its value. Today the
  result is almost always an unresolved symbol, which hides the problem. Exposing the same
  lookup to resolvers would turn it into wrong answers.

The second point is a prerequisite: local lookup must know the binding kind before it hands out
an init form.

## 2. Design

### 2.1 Classify bindings

Resolve a local usage to a `LocalBinding`:

```clojure
{:kind      #{:let-init :param :destructured :loop :seq-binding :unknown}
 :init-form <read data>}   ; present only for :let-init
```

- `:let-init` means the symbol is a direct left-hand side of a pair in the binding vector of
  `let`, `let*`, `if-let`, `when-let`, `if-some`, or `when-some`, and the form after it is its
  value for the whole scope. Nothing else produces an `:init-form`.
- Every other kind is reported as such with no init form, so a resolver can tell "not knowable"
  from "not found".
- `:unknown` is the default. A binding that cannot be classified with certainty is `:unknown`.

Classification works from the enclosing form. kondo reports the binding macro's own usage with a
span; read that form and walk its binding vector, matching the kondo `:locals` entry by source
position. This needs a reader that keeps positions. Which one (a new dependency, or kondo's
parsed form) is the implementation's first decision; the requirement is that matching is by
position, never by name.

### 2.2 Look up by usage, so shadowing is kondo's problem

The lookup takes a symbol and is scoped to the callsite span. It finds every kondo
`:local-usages` entry for that name inside the span and follows each `:id` to its binding.

- All usages resolve to one binding: return it.
- They resolve to different bindings (the name is shadowed inside the span) or to none: return
  nil. A resolver must never see a binding that only some occurrences of the name refer to.

### 2.3 Hand it to resolvers

Add one key to `CallsiteResolverContext` and `ConstructorTypeResolverContext`:

- `:resolve-local` — `(fn [sym] -> nil | LocalBinding)`.

It is a function, not data, because only the resolver knows which symbols it needs. Resolvers
recurse by calling it again on symbols in the returned `:init-form`. The explorer caps the
total depth, as `max-resolution-depth` does today. For the constructor pass the scope is the
constructor call's span; for the boundary pass it is the boundary usage's span.

The key is optional in `FactTypeResolverContext`, the same as every other key there: a resolver
runs in code this library does not own and must stay total.

### 2.4 Move `trace-local-form` onto the classified lookup

`trace-local-form` uses the same lookup and follows a local only when its binding is
`:let-init`. Parameters and other kinds are no longer traced. This is a behavior change on
existing callsites and ships with its own fixtures (§4).

## 3. What stays out

- **Interprocedural flow.** A parameter's value depends on its callers. Following it means
  call-graph analysis, argument binding through destructuring, and handling every caller. Not
  attempted. A parameter stays `:param` and unresolved.
- **A local whose function value is chosen by a caller** (a function passed in and wrapped with
  `partial`). The callee is unknown, so the arguments cannot be interpreted. Same reason.
- **Values that depend on runtime data**, such as the result of a call on a logic variable.
- **Macro-minted locals** whose names the user never wrote.

## 4. Fixtures

Each is a minimal namespace in the analyzer test rules.

- `let` local holding a literal; a chain of two `let` locals.
- Local holding a literal map, looked up with a runtime key.
- Shadowed name inside the span (expects nil).
- Fn parameter followed by another parameter (expects `:param`, no init; pins the existing
  misread).
- Destructured symbol; `loop`, `for`, and `doseq` bindings (expect their kinds, no init).
- A local called as a function (expects the local lookup to work on the head symbol).

## 5. Open questions

1. **Exact versus possible types.** A lookup in a literal map, a `case`, or an `if` gives the
   *set* of values the type could be, not the one it is. A resolver reports that whole set. This
   is correct only when the visible flow accounts for every possible value: all branches or map
   values are literal types, and a default or fall-through is literal as well. One branch that is
   not written out leaves the callsite unresolved, never resolved with the visible part. Over-
   reporting a member the flow can never produce is acceptable; omitting one a resolved callsite
   could produce is not, since that reports `:full` for an incomplete set. `:resolved-types`
   carries no "one of" marker today. Decide whether the explorer adds one before a resolver
   that enumerates values ships.
2. **Reader choice** for §2.1, and whether it is also the right moment to tighten
   `read-init-form` for non-resolver callers.

## 6. Rollout

1. Binding classification and the §4 fixtures, with `trace-local-form` moved over (§2.4).
2. `:resolve-local` on both contexts, schema updated, documented in the annotation plugin docs.
3. First consumers are caller-owned resolvers. The explorer ships no resolver that uses it.
