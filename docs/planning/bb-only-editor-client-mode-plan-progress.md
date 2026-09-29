# BB-only editor client mode — implementation progress

Companion to [`bb-only-editor-client-mode-plan.md`](bb-only-editor-client-mode-plan.md).
Checked boxes are landed and verified (`make test` in `server/`); everything
else is the current understanding of what remains.

## Phase 0a — global-closure-only navigation (drop the scoped `:match` path)

Landed: `client.clj` no longer reads `:upstream`/`:downstream`/`:match` for
navigation. `match-via` / `dep->matching-target` / `deps->targets` deleted;
`lhs-navigate` / `rhs-navigate` answer from `global-producer-targets` /
`global-consumer-targets` via `scoped-producer-targets` /
`scoped-consumer-targets`. Full `make test` green, `make lint` + reflection-check clean.

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
      falls back to the raw token; `:caller-ns` still passed. Tier1 green,
      byte-compile clean.
- [x] Neovim: `conjure.resolve_token` over `eval-str`, wired into
      `init.lua` `M.navigate` (one extra nested eval); same fallback. Suite
      green (all five files).
- [x] JVM `resolve-token` kept as back-compat escape hatch (untouched paths).
- [x] Parity pinned: `test-editor-resolve-form` eval-roundtrips the built
      form (exact values, fixpoints, navigate-equivalence raw vs resolved);
      `test-editor-resolve-form-stays-in-sync` asserts all three reads agree.
