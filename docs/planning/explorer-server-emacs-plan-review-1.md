# Explorer Server ↔ Emacs Plan — Review 1

> Review of `explorer-server-emacs-plan.md` (Proposed). No edits to the plan itself — this file is the commentary. Author direction incorporated inline (see §0).

## 0. Direction applied

Per author:

* Must be **machine-agnostic** — no hard-coded `~/Projects/...`, `~/emacs-dotfiles/...`, absolute `:port 9999` assumptions that leak a single laptop. Must transfer across machines.
* Must **not require the editor to know where `clara-rules-explorer/server` lives on disk** — at nav time we have a live CIDER nREPL with the server already on the classpath. Nothing file-based about that.
* Clarify what a **Spacemacs layer** buys vs. a bare `load-file` in `dotspacemacs/user-config`.
* Explicit **`.el` dependencies** so the file can be declared as a package later and so a caller knows the preconditions to `load-file` it.
* **Reuse existing constructor→class helpers** (`clara.server.tools.graph.analyze.ctor`) — do not re-derive `->Foo`/`map->Foo`/`Foo.` logic.
* `read-string` unsafety is **contextual** (trusted token inside nREPL, not user-supplied file). Don't apply blanket `*read-eval* false` advice.
* **Symbol resolution is nREPL-namespace resolution**, not filename/row/col matching. We have a running REPL; `:constructor-sym` resolves against the ns it was found in.
* **Prismatic Schema, not Malli** — correct the schema guidance.
* **No file-watch / hot reload** — supply an explicit `clara-explorer-refresh` that calls the server's refresh helper; don't watch the filesystem.
* `editor/emacs/` is the correct home for the elisp — keep it. `load-file` findability is an integration detail, not a reason to move it.

---

## 1. Overall assessment

Plan is sound for a spike: ~150 LOC elisp glue + a small `clara.server.graph.client` namespace shared with a future Conjure client, semantics stays in Clojure, LSP deferred. The six risk areas are (a) portability, (b) nREPL/EDN plumbing assumptions, (c) fact-type resolution correctness, (d) structural navigation robustness, (e) server lifecycle/refresh contract, (f) dependency + packaging clarity. None block the spike; all should be tightened before merging.

Verified on this machine: `~/.spacemacs -> ~/emacs-dotfiles/.spacemacs`, `layers` includes `clojure` with `clojure-backend 'cider`, `cider-20260402.444` and `parseedn-20231203.1909` present in `elpa/29.4/develop/`, `cider-nrepl-sync-request:eval` and `parseedn-read-str` have the signatures the plan assumes, existing binding `, g f` = `cider-jump-to-containing-function` (`.spacemacs:865`). `server/default-system` is `^:private` with no public accessor — §5.1's `current-system` needs new code.

---

## 2. Portability — no machine-specific paths

**Gap:** §10 proposes

```elisp
(load-file "~/Projects/clara-rules-explorer/editor/emacs/clara-explorer.el")
```

in `dotspacemacs/user-config`. This bakes in (i) home dir, (ii) `Projects` vs `projects` vs `work`, (iii) repo name. It fails on a second laptop, CI, or a teammate checkout. It also implies the editor must know where the *server* Clojure source lives — which per your direction it must not.

**Why the editor doesn't need the server's disk location:** At nav time the editor talks to a live nREPL where `clara.server.graph.*` is already on the classpath (via `cider-jack-in` / `clojure -M`). The elisp never reads `server/src/...` off disk; it `eval`s Clojure over nREPL. The only file path the editor needs is the *elisp file itself*, and that is a property of the editor checkout, not the Clojure project.

**Fix:**

