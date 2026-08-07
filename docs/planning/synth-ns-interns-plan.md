# Plan: Emit `declare` forms for interned vars in synthesized namespace sources

## Problem Recap

`reconstruct-ns-source` rebuilds `(ns …)` from a live Namespace, but omits
everything the namespace _owns_ itself — only external references (requires,
aliases, refers, imports) are reconstructed. When `synthesize-ns-source` falls
back to it (no source on classpath), helper functions like `->fact` are
undefined symbols in kondo's view. No `:to` / `:name` on `:var-usages` → no
callee → no `:fact-constructors` match-fn ever fires.

## Scope

The fix is entirely in `server/src/clara/server/tools/graph/analyze/synth.clj`,
with corresponding test additions in
`server/test/clara/server/tools/graph/analyze_test.clj`.

## Design Decisions

### 1. Emit `(declare …)` for non-production interned vars

For each namespace without classpath source, enumerate `ns-interns`, subtract
the production names (rules + queries whose synthetic `(def <tag> …)` snippets
already exist), and emit a single `(declare remaining-names…)` form after the
reconstructed `(ns …)` but before the synthetic snippet defs.

Why `declare` and not `(def …)` stubs:

- `declare` is enough for kondo to attribute the callee (`:to` and `:name` on
  the `:var-usage`). No type checking or arity checking is needed.
- Stays consistent with the philosophy that `reconstruct-ns-source` emits the
  `ns` form only — the declarations are part of the _synthesis_ layer.

### 2. Only on the reconstruction path

`base-source-fn` determines whether real source exists. When it returns a
string, the real source already declares everything — we must not add
duplicate declarations. The `(declare …)` emission is conditional on
`base-source-fn` returning nil.

### 3. Production-name exclusion

The `productions` arg (a sequence of `{:name … :rhs …}` maps) flowing into
`synthesize-ns-source` carries the local names of every rule and query in the
namespace. These names must be excluded from the `declare` list because they
already get a `(def __clara_explorer_rule_N__ …)` snippet, and the
`prune-and-rename-analysis` step handles attributing them later. Declaring
them in addition would create unnecessary double-references.

Concretely:

```clojure
(let [prod-names (into #{} (map (comp symbol name normalize-key-fn :name)) productions)
      helpers   (->> (ns-interns nsobj)
                     keys
                     (remove prod-names)
                     sort)]
  …)
```

### 4. `:refer-clojure` consistency

A var may shadow a `clojure.core` name (e.g. someone defines `defn` in their
rule ns). `reconstruct-ns-source` already detects this via `core-deviations`
and emits a `(:refer-clojure :exclude [defn])` clause. `(declare defn)` in
the same synthesized source resolves correctly — kondo treats the declared var
as the local one, not `clojure.core`'s. No special-casing needed.

### 5. Optional `:arglists` metadata

Deferred. Not required for callee attribution. Can be added later if a
downstream consumer wants arity checking on the synthetic calls.

## Implementation Steps

### Step 1 — `synth.clj`: Add `non-production-interns` helper

Extract a private function that, given a namespace symbol and a set of
production local names, returns the sorted list of interned symbols that are
not productions:

```clojure
(defn- non-production-interns
  "Returns the sorted list of symbols interned in the namespace `ns-sym`
   that are not in `production-names`."
  [ns-sym production-names]
  (->> (ns-interns (the-ns ns-sym))
       keys
       (remove production-names)
       sort))
```

### Step 2 — `synth.clj`: Emit `(declare …)` in `synthesize-ns-source`

In `synthesize-ns-source`, when `base-source-fn` returns nil:

1. Build `production-names` set from `productions` and `normalize-key-fn`.
2. Call `non-production-interns`.
3. If non-empty, emit a `(declare …)` line after the reconstructed ns form
   and adjust `offset` to account for it.

```clojure
(defn synthesize-ns-source [ns-sym productions base-source-fn normalize-key-fn]
  (let [base-source (or (base-source-fn ns-sym)
                        (reconstruct-ns-source ns-sym))
        prod-names (into #{} (map (comp symbol name normalize-key-fn :name))
                         productions)
        ;; Only emit declaration when source was reconstructed (not real file)
        declare-form (when (nil? (base-source-fn ns-sym))
                       (let [helpers (non-production-interns ns-sym prod-names)]
                         (when (seq helpers)
                           (str "(declare " (str/join " " helpers) ")\n"))))
        extended-source (str base-source (or declare-form ""))
        offset (count (str/split-lines extended-source))
        …]
    …))
```