- [x] Template is two slots (caller-ns, token) and refuses `::` resolution when
      the buffer ns is absent (falls back to the raw token, matching the JVM
      client's `unreadable` guard).
- [x] Live verification: navigating from an aliased symbol and an `X.`-style
      token in Emacs and Neovim against a running repl (verified manually).
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
`ctor/resolve-ctor-form` injection precedent. Full suite green (after the
Phase 0b pins) — delegation parity holds.

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
- [x] `shared.hierarchy` (transpose + two closures) — extracted from
      `artifacts.hierarchy`; `artifacts.hierarchy` now delegates
      `->descendants` / `ancestor-closure` / `descendant-closure` to it, and
      `rehydrate`'s transpose delegates to the same definition.
- [x] `shared.rehydrate` (four usage closures + `:downstream` transpose +
      reference expansion) — `serialize/route-id` ported (`slug` +
      `sha1-base36`); `rehydrate.clj` is now the JVM half (annotation
      restoration + `:slim` narrowing) delegating to `shared.rehydrate`.
      slim/rehydrate parity pins held.
- [x] `shared.selection` / `shared.compose` — assessed: `registry` is NOT
      bb-safe (transitively pulls `serialize` → `clara.rules.schema`), so the
      shared forms take an injected capabilities map (`:read-analysis` /
      `:assert-compatible!`) rather than `:require` `registry`. Landed with the
      Phase 3 multi-unit step, then re-scoped (see the re-scoping note).
- [x] `shared.tokens` gained `record-ctor-class-symbol` — the pure syntactic
      half of `ctor/resolve-record-type` (strip `->`/`map->`, hyphen→underscore
      on the ns, no class-load). Pinned against `ctor/resolve-record-type` for
      the fixture record ctors; the bb `resolve-token` uses it for RHS ctor
      tokens.
- [x] `client.clj` (Phase 1 slice 1) + `rehydrate.clj` (this slice) delegate;
      existing `client`/`rehydrate`/`slim` tests pin parity, no behavior change.
- [x] End-to-end bb smoke (scratch, not committed): built the slim analysis
      from the checked-in `loan-app-ruleset` parts, `shared-rehydrate/rehydrate-analysis`
      produced correct `:id`/`:inserted-by-rules`/`:used-by-rules`, and
      `shared-navigate/navigate` answered LHS/global/RHS (with record-ctor
      normalization) over the rehydrated map. Confirms the Phase 3 single-unit
      core works under bb.

## Phase 2 — `bootstrap.bb` + bb smoke test

- [x] `server/bin/bootstrap.bb` (`server/src` + schema version from `deps.edn`).
- [x] `server/bin/bb_shared_smoke_test.bb` discovers every
      `:clara-rules-explorer/bb-loaded` ns under `server/src` and `require`s
      each under bb; wired as `make bb-smoke-test`. All three current shared
      namespaces load.

## Phase 3 — `editor_client.bb`

- [x] Single-unit `editor_client.bb` landed: `bb bin/editor_client.bb
      '<selection-edn>' '<navigate-input-edn>'` reads the four parts
      (`production-index` / `fact-types` / `dep-graph` / `meta`), rehydrates
      via `shared.rehydrate`, and answers `shared.navigate` with a pure bb
      runtime (keyword / string / record-ctor normalization; `:var? false`
      sources). Verified over the checked-in `loan-app-ruleset` unit
      (LHS / RHS record-ctor / global / error).
- [x] Multi-unit via the shared selection + compose namespaces, each taking an
      injected capabilities map (`:read-analysis` / `:assert-compatible!`).
      `editor_client.bb` always composes the selection (single and multi-unit
      alike, mirroring the server's `:registry` mode). `compose.clj` keeps only
      the `:layers` fold half; the `:compose` half delegates to the shared
      compose. `:written-by` stays
      `clara.server.tools.graph.artifacts.compose` so the persisted composed
      example is byte-for-byte unchanged (`regen-example-test` still green).
- [x] Parity verified against the checked-in example registry: new
      `editor-client-bb-test` composes the two `rules-annos/` source units in
      bb and asserts the producer/consumer target name-sets equal the JVM
      `compose/->composed-analysis` + `rehydrate/rehydrate-analysis` closures,
      that the cross-unit producer edge is reachable from the downstream unit,
      that a single-unit record-ctor token normalizes to its class type, and
      that the answers agree with the `producers`/`consumers` subcommands over
      the composed unit. Full suite green.

## Phase 4 — Emacs transport / Phase 5 — neovim transport

- [x] Emacs bb transport landed: `clara-explorer-transport` defcustom (nREPL
      default), `clara-explorer--bb-eval` (shells out to `bb editor_client.bb`,
      parses EDN), single-unit registry-selection prompt defaulting from
      `CLARA_RULES_EXPLORER_REGISTRY`, and `refresh`/`swap-session!` no-op in bb
      mode. `editor/emacs/editor_client.bb` symlinks to the script. Tier1 green,
      byte-compile clean.
- [x] Neovim bb transport landed: `g:clara_explorer_transport` ("nrepl" default,
      "bb" alternate), `conjure.bb_eval` (shells out via `vim.system` to
      `bb editor_client.bb`, parses EDN), single-unit registry-selection prompt
      (`g:clara_explorer_registry_root` / `CLARA_RULES_EXPLORER_REGISTRY`,
      `conjure.bb_select_unit` re-prompts), and `refresh`/`swap_session` no-op in
      bb mode. `editor/neovim/lua/clara-explorer/editor_client.bb` symlinks to the
      script; `:ClaraExplorerToggleTransport` / `:ClaraExplorerSelectUnit`
      commands added. `bb_transport_spec.lua` added; full suite green,
      format-check + lint clean.

## Phase 6 — smart transport default (bb by default, nREPL when an explorer server is running)

Landed (revised): the transport default is now `auto` in both editors. `auto`
resolves to nREPL only when the connected repl actually has a running explorer
system; otherwise it resolves to bb. Both modes still require a connected
repl — bb mode just has no explorer JVM/server on it. There is no "offline bb"
mode: without a repl, tokens cannot be resolved and namespaces cannot be
mapped to source files, so navigation cannot work.

- [x] Emacs: `clara-explorer-transport` default `'auto` (new choice alongside
      `'nrepl`/`'bb`); `clara-explorer--server-available-p` probes
      `clara.server.graph.client/get-current-system` via `requiring-resolve`
      (false when the explorer is absent from the classpath or no system is
      registered); `clara-explorer--effective-transport` uses that probe.
- [x] Emacs: `clara-explorer--navigate` keeps the hard "Not connected to a
      CIDER REPL" gate and always resolves the token over the repl, then
      dispatches on the probed transport.
- [x] Neovim: `conjure.transport()` default `"auto"`; new async
      `conjure.server_available(cb)` (probe + `server_available_cache`) and
      `conjure.with_transport(cb)`; `conjure.bb_transport_p()` is the
      synchronous best-effort read for toggle/select-unit (default bb until
      probed).
- [x] Neovim: `init.navigate`/`init.refresh`/`init.swap_session` resolve the
      transport via `conjure.with_transport`; navigate still gates on
      `conjure.connected()` and resolves the token first.
- [x] Transport visibility + full errors: Emacs `clara-explorer-transport-status`
      and Neovim `:ClaraExplorerTransportStatus` report configured/effective
      transport plus the explorer-server probe (both put effective first;
      Neovim opens a scratch buffer). Neovim `:ClaraExplorerLastError` opens
      the last full nREPL stack trace + code; `conjure.record_error` also
      appends the full trace to the Conjure log (Conjure's `eval-str` skips
      its own `display-result` when a `cb` is passed, so this was previously
      dropped).
- [x] Neovim nREPL transport fix: `eval_edn` no longer treats a plain
      `resp.err` (stderr, e.g. the INFO logs `client/navigate` writes) as a
      navigation failure. It now fails only on `resp.ex`/`root-ex` or the
      nREPL `eval-error` status, so the stderr message can't swallow the
      `value` message that follows and block producer/consumer jumps.
- [x] Tests: Emacs green (both Tier 1 and Tier 2), byte-compile clean; Neovim
      suite green. `stylua`/`selene` are not
      installed in this sandbox, so `make format-check lint` could not run.
- [x] Docs: `docs/explorer-editor-navigation-neovim.md` transport + command
      sections updated, and `docs/explorer-editor-navigation-emacs.md` command
      list extended.

Behavior notes:
- The discriminator is explorer-server availability, not nREPL-client
  connection: bb mode still has a connected CIDER/Conjure session (it just
  lacks the explorer JVM), so `cider-connected-p`/`conjure.connected()` is
  true in both modes and cannot choose between them.
- The probe is cheap: `requiring-resolve` fails fast when the explorer is not
  on the classpath, and returns nil when no system is registered.

## Notes / decisions while implementing

- `clj-nrepl-eval` is unusable in this sandbox (its bb bootstrapping writes to
  `~/.clojure/.cpcache`, which the sandbox denies), so verification is via
  `make test` (cognitect test-runner; `-n <ns>` / `-v <var>` to focus), not the
  running REPL on `:52909`.
- Schema is a deliberate shared dependency
  (`clara.server.graph.schema`, `clara.server.graph.navigate`):
  it loads under bb via `bootstrap.bb` and is enforced only at test time
  (`schema.test/validate-schemas`), with no explicit runtime `s/validate`.
- Re-scoping: the flat `clara.server.tools.graph.shared.*` prefix was replaced
  with domain-scoped shared namespaces that mirror where each piece came from —
  `clara.server.tools.graph.artifacts.hierarchy` /
  `.rehydrate` / `.selection` / `.compose` / `.registry`, and
  `clara.server.graph.navigate` / `.tokens` / `.schema`. Pure passthrough
  delegates (`artifacts.registry/unit-key` and `narrow-analysis`,
  `artifacts.compose/union-fact-types`, and the `artifacts.hierarchy` closure
  re-exports) were removed — callers require the shared namespace directly —
  while the value-adding JVM delegates (`selection/->selection`,
  `compose/->composed-analysis`, `rehydrate/rehydrate-analysis`, `client/navigate`)
  stay. The editor resolve-form resource moved beside `shared.tokens`
  (`resources/clara/server/graph/`), and the editor symlinks re-point.
- The neovim bb transport's `vim.system` `on_exit` callback runs in a LibUV
  "fast event" context where `nvim_win_is_valid` / `nvim_set_current_win` (and
  other window/buffer APIs) are forbidden. The callback body is therefore
  wrapped in `vim.schedule` so window restoration, `vim.notify`, the picker, and
  the jump path all run on the main loop.
- bb mode made `jump.goto_fallback` the default jump path (every bb target is
  `:var? false`), which exposed three latent bugs there: the nREPL `value` was
  never EDN-decoded (strings arrive quoted, so `open_resource` never matched),
  the resource form did not munge `-`→`_`, and it only did classpath
  `clojure.java.io/resource` (missing buffer-eval'd namespaces). `goto_fallback`
  now prefers the loaded var's `:file` metadata via `(resolve (symbol …))`, then
  a munged `.clj`/`.cljc` resource lookup, decodes the value with `edn`, and
  `open_resource` accepts plain absolute paths.
- Review follow-ups (post Phase 4/5):
  - Unit discovery moved into `editor_client.bb --list-units '<root>'`, so the
    registry layout has one owner. Both editors dropped their own
    `rules-inspect-manifest.edn` walk — elisp
    `clara-explorer--bb-list-unit-repos` and `conjure.bb_list_unit_repos` now
    shell out and decode the sorted EDN vector; a `--list-units` pin was added
    to `editor-client-bb-test`.
  - Elisp references in the Lua are qualified with the project-relative path
    (`editor/emacs/clara-explorer.el`'s `clara-explorer--…`), not a bare naming
    convention.
  - `jump.goto_fallback`'s source-location form is a single `SOURCE_LOC_FORM`
    format template (two `%s` slots: ns, fq name) instead of interleaved string
    concatenation, so the Clojure form reads as one block.

## Post-review fixes

Landed after the plan-review sweep; each is verified by the suite the section
it touched runs under.

- [x] `navigate-global` candidates are always a set: `(conj nil direct)` was
      producing a list, so an unknown keyword/string token threw a
      `ClassCastException` from `set/intersection` instead of reaching the clean
      "no fact type found" error. Pinned by `test-global-unknown-keyword-token-errors`.
- [x] `schema/SourceLoc`'s `:file`/`:line`/`:column` are optional keys — they only
      exist when `:var?` is true, so the bb runtime's `{:var? false}` source is
      now schema-valid.
- [x] Emacs Tier-1 stub fix: `test-helper.el`'s `(autoload …)` forms made the
      stubbed `nrepl-dict-get`/`parseedn-read-str` unreachable (`fboundp` was
      true for the autoload). Guards now use `test-helper--real-fn-p`, and the
      dict stub compares string keys with `equal`; both `make test` and
      `make test-tier1` are green.
- [x] `annotations_report.bb` migrated onto `bootstrap.bb` + the shared
      `hierarchy` namespace — the inline `->descendants` / `with-hierarchy`
      reimplementations and the `layout.cljc` symlink are gone, and `layout` is
      now `:clara-rules-explorer/bb-loaded true` (the smoke test requires 9
      namespaces).
- [x] Branches example: `make regen-artifacts` now persists a byte-identical
      copy of `loan-disposition-ruleset` under `branches/alt`, so the registry
      walk's `branches/` convention is pinned end-to-end. `editor_client.bb
      --list-units` returns `repo@branch` unit keys, and both editors split the
      `@` into `{:repo … :branch …}`; the JVM registry discovery and the bb
      list agree on the same branch label.
- [x] Doc-reference sweep: the stale `shared.*` / flat-prefix references in the
      editor resolve-form resource, `graph/schema.clj`, the elisp, and its test
      now resolve to `clara.server.graph.*`.
- [x] Root `AGENTS.md` lists the editor projects and points at `editor/AGENTS.md`,
      which documents the Emacs Tier-1/Tier-2 split that hid the stub failure.