* For the **spike**: no `.spacemacs` change at all — `M-x load-file` with completion, or `M-x eval-buffer` on the file. Document that as the spike workflow and keep `dotspacemacs/user-config` out of the PR.
* For **durable install**: do not hard-code an absolute path. Two portable patterns:

  ```elisp
  ;; A: relative to the file itself — works when the file is loaded once
  (when load-file-name
    (add-to-list 'load-path (file-name-directory load-file-name)))

  ;; B: Spacemacs private layer (see §3) — layer variable points at the checkout
  ;; dotspacemacs-configuration-layers '(... (clara-explorer :variables clara-explorer-root "~/src/clara-rules-explorer"))
  ```

  Both transfer across machines because the path is supplied by the layer var or by `load-file-name`, not baked into the repo.

* Add a CI check: `grep -R "~/Projects\|/Users/mrrodriguez" editor/` must be empty.

---

## 3. What a Spacemacs layer buys (and what it doesn't)

A **layer** is Spacemacs' packaging unit for `dotspacemacs/user-config` sprawl. For this feature it replaces the single `load-file` line with a discoverable, toggleable unit.

**Without a layer (status quo):**

* Drop a `(load-file "...")` in `dotspacemacs/user-config`, hope `cider`/`parseedn` already loaded, no lazy loading, no `SPC` leader keys, no `describe-layer`.

**With `~/.emacs.d/private/clara-explorer/` (or `editor/emacs/spacemacs-layer/` contributed upstream):**

```
private/clara-explorer/
  packages.el   — declares :location (recipe :fetcher local) + dependencies
  config.el     — defcustom clara-explorer-root, keybinding prefix
  keybindings.el — spacemacs/set-leader-keys-for-major-mode 'clojure-mode "g p" ...
  funcs.el       — autoloads (wraps editor/emacs/clara-explorer.el via load-path)
```

Benefits:

