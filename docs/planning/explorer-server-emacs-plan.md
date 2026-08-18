# Explorer Server ↔ Emacs (CIDER) Navigation Plan

Status: **Proposed** (not yet implemented)

Related:

Goal: from a `.clj` buffer connected to a live CIDER REPL that is running the
explorer server (`clara.server.graph.server/start!`), point at a fact type in a
`defrule`/`defquery` and jump to the producer (LHS) or consumer (RHS) of that
type, using the dependency graph the server has already computed.

---

## 1. Goals and non-goals

**Goals**

- Two editor commands:
  1. **Navigate to producer** — cursor over a **LHS** fact type → jump to the
     upstream production that inserts (or retracts) a fact satisfying it.
  2. **Navigate to consumer** — cursor over a **RHS** fact type → jump to a
     downstream production whose LHS consumes a fact that type satisfies.
- Direct jump when exactly one candidate; a **popover** (helm/ivy
  `completing-read`) when there is more than one. Popover options are
  **fully-qualified** production names, never truncated.
- Resolution of shorthand tokens to their fully-qualified, kind-explicit fact
  type before matching (imported class, `:refer`'d symbol, `:as` alias,
  `::auto-resolved` keyword, record constructor `->Foo` / `map->Foo` / `Foo.`).
- Ship a single loadable `.el` file as the starting point for testing.
- Leave a clean seam for a future neovim (Conjure) client.

**Non-goals**

- No UI (`ui/`) changes.
- No change to the existing HTTP API contract, and no new HTTP endpoints
  (remote-server support is out of scope — see §4.2).
- No LSP integration in the first cut (see §4.3 for the reasoning and the path
  that would justify it).
- Not implementing — this document is the plan only.

---

## 2. The two commands, specified

Notation: `P` is the production under the cursor (a rule or query); `T` is the
fact type under the cursor.

### 2.1 Navigate to producer (LHS)

Precondition: cursor is on a fact type in `P`'s LHS.

`T` may be any LHS type: a plain condition `[Application …]`, a nested
`:not`/`:and`/`:or`/`:exists` type, or an accumulator `:from [DocumentCheck …]`
type.

