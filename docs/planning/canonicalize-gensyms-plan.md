# Canonicalize reader gensyms in persisted forms

Status: **proposal**. Scope: `explorer/` analysis and serialization. No API or
artifact-shape change; artifact *values* change once.

## 1. Problem

Regenerating a rulebase's artifacts with no source change still produces a diff.
Printed forms carry reader gensyms whose numbers come from a JVM-wide counter, so
they depend on everything else the process read first:

```clojure
;; one run                                    ;; the next
(fn* [p1__79059#] (= (:type p1__79059#) "C")) (fn* [p1__79856#] (= (:type p1__79856#) "C"))
```

That churn reaches every persisted value built from a form:

- `:rhs-form` (`production-details.edn`), `:constraints` and accumulator `:form`
  (`production-conditions.edn`);
- callsite `:source-str` in the annotation layers — and, because
  `clara.explorer.annotations.callsite/callsite-id` hashes `:source-str`, the
  `:callsite-id` itself.

The id churn is the costly part. `:callsite-id` is the join key a curated layer
writes against (`clara.explorer.annotations.rebase`, the overlay), and it is
meant to change only when the callsite's own source text changes. Today a
curated resolution against any callsite containing `#(…)` stops matching on the
next regeneration, with no edit to the rule.

Across a registry of many rulesets, a full regeneration with no rule changes
touches these files in a meaningful fraction of units, and every such
difference is a `pN__NNN#` renumbering. `#(…)` is by far the most common
source; the other reader-gensym shapes (`x__123__auto__`, `rest__123#`,
`G__123`) are rarer but are the same problem.

## 2. Where the numbers enter

Two independent routes, both through the Clojure reader:

1. **Forms the explorer reads from source text.** `clara.explorer.analyze.kondo`
   slices source at a kondo span and calls `read-string` on it:
   `read-boundary-args`, `read-ctor-form`, `read-init-form`. The results become
   `:arg-form`, whose `pr-str` is `:source-str`. `read-string` over `#(…)` mints
   fresh numbers on every call.
2. **Forms Clara stored at load time.** A production's `:rhs` and its
   conditions were read when the rule namespace loaded, so their numbers were
   fixed then. `clara.explorer.serialize` prints them through `*form-printer*`
   (`serialize-rhs-form`, `serialize-condition`).

A restored session feeds route 1 from route 2: `analyze.synth` prints Clara's
stored forms as source for clj-kondo, so the text the kondo readers slice
already contains literal `p1__79059#` symbols. Canonicalizing by symbol name
covers that case with no special handling.

## 3. Design

One pure function, applied at the two points where a form becomes persisted text.

### 3.1 `clara.explorer.utils/canonicalize-gensyms`

`(canonicalize-gensyms form) -> form`. Walks `form` (`clojure.walk/postwalk`),
and renames every symbol matching a reader-gensym pattern to a canonical name
numbered by order of first appearance within `form`:

| Reader output | Pattern | Canonical |
|---|---|---|
| `#(…)` positional arg | `p(\d+)__\d+#` | `p<pos>__<ord>#` — the positional index is kept |
| `#(…)` rest arg | `rest__\d+#` | `rest__<ord>#` |
| syntax-quote `x#` | `(.+)__\d+__auto__` | `<x>__<ord>__auto__` |
| `(gensym)` default | `G__\d+` | `G__<ord>` |

`<ord>` is one counter shared across all patterns within `form`, so two
distinct gensyms never map to the same name. The canonical names still match
the patterns, so they cannot collide with a symbol the walk leaves alone.
Metadata on renamed symbols is preserved. A form with no gensyms is returned
unchanged (identical, not just equal).

`clara.explorer.utils` is the home because it is already the leaf namespace
every other namespace may require without a cycle.

### 3.2 Scope: one persisted string, one canonicalization

Numbering is per unit of persisted text, so a unit's output depends only on its
own content:

- **Reading** (`analyze.kondo`): canonicalize each form the readers return —
  each boundary argument separately (after `rest`, not the whole call), the
  ctor form, the init form. A `:source-str` is then a function of that
  argument's text alone, which is exactly the stability `callsite-id` promises.
- **Printing** (`serialize`): a private `print-form` in `clara.explorer.serialize`
  that does `(*form-printer* (utils/canonicalize-gensyms form))`, and every
  current `*form-printer*` call site (`serialize-rhs-form`, and the
  `serialize-forms` / `serialize-accumulator` helpers in `serialize-condition`)
  calls it instead. `*form-printer*` stays rebindable; canonicalization is not
  something a caller can bind away.

Per-unit scope is sound because no reader gensym spans two units: a `#(…)`'s
args are confined to that literal, and an auto-gensym to its syntax-quote form.
Even where one could, the renamed strings are display and hash inputs, never
evaluated together.

### 3.3 What deliberately does not change

- `analyze.synth`'s generated source. It is kondo's input, never persisted, and
  kondo positions must match the text it was given.
- Resolver inputs keep working: `:arg-form` handed to a
  `:type-resolver-fn` / `:callsite-resolver-fn` is canonicalized, but resolvers
  read keywords and resolve vars, and a gensym local resolves to nothing under
  either name.
- The editor and navigate routes work from kondo spans, not printed forms.

## 4. Changes

| File | Change |
|---|---|
| `explorer/src/clara/explorer/utils.clj` | add `canonicalize-gensyms` |
| `explorer/src/clara/explorer/analyze/kondo.clj` | canonicalize results of `read-boundary-args` (per arg), `read-ctor-form`, `read-init-form` |
| `explorer/src/clara/explorer/serialize.clj` | add private `print-form`; route every `*form-printer*` call through it |
| `explorer/test/clara/explorer/…` | tests below |
| `docs/planning/explorer-renaming-migration.md` or release notes | one line: callsite ids and printed forms change once; regenerate |

## 5. Tests

- `canonicalize-gensyms` unit tests: each pattern; nested `#(…)` inside a
  `fn`; two `#(…)` in one form get distinct ordinals; positional index kept
  (`p2__…#` stays `p2`); non-gensym symbols and metadata untouched; no-gensym
  form returns identical.
- **Counter independence**: read the same source text twice in one JVM, with
  `(dotimes [_ 1000] (gensym))` between, and assert equal `:source-str`,
  `:callsite-id`, `:rhs-form`, and `:constraints`. This is the regression test
  for the actual problem.
- Existing `analyze_test` / `serialize_test` / `core_test` expectations that
  pin a printed form containing `#(…)` are updated to canonical names.

## 6. Consumer impact

- **One-time value churn.** Every `:callsite-id`, `:source-str`, `:rhs-form`,
  and `:constraints` value containing a gensym changes once. Regenerate derived
  artifacts. After that, regenerating unchanged source is byte-stable for these
  values.
- **Curated layers.** An `agent-annotations.edn` entry keyed by a callsite id
  that contains a gensym dangles after the upgrade. It was already going to
  dangle on the next regeneration; after this change it is the last time.
  `clara.explorer.annotations.report`'s dangling-entry lint surfaces them.
- No `:slim :dropped` change, so no registry shape skew.

## 7. Out of scope

- **Macro-generated names that embed a gensym indirectly.** e.g. a library
  macro naming a local `(symbol (str sym (md5 (pr-str body))))`: when `body`
  contains `#(…)`, the counter value is hashed into the name and no pattern can
  recover it. The fix belongs in that macro (a fixed local name — it need only
  be unique within its own expansion).
- **`(gensym "prefix")`**, which yields `prefix123` with no delimiter and
  cannot be matched without false positives on ordinary symbols. Add a pattern
  only if a real case shows up.

## 8. Acceptance

Regenerating a registry twice from the same checkouts, in separate JVMs,
produces no diff in `production-details.edn`, `production-conditions.edn`, or
the annotation layers, apart from §7 cases.
