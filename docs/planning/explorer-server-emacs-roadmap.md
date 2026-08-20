# Explorer Server ↔ Emacs Navigation — Roadmap

Status: **Phase 0 + Phase 1 implemented** (Phase 0.5 manual Emacs acceptance
pending user verification; Phase 1 Elisp tiered tests implemented) · Plan: `docs/planning/explorer-server-emacs-plan.md`
(review 1 applied)

This is the executable checklist for the plan. Work top to bottom; every box
names its verification gate. Server work is always verified **before** the
matching elisp work (`cd server && make test lint reflection-check`).

---

## Phase 0 — Spike: prove the semantics

Goal: `M-x clara-explorer-navigate-producer` and
`M-x clara-explorer-navigate-consumer` work against the demo rules from a live
CIDER REPL. No config changes, no keybindings, no tests yet — manual
verification only.

### 0.1 Server: client-API skeleton

- [x] Add `server/get-current-system` (public, returns `@default-system`; keep
      `default-system` `^:private`) — server.clj
- [x] Create `server/src/clara/server/graph/client.clj`:
  - [x] `registered-system` atom + `register!` + `get-current-system`
        (registered → `server/get-current-system` fallback)
  - [x] Prismatic schemas: `NavigateInput`, `SourceLoc`, `NavigateTarget`,
        `NavigateResult` (`schema.core`, matching `server.clj` conventions)
  - [x] `{:error s/Str}` return contract for unknown production / no match /
        no registered system
- [x] **Gate:** `make lint reflection-check` (`^Class` hint on `.getName`)

### 0.2 Server: `navigate` — LHS producer path (simplest first)

- [x] Normalize `:production` fq string (enforced by `NavigateInput`
      `s/Str` validation; strings pass through `ann/normalize-rule-name`
      unchanged)
- [x] Pull warmed analysis via `cache/get-rulebase-analysis`
- [x] Token resolution §7 steps 1–3 for `:lhs` (keyword / string / imported
      class / ctor symbol via **`analyze.ctor/resolve-record-type`** — do not
      hand-derive)
- [x] Filter `:upstream` `:match` pairs on `consumer-type.name == T`; attach
      var-metadata `:source`; sort by fq name
- [x] **Gate:** REPL smoke test —
      `(client/navigate {:production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?" :side :lhs :token "DocumentCheck"})`
      returns `app-has-all-required-docs`

### 0.3 Server: `navigate` — RHS consumer path

- [x] Anchor token against `:insert-types` ∪ `:retract-types`
- [x] Callsite-aware resolution (§7.4): match fq ctor symbol against the
      production's serialized `:dynamic-insert-types-detected` /
      `:dynamic-retract-types-detected` `:constructor-sym` / `:fact-type`,
      read `:resolved-types`
- [x] Filter `:downstream` `:match` pairs on `producer-type.name == T`;
      propagate `:via :insert|:retract`
- [x] Outside-defrule global path: `{:production nil :caller-ns … :token …}`
      → resolve in live ns → `used-by-rules` / `used-by-queries`
- [x] **Gate:** REPL smoke tests on `app-outcome-approved?` (record ctor),
      `rule-retract-java-dot` (`:via :retract`), and `analyze-test-rules`
      `make-document-check` helper chain (global path)

### 0.4 Elisp: `editor/emacs/clara-explorer.el`

- [x] File header: `Package-Requires: ((emacs "28.1") (cider "1.12")
      (parseedn "1.2") (clojure-mode "5.18"))` + `Version: 0.1.0` + `featurep` runtime guards
      (now `or (featurep ...) (require ...)` for Eldev) + self-locating `load-path` (`load-file-name`)
- [x] `clara-explorer--eval-edn` — sync eval via
      `cider-nrepl-sync-request:eval` with connection captured once via
      `(cider-current-repl 'infer 'ensure)`; form wrapped in
      `(do (require 'clara.server.graph.client) …)`; `parseedn-read-str` result
