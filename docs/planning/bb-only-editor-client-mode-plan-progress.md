# BB-only editor client mode — implementation progress

Companion to [`bb-only-editor-client-mode-plan.md`](bb-only-editor-client-mode-plan.md).
Checked boxes are landed and verified (`make test` in `server/`); everything
else is the current understanding of what remains.

## Phase 0a — global-closure-only navigation (drop the scoped `:match` path)

Landed: `client.clj` no longer reads `:upstream`/`:downstream`/`:match` for
navigation. `match-via` / `dep->matching-target` / `deps->targets` deleted;
`lhs-navigate` / `rhs-navigate` answer from `global-producer-targets` /
`global-consumer-targets` via `scoped-producer-targets` /
`scoped-consumer-targets`. Full `make test` green (401 tests, 2504
assertions), `make lint` + reflection-check clean.

- [x] Parity probe: `scoped ≡ global` over every `client-test` fixture.
      Findings (all three fixtures, incl. keyword/tuple hierarchy):
      - Declared LHS/RHS types are always `:fact-types` keys — the global
        lookup's precondition holds, no exceptions needed.
      - LHS: scoped ≡ global **minus self**. Both dep-graph builders refuse
        producer = consumer (`core.clj`, `compose.clj`), so the scoped path
        could never return the querying production; the closure can (a rule
        that consumes what it produces). `exclude-querying-production` closes it — principled,
        not fixture-accidental.
      - RHS: same self story, plus the predicted `:via` gap — scoped tags each
        consumer `:retract` iff the matched type is in the *current*
        production's retract set (`matching-type-pairs` `:via`); the raw
        global consumer closure tags `:insert`. Fixed with per-type tagging +
        retract-wins merge (= `match-via`'s `some`).
      - No same-name insert+retract dup rows observed in any fixture, but
        `dedupe-targets` (retract-wins) normalizes the overlap case anyway —
        the scoped path answered one row there, never two.
- [x] Probe removed after green (it referenced the deleted fns); kept as
      `test-scoped-navigation-excludes-self` through the public `navigate`
      API (self-looping `app-outcome-denied?`, both sides).
- [x] Existing pins held without edits: `test-consumer-retract-via-flag`,
      producer/consumer/global/callsite tests.
- [x] `navigate-global` settled during Phase 1 extraction: it now dedupes with
      retract-wins like the scoped closures (raw self stays irrelevant with
      `production=nil`, but same-name duplicates across matched types are
      collapsed).

## Phase 0b — editor-side token resolution

Landed. Verified headlessly on all three sides; the live repl round trip
(CIDER/Conjure against a real project) still wants a click-through in your
editors.

Single source of truth: `server/resources/.../shared/editor-resolve-form.clj`
holds the canonical template text. It is read as a string at runtime
everywhere — `shared.tokens/editor-token-resolve-form` slurps it via
`io/resource` and fills the two slots (caller-ns, token), and both editors
slurp it at load through symlinks beside their own files
(`editor/emacs/`, `editor/neovim/lua/clara-explorer/`). No embedded copies
remain, so there is nothing to drift; the sync test fails if any of the three
reads ever disagree. (Rationale recorded: runtime sharing is impossible —
separate installs, and nREPL-fetch would violate the no-explorer-dep
constraint — so file-sharing is the strongest available mechanism. The
repo-relative symlinks must be materialized by each editor's package/plugin
build step into the package-local directory before release.)

- [x] Port `client/resolve-token` prefix-stripping into the shared resolve form.
      `shared.tokens/normalize-ctor-target` (`X.` → `X`, `X/new` → `X`,
      ctors/plain pass through); `client/resolve-ctor-token` refactored onto
      it with identical behavior.
- [x] Emacs: `clara-explorer--resolve-token` via CIDER sync eval, wired into
      `clara-explorer--navigate` before the navigate map is built; nil/error
      falls back to the raw token; `:caller-ns` still passed. Tier1 green
      (84 tests), byte-compile clean.
- [x] Neovim: `conjure.resolve_token` over `eval-str`, wired into
      `init.lua` `M.navigate` (one extra nested eval); same fallback. Suite
      green (all five files, 0 failures/errors).
- [x] JVM `resolve-token` kept as back-compat escape hatch (untouched paths).
- [x] Parity pinned: `test-editor-resolve-form` eval-roundtrips the built
      form (exact values, fixpoints, navigate-equivalence raw vs resolved);
      `test-editor-resolve-form-stays-in-sync` asserts all three reads agree.
- [x] Template is two slots (caller-ns, token) and refuses `::` resolution when
      the buffer ns is absent (falls back to the raw token, matching the JVM
      client's `unreadable` guard).
- [ ] Live verification: navigate from an aliased symbol and an `X.`-style
      token in Emacs and Neovim against a running repl.
- Note: `Class/.getName` / `String/.endsWith` / `String/.startsWith` call-site
  syntax adopted in the template + `client.clj`. Pre-existing `^Class` hints
  elsewhere (e.g. `serialize`, `ctor`) left for a later sweep.
- Note: shared namespaces are plain `.clj` (no reader conditionals yet), so
  kondo's `:cljs` analysis of `slurp`/`format` is not in play; `.cljc` is only
  needed once a reader conditional lands. The template's `::` branch refuses
  to resolve when the buffer ns is absent, mirroring `client/read-token`.
## Phase 1 — extract `shared.*`, JVM delegates (no bb yet)

Slice 1 landed: `shared.tokens` + `shared.schema` + `shared.navigate` (plain
`.clj`; `shared.schema`/`shared.navigate` require `schema.core`, which bb
provisions via `bootstrap.bb`). `client.clj` is now the JVM shell
(system/swap, live-namespace resolvers, var-metadata sources) delegating to
`shared.navigate/navigate` with an injected `runtime` map
(`:resolve-token` / `:token->fq-sym` / `:production-source`), following the
`ctor/resolve-ctor-form` injection precedent. Full suite green (403 tests,
2521 assertions after the Phase 0b pins) — delegation parity holds.

- [x] `shared.tokens` (`real-type-name?`, `callsite-matches-token?` — the
      pure helpers; live `ns-resolve`/class-loading stays JVM-side, fq
      normalization arrives with the bb entry / 0b resolve form)
- [x] `shared.schema` — the navigate contract (`NavigateInput`, `SourceLoc`,
      `NavigateTarget`, `NavigateResult`, `NavigateError`,
      `NavigateResponse`, all moved verbatim out of `client.clj`) plus the new
      `NavigateRuntime` (`:resolve-token` / `:token->fq-sym` /
      `:production-source`, fn schemas via `s/=>`). Docstrings across
      `shared.navigate` name schemas instead of spelling shapes, per the
      `artifacts.schema` house rule. Verified under bb: `schema.core` 1.4.1
      loads via `add-deps`, `s/check` accepts plain-fn runtimes and rejects
      bad inputs, and `shared.navigate/navigate` runs end-to-end over a stub
      analysis with a schema-valid response (`bootstrap.bb` owns the
      provisioning). `client.clj` keeps `s/defn` annotations against
      `shared-schema/*`; the explicit runtime `(s/validate NavigateInput …)`
      was removed — schema enforcement is test-time only via the existing
      `validate-schemas` fixture.
- [x] `shared.navigate` (pure `navigate` over a rehydrated analysis map;
      `navigate-global` dedupes with retract-wins like the scoped closures)
- [ ] `shared.hierarchy` (transpose + two closures — note
      `artifacts.hierarchy` (no requires at all) and `rehydrate`'s private
      closure fns are parallel implementations to unify, not just move)
- [ ] `shared.rehydrate` (four usage closures + `:downstream` transpose —
      needs `serialize/route-id` ported or injected; `rehydrate` also pulls
      `annotations.merge` / `conditions` / `serialize`, none bb-safe)
- [ ] `shared.selection` / `shared.compose` (need `registry` / `store` —
      file I/O namespaces — assessed for bb-safety first)
- [ ] `client.clj` + `rehydrate.clj` delegate; existing
      `client`/`rehydrate`/`slim` tests pin parity, no behavior change.

## Phase 2 — `bootstrap.bb` + bb smoke test

- [x] `server/bin/bootstrap.bb` (`server/src` + schema version from `deps.edn`).
- [x] `server/bin/bb_shared_smoke_test.bb` discovers every
      `:clara-rules-explorer/bb-loaded` ns under `server/src` and `require`s
      each under bb; wired as `make bb-smoke-test`. All three current shared
      namespaces load.

## Phase 3 — `editor_client.bb`

- [ ] Single-unit selection first (reuse `annotations_report.bb`'s read path),
      then multi-unit via `shared.selection`/`shared.compose`.
- [ ] Verify against `producers`/`consumers` subcommands + `rehydrate` over the
      checked-in example registry.

## Phase 4 — Emacs transport / Phase 5 — neovim transport

- [ ] `clara-explorer--eval-bb` behind a defcustom; registry-selection prompt
      defaulting from `CLARA_RULES_EXPLORER_REGISTRY`; nREPL stays the default;
      `swap-session!`/`refresh` no-op in bb mode.
- [ ] Same in Lua (`conjure.lua` alternate executor).

## Notes / decisions while implementing

- `clj-nrepl-eval` is unusable in this sandbox (its bb bootstrapping writes to
  `~/.clojure/.cpcache`, which the sandbox denies), so verification is via
  `make test` (cognitect test-runner; `-n <ns>` / `-v <var>` to focus), not the
  running REPL on `:52909`.
- Schema is a deliberate `shared.*` dependency (`shared.schema`,
  `shared.navigate`): it loads under bb via `bootstrap.bb` and is enforced only
  at test time (`schema.test/validate-schemas`), with no explicit runtime
  `s/validate`.