Note: `base-source-fn` is called twice when source is missing — once to get
nil (triggering reconstruction), once to check nil-ness for the conditional.
This is cheap (it's a resource lookup). If this is a concern, capture in a
`let` binding:

```clojure
(let [real-source (base-source-fn ns-sym)
      base-source (or real-source (reconstruct-ns-source ns-sym))
      missing?   (nil? real-source)
      …]
  …)
```

### Step 3 — `analyze_test.clj`: Test helper resolution in reconstructed ns

Add a test case to `test-analyze-session-rules--reconstructed-ns-fallback` (or
a new deftest) that:

1. Creates an eval'd namespace with a helper function (`->fact`) that builds a
   map and a rule whose RHS calls `(insert! (->fact …))`.
2. Runs the full analysis pipeline (`analyze-session-rules` →
   `generate-annotations-from-analysis` with a `:fact-constructors` spec).
3. Asserts:
   - The synthesized source contains `(declare ->fact)` (or whatever the
     helper is named).
   - The annotation has a resolved callsite with a non-nil `:constructor-sym`
     and `:callsite-id` containing a constructor segment (not `:-:`).
   - The dynamic-insert-types-detected `:resolution` is `:full` (or at least
     not `:none`).

Pattern from existing code: the eval'd namespace test at line 684 of
`analyze_test.clj` already does `create-ns` + `eval` to get a namespace with
no classpath source. Extend the same pattern but add a helper function.

### Step 4 — Verify

- `cd server && make test` — full test suite.
- `cd server && make lint` — clj-kondo static analysis.
- `cd server && make format-check` — formatting.
- Visual inspection of synthesized source for the eval'd namespace case.

## Files Changed

| File                                                    | Change                                                                                                          |
| ------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `server/src/clara/server/tools/graph/analyze/synth.clj` | Add `non-production-interns` helper; modify `synthesize-ns-source` to emit `(declare …)` on reconstruction path |
| `server/test/clara/server/tools/graph/analyze_test.clj` | Add test for helper resolution in reconstructed ns with `:fact-constructors`                                    |

## Risks / Edge Cases

1. **Empty `ns-interns` beyond productions.** Many namespaces have no extra
   helpers. The `declare` line is simply omitted when the list is empty. No
   overhead, no change to existing behavior.
2. **`declare` of a var that kondo already knows from elsewhere.** The
   reconstructed ns may `:refer` the same symbol from another namespace.
   `declare` takes precedence in the local ns — kondo resolves the local.
   This matches reality: the live ns _does_ have the local var.
3. **Performance.** `ns-interns` is O(n) and called once per namespace in the
   analysis path. Trivial.
4. **Macros.** If a namespace interns a macro, `declare` will treat it as a
   var. Macros called from a rule RHS will resolve as var-usages, not
   macro-usages. This is fine — we only need callee attribution, not
   macro-expansion fidelity.

## Progress

- [x] Step 1 — `non-production-interns` helper added to `synth.clj`
- [x] Step 2 — `synthesize-ns-source` modified to emit `(declare …)` on
      reconstruction path, using `real-source` binding to avoid double-calling
      `base-source-fn`
- [x] Step 3 — Two test cases added to
      `test-analyze-session-rules--reconstructed-ns-fallback`:
  - "reconstructed ns source emits declare for interned helpers" — verifies the
    synthesized source string contains `(declare ->fact)` and places it before
    synthetic snippet defs
  - "reconstructed ns: interned helper resolves via :fact-constructors" —
    end-to-end test: eval'd ns with helper → full analysis pipeline → asserts
    `:resolution :full`, `:constructor-sym` present, and `:callsite-id` contains
    `:->fact:` segment
- [x] Step 4 — Full verification passes:
  - `make test`: 190 tests, 1328 assertions, 0 failures, 0 errors
  - `make lint`: 0 errors, 0 warnings
  - `make format-check`: All source files formatted correctly
