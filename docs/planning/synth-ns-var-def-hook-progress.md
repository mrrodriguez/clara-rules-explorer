# Synthesized-namespace var-definition hook — Progress

Tracks incremental progress on `synth-ns-var-def-hook-plan.md`.

## Status: implemented + verified

- [x] Work item 1: `VarDef` schema + `:var-defs-fn` support in `analyze.synth`
- [x] Work item 2: Convert `synthesize-ns-source` to an opts map
- [x] Work item 3: `:ns-var-defs-fn` on `analyze-session-rules` (exception containment, threaded through)
- [x] Work item 4: `tap>` reporting for skipped var defs
- [x] Work item 5: Docs (`analyze-session-rules` docstring, `analyze.synth` ns docstring, `server/docs/rule-annotations.md`)
- [x] Tests (plan §9)
- [x] Verify: `make test lint format-check reflection-check`

## Files changed

- `server/src/clara/server/tools/graph/analyze/synth.clj`
  - Added `schema.core` require; `VarDef` schema; `var-def-line` (print +
    round-trip validation + `tap>` skip reporting); `synth-var-defs`
    (exception containment, production-collision drop).
  - `synthesize-ns-source` now takes an opts map (`:ns-sym :productions
    :base-source-fn :normalize-key-fn :var-defs-fn`). Emits var defs between
    `(declare …)` and the rule snippets, and extends `:offset` over them.
- `server/src/clara/server/tools/graph/analyze.clj`
  - `analyze-session-rules` accepts `:ns-var-defs-fn`, threads it to
    `synth/synthesize-ns-source`, docstring updated.
- `server/test/clara/server/tools/graph/analyze_test.clj`
  - Updated the existing direct `synthesize-ns-source` call to the opts map.
  - Added tests: var-defs emission/offset/ordering, production-name
    collision, unreadable-literal skip, multiline skip, byte-identical
    no-hook/nil-hook/omitted-hook, single-hop end-to-end, two-hop
    `(def f (fn f …))` end-to-end.
- `server/docs/rule-annotations.md`
  - Added the `:ns-var-defs-fn` section under the extension points.

## Verification

```
make test              → 221 tests, 1500 assertions, 0 failures, 0 errors
make lint              → 0 errors, 0 warnings
make format-check      → all source files formatted correctly
make reflection-check  → passed (no warnings in project code)
```

## Notes

- clj-kondo attributes `:from-var` correctly for `(def f (fn f [x] …))`, so
  the two-hop helper test exercises the plan §9 requirement directly.
- `clojure.core/def` and bare `fn`/`let` are not resolvable in a raw
  `create-ns` namespace (no core refers); eval'd test fixtures use
  `clojure.core/fn`/qualified macros while the hook `:form` data keeps bare
  `def`/`fn` (which is what clj-kondo analyzes). This only affects the test
  fixtures, not the implementation.
