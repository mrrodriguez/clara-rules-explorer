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
- [ ] Open for Phase 1: `navigate-global` (production=nil) still serves raw
      closures *with* self and *without* dedupe — no production context there,
      so self is meaningless, but `shared.navigate` should settle one
      semantics (dedupe at minimum) when extracted.

## Phase 0b — editor-side token resolution

- [ ] Port `client/resolve-token` prefix-stripping into the shared resolve form
      (trailing `.`, `X/new`, `new X`, `->X`/`map->X` + `-`→`_`).
- [ ] Emacs: `clara-explorer--resolve-token` via CIDER eval, wired into
      `clara-explorer--navigate` before the navigate map is built; raw-token
      fallback on `nil`/error; `:caller-ns` still passed.
- [ ] Neovim: `resolve_token` helper over Conjure `eval-str` in `conjure.lua`,
      called from `init.lua` `M.navigate` (one extra nested eval).
- [ ] JVM `resolve-token` kept as back-compat escape hatch.

## Phase 1 — extract `shared.*`, JVM delegates (no bb yet)

- [ ] `shared.hierarchy` (transpose + two closures)
- [ ] `shared.rehydrate` (four usage closures + `:downstream` transpose)
- [ ] `shared.navigate` (pure `navigate` over a rehydrated analysis map)
- [ ] `shared.tokens` (normalization + callsite matching; `#?(:bb/:clj)` seam)
- [ ] `shared.selection` / `shared.compose`
- [ ] `client.clj` + `rehydrate.clj` delegate; existing
      `client`/`rehydrate`/`slim` tests pin parity, no behavior change.

## Phase 2 — `bootstrap.bb` + bb smoke test

- [ ] `server/bin/bootstrap.bb` (`server/src` + schema version from `deps.edn`).
- [ ] bb test `require`s every `shared.*` with no classpath beyond `src`;
      asserts the `:bb` branch is taken where conditionals exist.

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
