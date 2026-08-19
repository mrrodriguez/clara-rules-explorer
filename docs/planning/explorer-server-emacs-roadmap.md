# Explorer Server ↔ Emacs Navigation — Roadmap

Status: **Ready to start** · Plan: `docs/planning/explorer-server-emacs-plan.md`
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

- [ ] Add `server/current-system` (public, returns `@default-system`; keep
      `default-system` `^:private`) — server.clj
- [ ] Create `server/src/clara/server/graph/client.clj`:
  - [ ] `registered-system` atom + `register!` + `current-system`
        (registered → `server/current-system` fallback)
  - [ ] Prismatic schemas: `NavigateInput`, `SourceLoc`, `NavigateResult`
        (`schema.core`, matching `server.clj` conventions)
  - [ ] `{:error s/Str}` return contract for unknown production / no match /
        no registered system
- [ ] **Gate:** `make lint reflection-check` (`^Class` hint on `.getName`)

### 0.2 Server: `navigate` — LHS producer path (simplest first)

- [ ] Normalize `:production` fq string (mirror `ann/normalize-rule-name`)
- [ ] Pull warmed analysis via `cache/get-rulebase-analysis`
- [ ] Token resolution §7 steps 1–3 for `:lhs` (keyword / string / imported
      class / ctor symbol via **`analyze.ctor/resolve-record-type`** — do not
      hand-derive)
- [ ] Filter `:upstream` `:match` pairs on `consumer-type.name == T`; attach
      var-metadata `:source`; sort by fq name
- [ ] **Gate:** REPL smoke test —
      `(client/navigate {:production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?" :side :lhs :token "Application"})`
      returns the expected producer(s)

### 0.3 Server: `navigate` — RHS consumer path

- [ ] Anchor token against `:insert-types` ∪ `:retract-types`
- [ ] Callsite-aware resolution (§7.4): match fq ctor symbol against the
      production's serialized `:dynamic-insert-types-detected` /
      `:dynamic-retract-types-detected` `:constructor-sym` / `:fact-type`,
      read `:resolved-types`
- [ ] Filter `:downstream` `:match` pairs on `producer-type.name == T`;
      propagate `:via :insert|:retract`
- [ ] Outside-defrule global path: `{:production nil :caller-ns … :token …}`
      → resolve in live ns → `used-by-rules` / `used-by-queries`
- [ ] **Gate:** REPL smoke tests on `app-outcome-approved?` (record ctor) and
      `analyze-test-rules` `make-document-check` helper chain

### 0.4 Elisp: `editor/emacs/clara-explorer.el`

- [ ] File header: `Package-Requires: ((emacs "28.1") (cider "1.12")
      (parseedn "1.2") (clojure-mode "5.18"))` + `featurep` runtime guards +
      self-locating `load-path` (`load-file-name`)
- [ ] `clara-explorer--eval-edn` — sync eval via
      `cider-nrepl-sync-request:eval` with connection captured once via
      `(cider-current-repl 'infer 'ensure)`; form wrapped in
      `(do (require 'clara.server.graph.client) …)`; `parseedn-read-str` result
- [ ] `clara-explorer--edn-get` alist accessor
- [ ] Structural navigation:
  - [ ] `--enclosing-production` — alias-prefix-agnostic (`r/defrule` is the
        common case in the demo rules)
  - [ ] `--side-at-point` — sexp-aware depth-1 `=>` search; queries → `:lhs`
  - [ ] `--token-at-point` — `(cider-symbol-at-point 'look-back)`
  - [ ] `--context` — one-call gather (`:production`/`:kind`/`:side`/
        `:caller-ns`/`:token`)
- [ ] Commands `clara-explorer-navigate-producer` / `-consumer` (incl. query →
      "queries have no RHS" early return, outside-defrule global path)
- [ ] `clara-explorer--choose-target` (`completing-read`, `(retract)` suffix)
      and `clara-explorer--goto` (`cider-find-var` → §9.5 regex fallback)
- [ ] **No hard-coded paths/ports anywhere** —
      gate: `grep -R "~/Projects\|/Users/" editor/` is empty

### 0.5 Spike acceptance (manual)

- [ ] REPL: `(client/register! (server/start! {:session … :port 9999}))`
- [ ] Emacs: open `editor/emacs/clara-explorer.el`, `M-x eval-buffer`
- [ ] Cursor on `Application` in `app-outcome-pending?` LHS →
      `M-x clara-explorer-navigate-producer` jumps to producer(s)
- [ ] Cursor on `map->ApplicationOutcome` in `app-outcome-approved?` RHS →
      `M-x clara-explorer-navigate-consumer` shows fq-name popover / jumps
- [ ] Multi-target case shows sorted fq names with `(retract)` suffixes
- [ ] `::kw`, alias-prefixed, and helper-ctor tokens resolve; no-match and
      not-inside-a-rule cases message cleanly
- [ ] Works with two REPLs connected (targets the buffer's REPL)

---

## Phase 1 — Solidify

Goal: tested, refreshable, durably installable.

- [ ] `server/test/clara/server/graph/client_test.clj` against
      `loan-app-rules` + `analyze-test-rules`:
  - [ ] single-target direct result; multi-target deterministic ordering;
        zero-target error
  - [ ] `:via :retract` flag on retract-coupled targets
  - [ ] alias / `:refer` / `::keyword` resolution
  - [ ] callsite-linked ctor (`map->DocumentCheck` via `make-document-check`)
  - [ ] outside-defrule global-consumer path (`:production nil` + `:caller-ns`)
- [ ] **Gate:** `make test lint reflection-check`
- [ ] `clara-explorer-refresh` → `server/reload-annotations!` (§9.9)
- [ ] `clara-explorer-swap-session` → `server/swap-session!` (prompts for the
      session-rebuild expr, caches last per connection; prefix arg re-prompts)
- [ ] Manual check of the §5.2 staleness matrix:
      re-eval a rule → nav unchanged → rebuild + swap → nav updated;
      edit rule file w/o re-eval → refresh → nav updated
- [ ] Optional Spacemacs layer skeleton at `editor/emacs/spacemacs-layer/`
      (`packages.el` deps, `config.el` `clara-explorer-root` defcustom,
      `keybindings.el` `g p`/`g c`/`g r` scoped to `clojure-mode`,
      `funcs.el`)
- [ ] README snippet: both install paths (spike `eval-buffer` / layer var),
      REPL bootstrap, refresh workflow. No absolute paths in examples.
- [ ] Update plan status to **Implemented (phase 1)**

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

---

## Standing gates (every phase)

| Surface | Gate |
| --- | --- |
| Server change | `cd server && make test lint reflection-check` |
| Elisp change | `M-x eval-buffer` + manual nav against demo rules |
| Portability | `grep -R "~/Projects\|/Users/" editor/` empty |
| API contract | none — no HTTP changes allowed in this work |
