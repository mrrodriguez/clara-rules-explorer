Verified — the server-side findings are properly addressed. I re-checked each one against the last two commits, exercised the fixes in the REPL, and ran the full gates: **157 tests / 1125 assertions green, lint/format/reflection clean** (integration tests included in the run).

## Verdict per finding

**§1 NPE in `resolve-type` var branch — fixed, verified.** The `ns-name` → `prod-ns` rename eliminates the shadowing. `(resolve-type 'diverge.a 'Foo)` now returns `"diverge.a/Foo"` instead of NPE-ing. Regression tests cover var-resolution, cross-ns var-resolution, and the unresolvable-ns `symbol[...]` fallback. Bonus: map fact types now serialize kind-explicitly via `pr-str` (new branch + tests).

**§2 Phantom string types + dishonest `known` — fixed at the root, verified end-to-end.** The enrichment redesign (`:fact-raw-types` index on the snapshot, stripped at serve; raw objects merged into `:clara-rules/insert-types`, strings only in the display channel) eliminates the phantom string-kinded fact types entirely. Against the demo session pipeline (props layer + sidecar + session enrichment): **zero** string-kinded fact types remain, and `session-snapshot`'s new two-arity with the analysis known-set gives honest flags — all 18 demo session facts `known: true` _because they're actually in the analysis_, and a root-inserted `java.util.Date` (no rule touches it) correctly gets `known: false` with an id absent from the analysis index. The old `memory_test` assertion pinning "session facts are always known" is gone, replaced by `test-session-fact-known-parity`. The `get-snapshot` known-set invalidation is self-healing and the docstring is honest about the contract.

**§6 Loud total failure on divergence — fixed, verified.** Warn-and-keep-first with a full `rulebase-analysis` still building. The semantics are now coherent _and tested_: the canonical serialization gets ancestors; the divergent alias still surfaces as a linkable fact type with empty ancestors. Localized degradation, exactly what an explorer tool wants.

**§9 `catch Throwable` → `catch Exception` — fixed.**

**§10 Hash-order first-registration — fixed, verified.** `build-ancestors-index` now iterates `productions` in load order; I confirmed reversing production order deterministically flips which serialization is kept. No more hash-order arbitrariness.

**§11 Docstring drift — fixed** (both reverse indexes now mentioned).

**Testing gap (server half of §4) — addressed.** The `smoke_test` → `integration_test` rewrite runs real HTTP tests against _both_ canonical sessions, including hierarchy ancestors, tuple kind-explicitness, the `:match` bridge, and session known-flag parity end-to-end. The ns docstring explicitly documents that demo data is built from the non-hierarchy session only.

## Remaining nits (non-blocking)

- `warn-serialization-divergence!` uses `println` and a `volatile!`+`transient` `conj!` idiom — functional and correct, but a plain atom or a logging call would be more conventional.
- **Demo data is now stale**: `ui/static/demo-data` was scraped from the old string-enrichment behavior and still contains the phantom `"clojure.lang.Symbol"` string types and mismatched session ids. It needs a `pnpm scrape:demo` rerun against the fixed server — presumably part of the in-flight UI work, but worth making explicit since the checked-in data currently misrepresents the server contract.
- Pre-first-build session requests serve `known: false` snapshots until the analysis cache fills (self-heals on the next request once the known-set changes). Reasonable; just noting the transient.
- I see my review was committed as `docs/impl-review-kimi-1.md` — given the existing `plan-review-*` convention in this repo, I retract the hygiene half of my §12 comment; that's clearly the established practice here.

The fixes are high quality — particularly the decision to fix §2 at the enrichment boundary (raw types throughout, serialize only at the edge) rather than patching the symptom with string normalization. That's the architecturally right call and it composes with the raw-values-internally invariant from the original design.