1. **Dependency declaration** — `packages.el` lists `(cider parseedn clojure-mode)` in `clara-explorer-packages`; Spacemacs ensures they are installed before `config.el` runs. No ordering bug where `user-config` runs before `cider` lazy-loads.
2. **Toggle** — removing `clara-explorer` from `dotspacemacs-configuration-layers` fully unloads it; `user-config` requires manual editing.
3. **Per-mode keybindings** — `spacemacs/set-leader-keys-for-major-mode` scopes `g p`/`g c` to `clojure-mode` only, matching your existing `g f` pattern. Bare `global-set-key` pollutes every buffer.
4. **Load-path isolation** — layer's `load-path` addition is `(add-to-list 'load-path (expand-file-name "editor/emacs" clara-explorer-root))` — again, not machine-specific.

The layer does **not** change semantics; it is purely integration. For the spike, skip it. For the PR, ship `editor/emacs/clara-explorer.el` *plus* an optional `editor/emacs/spacemacs-layer/` skeleton and document both install paths.

---

## 4. Elisp dependencies — explicit contract

The plan says "no package dependency beyond what Spacemacs already loads (`cider`, `parseedn`, `dash`/`f`/`s`, `clojure-mode`, helm/ivy)" — that is too vague for a package header.

Proposed `Package-Requires` for `clara-explorer.el`:

```elisp
;; Package-Requires: ((emacs "28.1") (cider "1.12") (parseedn "1.2") (clojure-mode "5.18"))
;; Soft (runtime-checked, not hard-required): helm or ivy via `completing-read` polyfill; `sesman` via cider
```

Rationale:

* **Hard:** `cider` (for `cider-nrepl-sync-request:eval`, `cider-current-repl`, `cider-current-ns`, `cider-connected-p`, `cider-find-var`/`cider-find-ns`), `parseedn` (for `parseedn-read-str`), `clojure-mode` (syntax table + `clojure-find-ns`), `emacs 28.1` (Spacemacs baseline).
* **Not needed:** `dash`/`f`/`s` — nothing in the ~150 LOC needs them; avoid pulling them in. `helm`/`ivy` are already satisfied by Emacs' built-in `completing-read` (helm/ivy advise it). Don't `(require 'helm)`.
* **Runtime guard:** Top of file:

  ```elisp
  (unless (featurep 'cider) (user-error "clara-explorer requires cider"))
  (unless (featurep 'parseedn) (user-error "clara-explorer requires parseedn — add to dotspacemacs-additional-packages"))
  ```

This lets a future `MELPA` recipe or `straight` declaration be trivial, and tells a manual `load-file` caller exactly what must be present.

---

## 5. Constructor → class-name: reuse, don't replicate

Plan §7 step 2 proposes hand-deriving `->Foo`/`map->Foo`/`Foo.` → class names in the new `client` namespace. That logic already exists and is subtle (hyphen/underscore, `map->` prefix, `X.` vs `X/new` vs `new X`, imported class vs record ctor).

**Canonical helper:** `clara.server.tools.graph.analyze.ctor/resolve-record-type` and `resolve-ctor-form` (see `server/src/clara/server/tools/graph/analyze/ctor.clj:11-66`). It:

* Detects `constructor-fn-name?` (`map->`/`->` prefix)
* Resolves via live `ns-resolve` against `ns-sym`
* For `var?` with name `->X`/`map->X`, derives `fq-sym` as `ns "." X` with `-`→`_` and verifies the class actually loads (`resolvable-fact-class`)
* Handles `X.` / `new X` / `X/new` Java ctors

**Directive for `client/navigate`:** Do not duplicate. Call:

```clojure
(ctor/resolve-record-type caller-ns-sym ctor-sym)
;; or for full form:
(ctor/resolve-ctor-form memoized-resolve-fn caller-ns-sym arg-form)
```

`serialize/resolve-type` is the *serialization* layer for already-resolved tokens; `ctor/*` is the *resolution* layer for ctor vars. The nav code's RHS path (§7.4) should match `:constructor-sym` against the live ns via `ctor/resolve-record-type`, then read `:resolved-types` from the matched callsite.

---

## 6. `read-string` safety — context, not blanket rule

Previous inline note flagged `clojure.core/read-string` + `*ns*` binding as unsafe (`#=` eval). Author correctly notes: input is the token under point, eval'd *inside the nREPL* against a live, trusted session — not untrusted file content. The threat model is the user's own buffer, not a web form.

**Guidance (not a veto):**

* Prefer `clojure.edn/read-string` + explicit `::` handling when you *only* need EDN (keywords, symbols, strings). For `::kw` auto-resolve, read the token's `ns-alias` map from `(ns-aliases caller-ns)` and resolve `::alias/kw` manually — this avoids `read-string` entirely.
* If you do use `clojure.core/read-string` to get `::` auto-resolve for free, bind `*read-eval* false` and `*ns*` to `caller-ns`, and wrap in `try` — document that the token is trusted editor state, not persisted data. This is the same trust boundary as `cider-eval-last-sexp`.

Either is acceptable; don't cargo-cult `*read-eval* false` without noting the trust context.

---

## 7. Symbol resolution: nREPL ns, not file/row/col

Plan §9.7 and §7.4 lean on `:filename` + row/col to disambiguate global consumer lookups outside a `defrule`. Per your direction: **resolve against the live ns**.

At nav time we have `cider-current-ns` (the buffer's ns) and the callsite `:constructor-sym`. The fully-qualified symbol is:

```clojure
(ns-resolve (find-ns caller-ns-sym) ctor-sym) ; -> var/class
;; then derive fq class via ctor/resolve-record-type
```

Row/col is unnecessary for identity; it was only a fallback for `jar:` sources where `ns-resolve` fails. Keep the contract: nav input is `{:token "map->Foo" :caller-ns "my.app.rules"}`, client does `ns-resolve` inside the REPL, returns `:resolved-types`. Global consumer lookup is then a pure graph query (`used-by-rules` index), not a file grep.

Consequence for `client/navigate`'s outside-defrule path: accept `{:production nil :token "map->Foo" :caller-ns "..."}`, not `{:filename ... :row ...}`. The elisp already knows `cider-current-ns` cheaply.

---

## 8. Schema: Prismatic, not Malli

Previous inline note cited `m/schema` / `m/=>`. This project uses **Prismatic Schema** (`schema.core`), per `server/src/clara/server/graph/server.clj` and `AGENTS.md`.

For `clara.server.graph.client`:

```clojure
(ns clara.server.graph.client
  (:require [schema.core :as s]))

(s/defschema NavigateInput
  {(s/optional-key :production) (s/maybe s/Str)
   (s/optional-key :caller-ns) s/Str
   :token s/Str
   (s/optional-key :side) (s/enum :lhs :rhs)})

(s/defschema NavigateResult
  {:direction (s/enum :producer :consumer)
   :production s/Str
   :type s/Str
   :targets [{:name s/Str :ns s/Str :type s/Str :via (s/enum :insert :retract)
              :source {:var? s/Bool :file (s/maybe s/Str) :line (s/maybe s/Int) :column (s/maybe s/Int)}}]})

(s/defn navigate :- (s/either NavigateResult {:error s/Str})
  [{:keys [production token side caller-ns] :as m} :- NavigateInput]
  ...)
```

Validate at the choke point with `s/validate` (as `server.clj` does), keep `*warn-on-reflection* true` and run `make reflection-check` — `Class/.getName` needs `^Class` hint, as in `serialize.clj`.

---

## 9. Explicit refresh, not watch

You do not want file-watcher hot reload. The server already has the primitive:

* `server/reload-annotations!` — re-derives annotations from `(:annotations-spec state)` + current session, re-reads file-backed sources, rebuilds generated layer from cached per-ns kondo analyses (no full kondo re-run); then `cache/warm!`.
* `server/swap-session!` — for swapping the session itself.

Expose these as **explicit elisp commands**:

```elisp
(defun clara-explorer-refresh ()
  "Re-warm the explorer analysis for the current REPL session."
  (interactive)
  (unless (cider-connected-p) (user-error "Not connected"))
  (clara-explorer--eval-edn '(clara.server.graph.server/reload-annotations!))
  (message "clara-explorer: analysis refreshed"))

(defun clara-explorer-swap-session (session-sym)
  "Hot-swap the explorer session (advanced)."
  ...)
```

No `after-save-hook`, no filesystem watch, no `::source` cache-key invalidation on `file-mtime`. The user calls `M-x clara-explorer-refresh` (or `, g r` in the layer) when they have re-`eval`'d a rule. Document this as the workflow; optionally bind it in the layer's `keybindings.el`.

If a future kondo tier (§8 option A, non-var productions) is added, its per-ns analysis should be cached under `[::source ns-sym]` keyed by `cache` identity, not by file mtime — refresh via `reload-annotations!` is sufficient.

---

## 10. `editor/emacs/` placement — correct as planned

`editor/emacs/clara-explorer.el` is the right home. Top-level `editor/` keeps Emacs and future `editor/neovim/` peers together, outside `server/` and `ui/` per `AGENTS.md` separation. The `load-file` findability concern is an install detail (see §2–3), not a reason to move it into `server/resources` or `ui/`. If you want a Spacemacs layer skeleton, add it *alongside* as `editor/emacs/spacemacs-layer/` rather than moving the file.

Add to `.gitignore` nothing; the file is tracked. Ensure `server/Makefile` targets don't lint it (`clj-kondo` ignores `editor/`).

---

## 11. Remaining gaps to address before coding

Grouped by severity. Items marked **[S]** must be fixed in the spike; **[P]** can wait to phase 1.

**[S] Transport / EDN:**

* Lock the nREPL helper: `(cider-nrepl-sync-request:eval code (cider-current-repl 'infer 'ensure) ns)` — not `cider-current-connection` (no such fn). Capture repl once per command to avoid `sesman` cross-talk when multiple REPLs connected.
* Define the wire format: elisp sends `(clara.server.graph.client/navigate {:production ... :side ... :token ... :caller-ns ...})` via `prin1-to-string`/`format "%S"`, server returns `(pr-str result)` so `nrepl-dict "value"` is a parsable EDN string; elisp does `parseedn-read-str`. Document that `Class` never crosses the wire.
* Add timeout/error path: `nrepl-dict "err"`/`"ex"` → `message`, `{:error "..."}` from `navigate` → `message`, no popover.

**[S] Structural nav:**

* `=>` finder must be sexp-aware (depth-1 under `defrule`), not string search — use `clojure-mode` parse or `paredit-forward` walk. Queries have no `=>` → always `:lhs`; early-return for `:consumer` on query.
* `token-at-point` should reuse `cider-symbol-at-point 'look-back` (already in your `.spacemacs:799`) so `::kw`, `Foo.`, `map->Foo` read as one token. Don't hand-roll syntax-table hacks.
* `enclosing-production` must handle aliased `defrule` (`(r/defrule NAME` via `:as r`) by reading `ns-aliases`, not regex.

**[S] Fact-type resolution:**

* Wire `ctor/resolve-record-type` (see §5) into `client/navigate`; anchor resolved `T` against `P`'s `:lhs-types`/`:insert-types`∪`:retract-types` before filtering `:upstream`/`:downstream` `:match` pairs. Hierarchy already accounted for by `core/downstream?` via `:ancestors-fn`.

**[P] Server accessor:**

* Add `(defn current-system [] @default-system)` alongside `client/register!` — spike uses `register!` for tests, `current-system` for 0-arg convenience. Keep `default-system` `^:private`.

**[P] Popover + jump:**

* `completing-read` is sufficient (helm/ivy advise it). Sort targets deterministically by fq name (plan's choice), surface `:via :retract` as `"(retract)"` suffix.
* Jump: `cider-find-var` first, `find-file`+`goto-char` with kondo row/col second, `cider-find-ns`+regex third. Tier 2 kondo deferred until a real non-var `defrule`/`defquery` case bites.

**[P] Testing:**

* Add `server/test/clara/server/graph/client_test.clj` against `loan-app-rules` + `analyze-test-rules` (covers imported class, record ctor, accumulator `:from`, `:not`, `map->DocumentCheck` helper chain). Assert single/multi/zero cases, `:via` flag, alias/`::` resolution. Gate with `make test lint reflection-check`.

---

## 12. Suggested plan edits (for next pass)

1. Replace §10 `load-file "~/Projects/..."` with spike `M-x load-file` + layer-based durable install (see §3).
2. Rewrite §7 ctor-var bullet to call `ctor/resolve-record-type` instead of `(str var-ns "." X)`.
3. Add §7.4 note: resolve `:constructor-sym` via `ns-resolve` in `caller-ns`, not filename/row/col.
4. Change schema example from `m/=>`/`m/schema` to `s/defn`/`s/defschema`/`s/validate`.
5. Replace "file-watch / kondo cache invalidation" paragraph with explicit `clara-explorer-refresh` → `server/reload-annotations!` contract (§9).
6. Add `Package-Requires` header (§4) to §9 function inventory.
7. Keep `editor/emacs/` — add a one-line note that load-path is layer-provided, not machine-specific.

---

## 13. References

* Plan: `docs/planning/explorer-server-emacs-plan.md`
* Ctor helpers: `server/src/clara/server/tools/graph/analyze/ctor.clj`
* Server lifecycle: `server/src/clara/server/graph/server.clj:33,399-423,433-455`
* Cache: `server/src/clara/server/graph/cache.clj`
* Serialize: `server/src/clara/server/tools/graph/serialize.clj:25`
* Your dotfile: `~/emacs-dotfiles/.spacemacs` (symlink at `~/.spacemacs`), `elpa/29.4/develop/{cider,parseedn}`
* Skills: `clojure-engineering` (Prismatic Schema, reflection), `AGENTS.md` make targets