- [x] `clara-explorer--edn-get` accessor (hash-table + alist — parseedn
      20231203 returns hash-tables for maps, not alists)
- [x] Structural navigation:
  - [x] `--enclosing-production` — alias-prefix-agnostic (`r/defrule` is the
        common case in the demo rules)
  - [x] `--side-at-point` — sexp-aware depth-1 `=>` search; queries → `:lhs`
  - [x] `--token-at-point` — `(cider-symbol-at-point 'look-back)`
  - [x] `--context` — one-call gather (`:production`/":kind"/":side"/
        `:caller-ns`/":token")
- [x] Commands `clara-explorer-navigate-producer` / `-consumer` (incl. query →
      "queries have no RHS" early return, outside-defrule global path)
- [x] `clara-explorer--choose-target` (`completing-read`, `(retract)` suffix)
      and `clara-explorer--goto` (`cider-find-var` → §9.5 regex fallback)
- [x] **No hard-coded paths/ports anywhere** —
      gate: `grep -R "~/Projects\|/Users/" editor/` is empty

### 0.5 Spike acceptance (manual)

- [ ] REPL: `(client/register! (server/start! {:session … :port 9999}))`
- [ ] Emacs: open `editor/emacs/clara-explorer.el`, `M-x eval-buffer`
- [ ] Cursor on `DocumentCheck` in `app-outcome-approved?` LHS →
      `M-x clara-explorer-navigate-producer` jumps to
      `app-has-all-required-docs` (and `Application` on any LHS messages
      "no producer" — it is a source fact type)
- [ ] Cursor on `map->ApplicationOutcome` in `app-outcome-approved?` RHS →
      `M-x clara-explorer-navigate-consumer` shows fq-name popover / jumps
- [ ] Multi-target case shows sorted fq names with `(retract)` suffixes
- [ ] `::kw`, alias-prefixed, and helper-ctor tokens resolve; no-match and
      not-inside-a-rule cases message cleanly