Result set = the upstream productions of `P` (from `P`'s `:upstream` entries)
whose `:match` includes a `consumer-type` equal to `T` (kind-explicit,
serialized in `P`'s namespace). Because an upstream edge exists **only** when
some produced type satisfies some consumed type, the `:match` pairs are exactly
the satisfaction evidence; filtering on `consumer-type == T` selects the
producers of _that_ type.

Hierarchy is already accounted for: `core/->dep-graph` links a producer to a
consumer when the producer's inserted type is `T` itself **or** a descendant of
`T` (via the session's `:ancestors-fn`). "Navigate to producer" therefore
surfaces producers of subtypes too, which is what we want.

- 0 results → message `No producer of <T> for <P>`.
- 1 result → jump directly.
- N results → popover of fully-qualified names, sorted; choose one → jump.

### 2.2 Navigate to consumer (RHS)

Precondition: cursor is on a fact type `T` in `P`'s RHS **effect chain** — the
code that produces `P`'s inserts/retracts. That includes both:

- a literal boundary-function argument (`insert!` / `insert-unconditional!` /
  `retract!`), and
- a **fact-constructor callsite the rule's RHS is already linked to** by the
  dynamic callsite analysis: user-defined constructors (`:fact-constructors`),
  helper functions the RHS reaches (`:boundary-to-constructor-path`
  provenance), and var-as-fact aliases (`:fact-type-spec-fn`).

Only rules have an RHS; queries never do.

`T` may be a record constructor (`map->ApplicationOutcome`,
`->ApplicationOutcome`, `ApplicationOutcome.`), a user-defined ctor var, an
imported/`:refer`'d class, a keyword, or a string. The cursor need not sit on
the literal `insert!` argument — it may be over the ctor token in a helper fn
the RHS calls; resolution goes through the production's recorded callsite
linkage (§7.4).

Result set = the downstream productions of `P` (from `P`'s `:downstream`
entries) whose `:match` includes a `producer-type` equal to `T`. Retraction
coupling is distinguishable — the `:match` pair carries `:via :retract` — and
should be surfaced in the popover (e.g. `(retract)` suffix) rather than hidden.

- 0 / 1 / N results behave as in §2.1.

When the cursor is over a ctor **outside** any `defrule` (e.g. in the helper
fn's own definition), there is no enclosing `P` to scope to: resolve the ctor
to `T` via the same callsite linkage in reverse (which productions' callsites
match this `:constructor-sym` / source location), then answer with the global
consumers of `T` rather than `P`'s scoped `:downstream` (§7.4).

### 2.3 Popover contents

Every option is the **fully-qualified** production name
(`ns/rule-name`), the only label we show. The client API also returns `:ns`,
`:type` (`"rule"` / `"query"`), `:via` (`:insert` / `:retract`), and the
source location; the popover uses the fq name, and the jump uses the source
location.

---

## 3. What the server already knows

Everything the commands need semantically is already computed; the gap is only
an editor-shaped query surface and one missing index.

| Need                                                              | Existing source                                                                                                                             | Status                                  |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| Producers/consumers per production, with type-bridge evidence     | `core/->dep-graph` + `:match` on `:upstream`/`:downstream` (`core/get-production-deps-summary`)                                             | ✅ done                                 |
| Hierarchy satisfaction (inserted subtype satisfies read ancestor) | `core/downstream?` via session `:ancestors-fn`                                                                                              | ✅ done                                 |
| Kind-explicit type serialization + alias/refer/`::` resolution    | `serialize/resolve-type`                                                                                                                    | ✅ done (one gap: constructor vars, §7) |
| Deterministic ids                                                 | `serialize/route-id`                                                                                                                        | ✅ done                                 |
| Warm, cached analysis on the running system                       | `cache/get-rulebase-analysis` (`server/start!` warms it)                                                                                    | ✅ done                                 |
| **Source location (file/row/col) of each production**             | var `:file`/`:line`/`:column` metadata for `defrule`/`defquery` (most productions); kondo `:var-definitions` for non-var productions (rare) | ✅ var tier; ⚠️ kondo tier (§8)         |
| An editor-facing query surface (HTTP or Clojure)                  | —                                                                                                                                           | ❌ to be added (§6)                     |

The rulebase analysis is a **pure function**
(`core/->rulebase-analysis` of `(:session state)` and `(:annotations state)`),
so an editor client can compute it fresh or reuse the running system's warmed
cache; there is no hidden mutation to worry about.

---

## 4. Architecture decision (and the three questions)

### 4.1 How much needs to be in elisp?

A thin but real layer — roughly **120–180 lines**. Its responsibilities:

1. **Structural navigation** — find the enclosing `(defrule|defquery NAME …)`,
   decide whether point is left or right of the top-level `=>` (LHS vs RHS),
   and grab the fact-type token at point.
2. **Transport** — eval one Clojure form over the CIDER connection
   (`cider-nrepl-sync-request:eval`) and `parseedn-read-str` the printed EDN
   result. (`parseedn` is already in the user's Spacemacs package set.)
3. **UX** — `completing-read` popover (helm/ivy) for N > 1, and
   `cider-find-var` (var) / `find-file`+`goto-char` (non-var) for the jump.
4. **Commands** — named `interactive` functions (`M-x …`); keybindings later.

Everything semantic — which type, which producers/consumers, hierarchy
satisfaction, source locations — stays in Clojure. Elisp is glue + editor UX.

### 4.2 Do we need dedicated server endpoints?

**Not HTTP endpoints at all — but yes, a dedicated Clojure
client-API namespace.** The user's setup is "a live REPL running the server,"
and CIDER is already a client of that REPL. The most direct path is to eval a
small, stable Clojure function over nREPL and read back EDN. That function is
the "endpoint" — it just isn't HTTP.

- **No** port management, URL encoding, or JSON serialization in elisp.
- **No** new Ring routes, response schemas, or API-doc churn.
- The same Clojure surface is exactly what a future neovim/Conjure client evals
  (§12) — the "shared part" is the Clojure namespace, not a transport.

Remote/headless server support is **explicitly out of scope**, so HTTP
endpoints are not planned. If that ever changes, they'd be thin wrappers over
the same client-API functions (`POST /v1/navigate` → `client/navigate`), so
nothing is wasted.

### 4.3 Is this an LSP use-case?

Partially, but **not the right first cut** — and possibly not the right end
state either.

- "Upstream producer via fact-type hierarchy" is a **domain-specific graph
  traversal**, not "find references" — LSP has no vocabulary for it; it lives
  in the explorer server. (Plain jump-to-definition is not the problem: most
  productions resolve to vars, §8.)
- What LSP **would** buy us — symbol/alias/refer resolution, and the jump/popup
  mechanics — is the easy ~100 lines we'd otherwise hand-roll in elisp, and it
  would give neovim support for free.

Decision: **start with elisp glue + a Clojure client-API over nREPL.** Record
LSP as the long-term option (§13) with the concrete trigger: if we want the
same feature across editors with zero editor-specific code, wrap the
client-API in a small custom language server (or a clojure-lsp extension) that
answers a custom `definition`-like request by calling `client/navigate`. That
is a phase of its own, not the spike.

### 4.4 Resulting shape

```
editor (elisp, ~150 LOC)                 Clojure (shared core)
────────────────────────                 ──────────────────────
find enclosing defrule/defquery   ──┐
detect LHS vs RHS                  │
grab token at point                │   clara.server.graph.client/navigate
resolve -> EVAL over nREPL ────────┼──▶   (token+side+production) -> EDN targets
parseedn-read <- EDN               │   clara.server.graph.client/production-source
completing-read popover / jump  ◀──┘   jump: cider-find-var (var) · kondo/regex (non-var)
```

---

## 5. The shared core: `clara.server.graph.client`

New namespace `server/src/clara/server/graph/client.clj`. Small, stable,
EDN-in/EDN-out. It is the **contract**; both editors target it.

```clojure
(ns clara.server.graph.client
  "Editor-facing query surface. Pure EDN in, EDN out. No HTTP."
  (:require [clara.server.graph.cache :as cache]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.serialize :as serialize]
            ...))

(defonce ^:private system (atom nil))
(defn register! [sys] (reset! system sys) ::ok)
(defn system [] @system)
```

Proposed functions:

| Function               | Input                                                 | Output                                                                                                    |
| ---------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `register!`            | system map from `server/start!`                       | `::ok`                                                                                                    |
| `navigate`             | `{:production "ns/rule" :side :lhs\|:rhs :token "…"}` | see below                                                                                                 |
| `production-source`    | `"ns/rule"`                                           | var: `{:var? true :file … :line … :column …}`; non-var: `{:var? false :file … :row … :col …}`; else `nil` |
| `production-locations` | —                                                     | full `{fq-name location}` index (debugging)                                                               |

`navigate` result (EDN, one round trip):

```clojure
{:direction :producer        ;; or :consumer
 :production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
 :type       "clara.server.tools.graph.rules.loan_app_facts.Application"
 :targets
 [{:name "clara.server.tools.graph.rules.loan-app-rules/app-outcome-pending?"
   :ns   "clara.server.tools.graph.rules.loan-app-rules"
   :type "rule"
   :via  :insert             ;; :insert | :retract (retract-coupling flag)
   :source {:var? true :file "clara/server/tools/graph/rules/loan_app_rules.clj"
            :line 54 :column 1}}]}
```

`navigate` internals:

1. Normalize `:production` to a canonical fq string (matches
   `ann/normalize-rule-name` behavior — strings/symbols/keywords converge).
2. Get the warmed analysis:
   `(cache/get-rulebase-analysis (:cache sys) (:session state) (:annotations state)
                               (:memory-analysis state))`.
3. Confirm `P` exists (rules **or** queries map), else return a
   `{:error "…"}` the editor can message.
4. Resolve `:token` against `P`'s namespace and `:side` (§7) → canonical
   kind-explicit name `T`, including the callsite-aware path for RHS fact-ctor
   tokens (§7.4).
5. For `:lhs`: filter `P`'s `:upstream` entries where some `:match` has
   `consumer-type.name == T`. For `:rhs`: filter `P`'s `:downstream` where some
   `:match` has `producer-type.name == T`.
6. Attach `:source` per target — var metadata where `ns-resolve` succeeds,
   kondo location otherwise (§8).
7. Sort deterministically by fq name; return.

The client-API never re-serializes types by hand — it reuses
`serialize/resolve-type`, `serialize/serialize-production-dep`, and the
`:match` already on the analysis, so kind-explicit names and route ids stay
consistent with the HTTP API.

### 5.1 Accessing the running system

`server/default-system` is `^:private`. Two options:

- **Preferred:** the REPL user binds the result of `start!` and registers it:
  ```clojure
  (require '[clara.server.graph.client :as client])
  (def s (server/start! {:session my-session :port 9999}))
  (client/register! s)
  ```
- **Fallback:** add a public accessor to `server` (e.g. `current-system`) that
  returns `@default-system`, and have `client/navigate` default to it. One-line
  addition, removes the manual `register!` step for the common single-server
  case. Recommend doing both: `register!` for explicit/test use, `current-system`
  for the 0-arg convenience.

---

## 6. Split of responsibility

| Concern                                                     | Where                                                   | Why                                            |
| ----------------------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------- |
| Dep graph, producers/consumers, hierarchy                   | Clojure (existing `core`)                               | already computed + cached                      |
| Kind-explicit resolution of alias/refer/`::`                | Clojure (`serialize/resolve-type` + client)             | one source of truth for the kind vocabulary    |
| Constructor→fact-type mapping (`->Foo`, `map->Foo`, `Foo.`) | Clojure (client: callsite `:constructor-sym`→`:resolved-types`, reusing `analyze.ctor` where possible) | fiddly, must match annotations' resolved types |
| Source location of productions                              | Clojure (new kondo extraction)                          | kondo already has row/col/filename             |
| Enclosing production, LHS/RHS side, token-at-point          | elisp                                                   | editor structural knowledge                    |
| nREPL eval + EDN parse                                      | elisp                                                   | CIDER + `parseedn` already present             |
| Popover / direct-jump                                       | elisp                                                   | helm/ivy + `find-file`                         |

---

## 7. Fact-type resolution contract

This is the one genuinely hard part, and it must live in Clojure so it can be
refined without touching either editor. Input is the **raw token text** at
point plus `P`'s namespace and side. Steps:

1. **Read the token in context.** Use `clojure.core/read-string` with `*ns*`
   bound to `P`'s namespace so `::kw` auto-resolves and symbols read normally.
   (Not `edn/read-string`, which does not resolve `::`.)
2. **Resolve by kind** (mirrors `serialize/resolve-type`):
   - keyword → `(str kw)` (`::foo` already read as `:ns/foo`).
   - string → `(pr-str s)`.
   - symbol:
     - `ns-resolve` → `Class` → `.getName` (imported/`:refer`'d class).
     - `ns-resolve` → `var` whose name is `->X` / `map->X` → derive class name
       `(str var-ns "." X)`; a trailing-dot symbol `X.` → strip the dot then
       class name. **This is the gap in `serialize/resolve-type` today** (it
       returns the var's `ns/name`, not the fact type's class name), and the
       client must close it.
     - unresolved → `symbol[<value>]` (then it will not match any `:lhs-types`
       / `:insert-types`, and we message "no fact type found under cursor").
3. **Match against the production's declared types** for the side:
   - `:lhs` → `:lhs-types`; `:rhs` → `:insert-types` ∪ `:retract-types`.
     This anchors on the same kind-explicit names the analysis already computed,
     so a token resolves only if it _is_ one of the production's own types.
4. **Callsite-aware resolution (primary for RHS, co-equal with step 2).** A
   token that names a *user-defined* ctor or a ctor reached through helper fns
   will not resolve by name derivation alone. The production's
   `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected` carry
   the callsites its RHS is linked to — `:constructor-sym`, `:resolved-types`,
   `:fact-type` (var-as-fact), `:source-str`, `:filename`, `:ns`, and the
   `:via` provenance chain. Fully-qualify the token to a ctor symbol and match
   it against callsite `:constructor-sym` (or `:fact-type`); the matching
   callsite's `:resolved-types` is `T`. This is what makes user-defined ctors
   (`:fact-constructors`), helper-function chains
   (`:boundary-to-constructor-path`), and var-as-fact aliases
   (`:fact-type-spec-fn`) resolvable instead of assuming an inline `insert!`
   argument.

   For the **outside-the-defrule** case (§2.2), run the same match *across*
   productions: find callsites whose `:constructor-sym`/`:source-str`/position
   match the cursor, read their `:resolved-types` as `T`, and return the
   global consumers of `T` rather than `P`'s scoped `:downstream`.

Example — cursor over `map->ApplicationOutcome` in
`app-outcome-approved?` (RHS): token reads as the symbol
`map->ApplicationOutcome`; `ns-resolve` finds the record constructor var;
step 2 derives `clara.server.tools.graph.rules.loan_app_facts.ApplicationOutcome`;
step 3 finds it in `:insert-types`. LHS cursor over `Application` resolves the
imported class to the same-style name and filters `:upstream` matches.

---

## 8. Source location ("navigate to source")

The target of both commands is a **production**, and "jump to it" means
opening the file at the `(defrule …)` / `(defquery …)` form.

**Tier 1 — var metadata (primary, covers most productions).** In Clara,
`defrule` expands to a `defn` (a var with `:rule true` metadata whose 0-arity
returns the production map), and `defquery` expands to a `def` (a var with
`:query true` holding the production). Both intern an ordinary var, so the
reader-attached `:file`/`:line`/`:column` metadata points at the
`defrule`/`defquery` form. For "many/most" productions,
`(ns-resolve ns name)` yields a var, and the elisp jumps with
`(cider-find-var nil "ns/rule-name")` — which also resolves the classpath
resource path to an absolute file. No new server index is needed for this tier.

**Tier 2 — kondo `:var-definitions` (fallback for non-var productions).**
Productions built by special structures — `defsession` + inline `parse-rule`/
`parse-query`, `clara.tools.testing-utils/def-rules-test`, or programmatically
assembled production maps — do **not** intern production vars, so `ns-resolve`
returns nil. For these, clj-kondo's `defrule`/`defquery` hooks rewrite the forms
to `(def name {:production …})` and emit `:var-definitions` with
`:ns`/`:name`/`:row`/`:col`/`:end-row`/`:end-col`/`:filename`. But
`analyze/prune-and-rename-analysis` **removes** those real-source definitions
during source synthesis (they'd collide with the synthetic snippet defs); only
the pruned form is cached. Additive fix (only if a real non-var case bites):

- **A (recommended):** a dedicated cached pass
  `analyze/->production-source-locations` running clj-kondo over each
  rule-owning namespace's **real** source (via `analyze/find-ns-resource`, no
  synth, no pruning) with only `:var-definitions` analysis, extracting
  `{fq-name {:filename :row :col :end-row :end-col}}` under a separate cache
  key (`[::source ns-sym]`).
- **B:** stash the pre-prune `:var-definitions` for production vars as a side
  map (e.g. `::production-locations`) on the returned analysis in
  `->rule-source-analysis-from-namespaces`, then thread it through the cache.

Option A is more decoupled (no change to the synth path).

**Tier 3 — elisp regex fallback (last resort).** `cider-find-ns` + search for
`(defrule|defquery NAME` (§9.5). Handles non-classpath-source / `jar:` cases.

`find-ns-resource` returns a URL: `file:` URLs (the user's `server/src`/`test`
sources) convert to an absolute path; `jar:` URLs return `nil` and drop to the
regex fallback.

The spike uses **only tiers 1 and 3**; tier 2 is deferred until a non-var
production actually needs it.

---

## 9. The elisp layer

Single file: `editor/emacs/clara-explorer.el` (new `editor/` top-level dir;
`editor/neovim/` later). No package dependency beyond what Spacemacs already
loads (`cider`, `parseedn`, `dash`/`f`/`s`, `clojure-mode`, helm/ivy).

### 9.1 Commands

```elisp
(clara-explorer-navigate-producer)   ; M-x clara-explorer-navigate-producer
(clara-explorer-navigate-consumer)   ; M-x clara-explorer-navigate-consumer
```

Both are `interactive` and delegate to a shared `clara-explorer--navigate side`.
No keybindings for now — call them via `M-x` (after `M-x eval-buffer` on the
file) during the spike.

### 9.2 Structural navigation (elisp)

- `clara-explorer--enclosing-production` — walk up from point to the nearest
  `(r/defrule NAME …)` / `(defrule NAME …)` / `(defquery NAME …)` form (any
  alias prefix). Return the **unqualified** `NAME` and the start/end of the
  form. Derive the namespace from `cider-current-ns` (the buffer's own ns),
  and combine to the fq name `ns/NAME`. (The client-API verifies the fq name
  resolves to a real production and can correct the string/symbol form.)
- `clara-explorer--side-at-point` — relative to the top-level `=>` inside that
  defrule: point before it → `:lhs`; after → `:rhs`. (Queries have no `=>` →
  always `:lhs`.)
- `clara-explorer--token-at-point` — the symbol/keyword under point, including
  a leading `:` / `::` and the trailing `.` of a class constructor. Use the
  clojure-mode syntax table carefully (keywords and `Foo.` must read as one
  token, not split).

### 9.3 Transport + parse

```elisp
(defun clara-explorer--eval-edn (form)
  (let* ((code (prin1-to-string form))     ; EDN form the elisp already built
         (resp (cider-nrepl-sync-request:eval code))
         (val  (nrepl-dict-get resp "value")))
    (when val (parseedn-read-str val))))
```

`cider-nrepl-sync-request:eval` is present in the user's CIDER
(`cider-client.el`) — confirmed. Sync eval keeps the command a single,
synchronous round trip.

The elisp builds one form:

```clojure
(clara.server.graph.client/navigate
  {:production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
   :side :rhs
   :token "map->ApplicationOutcome"})
```

and the value comes back as the EDN map in §5.

### 9.4 Popover + jump

- 0 targets → `(message "No %s of %s" direction type)`.
- 1 target → `clara-explorer--goto` directly.
- N targets → `(completing-read "Producer: " fq-names nil t)` (helm or ivy as
  configured), then `clara-explorer--goto` the chosen one.
- `clara-explorer--goto` — for a var-backed target (`:var? true`, the common
  case) call `(cider-find-var nil fq-name)`, which resolves the resource path
  and lands on the `defrule`/`defquery` form. Otherwise use `:file` +
  `:row`/`:col` from the kondo tier, then fall back to §9.5.

### 9.5 Source-location fallback (elisp)

Last resort (non-var target with no kondo location, or a `jar:` file we cannot
open): jump to the namespace file and search:

1. `(cider-find-ns ns)` (or `clojure-find-ns`) to open the file.
2. `(goto-char (point-min))`, then
   `(re-search-forward (format "(def\\(rule\\|query\\)[[:space:]\n]+%s\\b" name) nil t)`.

Handles the rare macro-emitted / non-classpath-source cases.

### 9.6 Command names

Two `interactive` commands, nothing else for the spike:

- `clara-explorer-navigate-producer`
- `clara-explorer-navigate-consumer`

(Optional, later: a `clara-explorer-mode` minor mode + leader keys. Not needed
to test — `M-x` is enough.)

---

## 10. Spacemacs integration

The user's dotfiles live at `~/emacs-dotfiles/.spacemacs` (Spacemacs install at
`~/.emacs.d`). Custom elisp belongs in `dotspacemacs/user-config`:

```elisp
;; in dotspacemacs/user-config
(load-file "~/Projects/clara-rules-explorer/editor/emacs/clara-explorer.el")
```

For the **spike**, no `.spacemacs` change is needed: open the file and
`M-x eval-buffer`, then call the two commands with `M-x`. Keybindings
(`, g p` / `, g c`, matching the existing `, g f`) come later, after the
commands feel right.

---

## 11. Server-side changes checklist

Minimal set for the working spike + follow-ups:

| #   | Change                                                                               | Required for spike?                                             |
| --- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| 1   | `clara.server.graph.client` namespace (`register!`, `navigate`, `production-source`) | ✅ yes                                                          |
| 2   | Public accessor for the running system (`server/current-system`)                     | ✅ yes (0-arg convenience)                                      |
| 3   | Constructor→class-name resolution helper (close the `serialize/resolve-type` gap)    | ✅ yes (§7)                                                     |
| 4   | `analyze/->production-source-locations` (kondo tier, non-var productions only)       | ⚠️ phase 1 (spike uses `cider-find-var` for var-backed targets) |
| 5   | HTTP endpoints wrapping `client/navigate`                                            | ❌ not planned (no remote-server support)                       |
| 6   | `client/navigate` callsite-aware RHS resolution (§7.4)                                       | ✅ yes (§2.2)                                                  |

Tests: `client/navigate` gets unit tests in `server/test` against the existing
demo rules (`clara.server.tools.graph.rules.loan-app-rules` is ideal — it has
imported classes, a record constructor in the RHS, an accumulator `:from`, and
a `:not`), asserting: single-target direct result, multi-target ordering, retract
`:via` flag, alias/refer resolution, `::keyword` resolution, and the
no-match error. Add a callsite-linked case from
`clara.server.tools.graph.rules.analyze-test-rules` (its `make-document-check`
helper wraps `laf/map->DocumentCheck`, so a cursor over `map->DocumentCheck`
resolves through the recorded callsite linkage, not a literal `insert!`
argument).

---

## 12. Neovim sharing

The shared core is the Clojure `client/navigate` — nothing transport-specific.
A neovim client (Conjure, or `vim.ui.select` + a direct nREPL call) evals the
same form and parses the same EDN. The neovim-specific work is the Lua
equivalent of §9.2–§9.4 (find enclosing form + side + token, popup, jump),
roughly the same ~100 lines. Tree-sitter-clojure can supply the enclosing-form

- token navigation more robustly than the elisp regex/syntax-table approach, so
  the two editors may converge on slightly different structural heuristics while
  sharing `client/navigate` exactly.

Keep `client/navigate`'s inputs/outputs **plain EDN with fq strings**, never
editor-specific types, so neither editor needs Clojure-side changes.

---

## 13. LSP evaluation (deferred)

Why not now: the graph semantics are domain-specific and live in the running
server, and the elisp glue is small. LSP/clojure-lsp can already resolve the
common `defrule`/`defquery` vars, but it cannot express "producer/consumer via
fact-type hierarchy" without a custom extension. Why it may come later: a
custom language-server (or clojure-lsp extension) that exposes "definition of a
fact type in a defrule" by calling `client/navigate` would give **both** emacs
and neovim the feature with zero editor-specific code, and would reuse
clojure-lsp's existing alias/refer resolution.

Trigger to revisit: after the spike proves the semantics, if maintaining two
editor clients (elisp + Lua) starts to cost more than the one-time cost of a
language-server adapter that bridges to the running server's in-memory graph.

---

## 14. Execution order

**Phase 0 — spike (prove the semantics):**

1. `client/navigate` + `server/current-system` + constructor-resolution helper.
2. `editor/emacs/clara-explorer.el` with §9.2–§9.4; jump via `cider-find-var`
   (var-backed targets) with the §9.5 regex fallback.
3. Manual test via `M-x eval-buffer` +
   `M-x clara-explorer-navigate-{producer,consumer}` against the demo rules;
   verify producer/consumer filtering, popover FQ names, alias/refer/`::`/
   constructor resolution.

**Phase 1 — solidify:**

4. `analyze/->production-source-locations` (kondo tier) for **non-var**
   productions; `client/navigate` attaches `:source` for them; elisp keeps
   `cider-find-var` for var-backed targets.
5. Unit tests for `client/navigate`; add a small README snippet for `.spacemacs`
   and a REPL bootstrap snippet (`(client/register! (server/start! …))`).

**Phase 2 — neovim (optional):**

6. neovim client (Conjure) against the same Clojure `client/navigate` surface.

(HTTP endpoints are not planned — no remote-server support.)

Verification gates: `cd server && make test lint reflection-check` after
server changes; `M-x eval-buffer` + manual navigation for the elisp.

---

## 15. Decisions (resolved)

1. **Transport = nREPL eval over the existing CIDER connection.** No HTTP
   endpoints (no remote-server support).
2. **Elisp is glue (~150 lines); all semantics live in a new
   `clara.server.graph.client` namespace** that both editors share.
3. **Not LSP now.** Revisit when cross-editor cost outweighs a language-server
   adapter (§13).
4. **Source location is var metadata first** (`cider-find-var` for
   `defrule`/`defquery`), with a kondo tier for non-var productions and an
   elisp regex fallback (§8).
5. **Popover labels are always fully-qualified production names**; retraction
   coupling is surfaced via `:via :retract`.

## 16. Open questions

- Whether to prefer the public `server/current-system` accessor or require an
  explicit `client/register!` — implement both, default to the accessor.
- Popover ordering: sort by fq name (chosen) vs. by load order / dependency
  distance. Start with fq name; load order is trivially available if desired.
- Whether the kondo source-location tier (§8) is worth building at all, given
  non-var productions are rare — start with `cider-find-var` + regex fallback
  and add the kondo tier only if a real non-var case bites.