- [ ] Works with two REPLs connected (targets the buffer's REPL)

---

## Phase 1 — Solidify

Goal: tested, refreshable, durably installable.

- [x] `server/test/clara/server/graph/client_test.clj` against
      `loan-app-rules` + `analyze-test-rules`:
  - [x] single-target direct result; multi-target deterministic ordering;
        zero-target error
  - [x] `:via :retract` flag on retract-coupled targets
  - [x] alias / `:refer` / `::keyword` resolution
  - [x] callsite-linked ctor (`map->DocumentCheck` via `make-document-check`)
  - [x] outside-defrule global-consumer path (`:production nil` + `:caller-ns`)
- [x] **Gate:** `make test lint reflection-check`
- [x] `clara-explorer-refresh` → `server/reload-annotations!` (§9.9)
- [x] `clara-explorer-swap-session` → `server/swap-session!` (prompts for the
      session-rebuild expr, caches last per connection; prefix arg re-prompts)
- [x] §5.2 staleness matrix — server-side covered by
      `test-session-swap-reflected-in-navigation` + existing `server_test`
      reload coverage; the full manual Emacs flow remains part of 0.5 acceptance
- [x] Elisp tiered tests — `editor/emacs/test/clara-explorer-test.el` (43 ERT, see
      `docs/planning/explorer-server-emacs-testing.md` + `docs/explorer-editor-navigation.md#Testing`):
  - [x] **Tier 1 — stubbed unit** (`make -C editor/emacs test-tier1` / fallback `make test-unit`): 38 passed + 5 skipped, <1s, no network/JVM, `test/test-helper.el` `provide` stubs + minimal `clojure-mode` syntax table + tiny `nrepl-dict-get`/`parseedn-read-str` stubs, `with-clara-buffer` prefers real `clojure-mode` when `fboundp` else `emacs-lisp-mode`
  - [x] **Tier 2 — real-deps unit** (`make eldev-prepare && make eldev-test` / `eldev test`): same 38 + 5 now 0 skipped, `editor/emacs/Eldev` `:main-file`/`:package` + `(eldev-use-package-archive 'melpa) (eldev-use-package-archive 'gnu)` + `Version: 0.1.0` header (required for `package-buffer-info`), `.eldev/` gitignored (`make clean` wipes `server/target/elisp-check` + `.eldev/`), `test-helper--real-deps-p`/`--parseedn-real-p` + `test-helper--report-tier` banner (`Tier 1 — 5 skipped — hint: run eldev test`), `clara-explorer.el` guards now `or (featurep ...) (require ...)` and `test-helper` `unless (or (featurep ...) (require ...))` so Eldev’s real `cider`/`parseedn`/`clojure-mode` win, order `(require 'test-helper) (require 'clara-explorer)` + load-path shim for `test-helper` under Eldev. Covers `--edn-map→parseedn-read-str` hash-table/`vector` contract, `--eval-edn`+`nrepl-dict` wiring, `cider-symbol-at-point` `::`/`:kw`, real `syntax-ppss` for comments/reader macros
  - [x] **Tier 3 — live nREPL integration (deferred)** — full `client/navigate` e2e via `cider-nrepl-sync-request:eval` against live `server` (ctor, `:via :retract`, global `{:production nil}`), reserved for future `make eldev-prepare && eldev test --integration` / `test-integration` target; not required for CI (see testing doc)
  - [x] **Gate:** `make -C editor/emacs test-tier1` (CI, stubbed) and `make eldev-prepare && make -C editor/emacs eldev-test` (local, real deps) + `make check-elisp` (byte-compile vs stubs) — `--verbose` removed (Eldev 1.11 uses `-v` global)
- [x] Optional Spacemacs layer skeleton at `editor/emacs/spacemacs-layer/`
      (`packages.el` deps, `config.el` `clara-explorer-root` defcustom,
      `keybindings.el` `g p`/`g c`/`g r` scoped to `clojure-mode`,
      `funcs.el`) — durable install deferred per user (eval-buffer during spike)
- [x] README snippet: both install paths (spike `eval-buffer` / layer var),
      REPL bootstrap, refresh workflow. No absolute paths in examples.
- [x] Update plan status to **Implemented (phase 1)** — now includes tiered Elisp tests

---

## Phase 2 — Optional extensions (only on evidence)

- [ ] Kondo source-location tier (`analyze/->production-source-locations`,
      cache key `[::source ns-sym]` keyed by cache identity) — **only if** a
      real non-var production (`parse-rule`, `def-rules-test`, programmatic)
      fails `cider-find-var` in practice
- [ ] neovim client (Conjure) against the same `client/navigate` EDN surface
      (`editor/neovim/`)
- [ ] Revisit LSP adapter (plan §13) if maintaining elisp + Lua costs more
      than a one-time language-server bridge
- [ ] HTTP endpoints (`POST /v1/navigate` → `client/navigate`) — **only if**
      remote-server support becomes a goal (currently out of scope)
- [ ] Live nREPL integration suite (`make test-integration` → headless `server` + `cider-connect-clj`) — deferred from Phase 1 Tier 3; promote to Phase 2 when Tier 2 stable on multiple machines (see testing doc)

---

## Standing gates (every phase)

| Surface | Gate |
| --- | --- |
| Server change | `cd server && make test lint reflection-check` |
| Elisp change | `make -C editor/emacs test-tier1` (Tier 1 stubbed, CI) + `make -C editor/emacs eldev-prepare && make -C editor/emacs eldev-test` (Tier 2 real deps, local; 0 skipped) + `make check-elisp` (automated byte-compile vs stubs) + `M-x eval-buffer` manual nav; `.eldev/` ignored (`make clean` wipes) |
| Portability | `grep -R "~/Projects\|/Users/" editor/` empty; `clara-explorer-root` var only, no hard-coded paths |
| API contract | none — no HTTP changes allowed in this work |
