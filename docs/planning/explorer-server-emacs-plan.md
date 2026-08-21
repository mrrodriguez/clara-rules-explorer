# Explorer Server ↔ Emacs (CIDER) Navigation Plan

Status: **Implemented (phase 1)** — Phase 0 spike and Phase 1 solidified;
Phase 0.5 manual Emacs acceptance and Phase 2 extensions remain open.

Related:

- Review 1: `docs/planning/explorer-server-emacs-plan-review-1.md` (all items
  applied or answered inline)
- Roadmap / checklist: `docs/planning/explorer-server-emacs-roadmap.md`

Goal: from a `.clj` buffer connected to a live CIDER REPL that is running the
explorer server (`clara.server.graph.server/start!`), point at a fact type in a
`defrule`/`defquery` and jump to the producer (LHS) or consumer (RHS) of that
type, using the dependency graph the server has already computed.

---

## 1. Goals and non-goals

**Goals**

- Three editor commands:
  1. **Navigate to producer** — cursor over a **LHS** fact type → jump to the
     upstream production that inserts (or retracts) a fact satisfying it.
  2. **Navigate to consumer** — cursor over a **RHS** fact type → jump to a
     downstream production whose LHS consumes a fact that type satisfies.
  3. **Refresh analysis** — explicitly re-warm the server's analysis (and, when
     the session itself was rebuilt, swap it in) after re-evaling rules in the
     REPL. No file-watching (§5.2).
- Direct jump when exactly one candidate; a **popover** (`completing-read`,
  advised by helm in the user's setup) when there is more than one. Popover
  options are **fully-qualified** production names, never truncated.
- Resolution of shorthand tokens to their fully-qualified, kind-explicit fact
  type before matching (imported class, `:refer`'d symbol, `:as` alias,
  `::auto-resolved` keyword, record constructor `->Foo` / `map->Foo` / `Foo.`).
- **Machine-agnostic**: no hard-coded absolute paths (`~/Projects/...`), home
  directories, or ports anywhere in shipped elisp or docs (§10).
- Ship a single loadable `.el` file with explicit dependencies (§9.0) as the
  starting point for testing.
- Leave a clean seam for a future neovim (Conjure) client.

**Non-goals**

- No UI (`ui/`) changes.
- No change to the existing HTTP API contract, and no new HTTP endpoints
  (remote-server support is out of scope — see §4.2).
- No LSP integration in the first cut (see §4.3 for the reasoning and the path
  that would justify it).
- No file-watch / hot-reload of analysis (§5.2).
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

Only rules have an RHS; queries never do. On a query the consumer command
messages "queries have no RHS" and stops (no server round trip).

`T` may be a record constructor (`map->ApplicationOutcome`,
`->ApplicationOutcome`, `ApplicationOutcome.`), a user-defined ctor var, an
imported/`:refer`'d class, a keyword, or a string. The cursor need not sit on
the literal `insert!` argument — it may be over the ctor token in a helper fn
the RHS calls; resolution goes through the production's recorded callsite
linkage (§7.4).

Result set = the downstream productions of `P` (from `P`'s `:downstream`
entries) whose `:match` includes a `producer-type` equal to `T`. Retraction
coupling is distinguishable — the `:match` pair carries `:via :retract` — and
is surfaced in the popover as a `(retract)` suffix rather than hidden.

- 0 / 1 / N results behave as in §2.1.

When the cursor is over a ctor **outside** any `defrule` (e.g. in the helper
fn's own definition), there is no enclosing `P` to scope to: resolve the ctor
to `T` against the **live REPL namespace** (§7.4 — nREPL ns resolution, not
filename/row/col matching), then answer with the global consumers of `T`
rather than `P`'s scoped `:downstream` (§9.7).

### 2.3 Popover contents

Every option is the **fully-qualified** production name
(`ns/rule-name`), the only label we show, plus a `(retract)` suffix when
`:via :retract`. The client API also returns `:ns`, `:type`
(`"rule"` / `"query"`), `:via` (`:insert` / `:retract`), and the source
location; the popover uses the fq name, and the jump uses the source location.

---

## 3. What the server already knows

Everything the commands need semantically is already computed; the gap is only
an editor-shaped query surface and one missing index.

| Need                                                              | Existing source                                                                                                                             | Status                                  |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| Producers/consumers per production, with type-bridge evidence     | `core/->dep-graph` + `:match` on `:upstream`/`:downstream` (`core/get-production-deps-summary`)                                             | ✅ done                                 |
| Hierarchy satisfaction (inserted subtype satisfies read ancestor) | `core/downstream?` via session `:ancestors-fn`                                                                                              | ✅ done                                 |
| Kind-explicit type serialization + alias/refer/`::` resolution    | `serialize/resolve-type` (serialize.clj:25)                                                                                                 | ✅ done (ctor vars go through `analyze.ctor`, §7) |
| Constructor → record-type resolution (`->Foo`/`map->Foo`/`Foo.`)  | `analyze.ctor/resolve-record-type` (ctor.clj:23), `resolve-ctor-form` (ctor.clj:54), `constructor-fn-name?` (ctor.clj:11)                  | ✅ done — **reuse, do not replicate**   |
| RHS callsite linkage (`:constructor-sym` → `:resolved-types`)     | `analyze.callsite` (schema at callsite.clj:334-358); surfaced per production as serialized `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected` (core.clj:184-186, 235) | ✅ done |
| Global consumers of a type                                        | `fact_types` `used-by-rules` / `used-by-queries` (fact_types.clj:241-269, 373)                                                              | ✅ done                                 |
| Deterministic ids                                                 | `serialize/route-id`                                                                                                                        | ✅ done                                 |
| Warm, cached analysis on the running system                       | `cache/get-rulebase-analysis` (`server/start!` warms it)                                                                                    | ✅ done                                 |
| Explicit refresh primitives                                       | `server/reload-annotations!` (server.clj:448), `server/swap-session!` (server.clj:429)                                                      | ✅ done (elisp commands wrap them, §9.9) |
| **Source location (file/row/col) of each production**             | var `:file`/`:line`/`:column` metadata for `defrule`/`defquery` (most productions); kondo `:var-definitions` for non-var productions (rare) | ✅ var tier; ⚠️ kondo tier (§8)         |
| An editor-facing query surface (HTTP or Clojure)                  | —                                                                                                                                           | ❌ to be added (§5)                     |

The rulebase analysis is a **pure function**
(`core/->rulebase-analysis` of `(:session state)` and `(:annotations state)`),
so an editor client can compute it fresh or reuse the running system's warmed
cache; there is no hidden mutation to worry about.

---

## 4. Architecture decision (and the three questions)

### 4.1 How much needs to be in elisp?

A thin but real layer — roughly **150–200 lines**. Its responsibilities:

1. **Structural navigation** — find the enclosing `(defrule|defquery NAME …)`
   (including aliased prefixes like `r/defrule` — the demo rules use them, so
   this is the common case, not an edge), decide whether point is left or
   right of the top-level `=>` (LHS vs RHS), and grab the fact-type token at
   point.
2. **Transport** — eval one Clojure form over the CIDER connection
   (`cider-nrepl-sync-request:eval`) and `parseedn-read-str` the printed EDN
   result.
3. **UX** — `completing-read` popover (helm advises it in the user's setup) for
   N > 1, and `cider-find-var` (var) / `find-file`+`goto-char` (non-var) for
   the jump.
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
editor (elisp, ~180 LOC)                 Clojure (shared core)
────────────────────────                 ──────────────────────
find enclosing defrule/defquery   ──┐
detect LHS vs RHS (sexp-aware)     │
grab token at point                │   clara.server.graph.client/navigate
resolve -> EVAL over nREPL ────────┼──▶   (token+side+production|caller-ns) -> EDN
parseedn-read <- EDN               │   clara.server.graph.client/get-production-source
completing-read popover / jump  ◀──┘   jump: cider-find-var (var) · kondo/regex (non-var)
M-x clara-explorer-refresh ──────────▶   server/reload-annotations! (+ swap-session!)
```

---

## 5. The shared core: `clara.server.graph.client`

New namespace `server/src/clara/server/graph/client.clj`. Small, stable,
EDN-in/EDN-out. It is the **contract**; both editors target it. Schemas use
**Prismatic Schema** (`schema.core`), matching `server.clj` / `api.clj` — not
Malli.

```clojure
(ns clara.server.graph.client
  "Editor-facing query surface. Pure EDN in, EDN out. No HTTP."
  (:require [clara.server.graph.cache :as cache]
            [clara.server.graph.server :as server]
            [clara.server.tools.graph.analyze.ctor :as ctor]
            [clara.server.tools.graph.core :as core]
            [clara.server.tools.graph.serialize :as serialize]
            [schema.core :as s]
            ...))

(defonce ^:private registered-system (atom nil))

(s/defn register! :- s/Keyword
  [sys] (reset! registered-system sys) ::ok)

(s/defn get-current-system []
  (or @registered-system (server/get-current-system)))
```

Proposed schemas and functions:

```clojure
(s/defschema NavigateInput
  {(s/optional-key :production) (s/maybe s/Str)   ; fq "ns/rule"; nil = global path
   (s/optional-key :side)       (s/enum :lhs :rhs)
   (s/optional-key :caller-ns)  s/Str             ; buffer ns, for global path + ctor resolution
   :token                       s/Str})

(s/defschema SourceLoc
  {:var?   s/Bool
   :file   (s/maybe s/Str)
   :line   (s/maybe s/Int)
   :column (s/maybe s/Int)})

(s/defschema NavigateResult
  {:direction  (s/enum :producer :consumer :type)
   :production (s/maybe s/Str)
   :type       s/Str
   :targets    [{:name s/Str :ns s/Str :type s/Str
                 :via (s/enum :insert :retract)
                 :source SourceLoc}]})
```

| Function               | Input                                 | Output                                                              |
| ---------------------- | ------------------------------------- | ------------------------------------------------------------------- |
| `register!`            | system map from `server/start!`       | `::ok`                                                              |
| `get-current-system`       | —                                     | registered system, else `server/get-current-system`                     |
| `navigate`             | `NavigateInput`                       | `NavigateResult` or `{:error s/Str}`                                |
| `get-production-source`    | `"ns/rule"`                           | `SourceLoc`, else `nil`                                             |
| `get-production-locations` | —                                     | full `{fq-name location}` index (debugging)                         |

Validation happens at the choke point with `s/validate` (same pattern as
`server.clj`). Keep `*warn-on-reflection* true`: `Class/.getName` needs a
`^Class` hint, as in `serialize.clj`. Gate: `make test lint reflection-check`.

`navigate` result (EDN, one round trip, **pure EDN only** — strings, keywords,
maps, vectors, ints, booleans, nil; a `Class` object never crosses the wire):

```clojure
{:direction :consumer
 :production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
 :type       "clara.server.tools.graph.rules.loan_app_facts.ApplicationOutcome"
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
3. Confirm `P` exists (rules **or** queries map), else return
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

For the outside-defrule path (§9.7), `navigate` accepts
`{:production nil :caller-ns "my.app.rules" :token "map->Foo"}` (no `:side`)
and returns the global consumers of the resolved type — the same
`{:direction :type :targets …}` shape, so the editor-side popover/jump is
unchanged. **Identity is nREPL namespace resolution** (`ns-resolve` inside the
live session against `caller-ns`), never filename/row/col matching (§7.4).

The client-API never re-serializes types by hand — it reuses
`serialize/resolve-type`, `serialize/serialize-production-dep`, and the
`:match` already on the analysis, so kind-explicit names and route ids stay
consistent with the HTTP API.

### 5.1 Accessing the running system

`server/default-system` is `^:private` (server.clj:33) with no public accessor
today — verified. Two additions:

- **Preferred:** the REPL user binds the result of `start!` and registers it:
  ```clojure
  (require '[clara.server.graph.client :as client])
  (def s (server/start! {:session my-session :port 9999}))
  (client/register! s)
  ```
- **Convenience:** add a public `server/get-current-system` (one line:
  `@default-system`), and have `client/get-current-system` fall back to it.
  `default-system` stays `^:private`.

Do both: `register!` for explicit/test use, `get-current-system` for the 0-arg
case.

### 5.2 Lifecycle & staleness contract (no file-watch)

Navigation answers reflect the **session the server was started with**, not
the current REPL state. Re-evaling a `defrule` in the REPL changes the REPL's
vars but not the server's `(:session state)`. There are exactly two staleness
levels, each with an explicit, user-initiated fix:

| What changed                                        | Stale data                          | Fix (explicit command, §9.9)                       |
| --------------------------------------------------- | ----------------------------------- | -------------------------------------------------- |
| Rule source on disk / annotations / kondo view      | annotations + warmed analysis       | `M-x clara-explorer-refresh` → `server/reload-annotations!` (server.clj:448; re-derives annotations, rebuilds generated layer from cached per-ns kondo analyses, re-warms cache) |
| Rules re-eval'd in the REPL (session itself stale)  | the session the graph is built from | rebuild session in the REPL, then `M-x clara-explorer-swap-session` → `server/swap-session!` (server.clj:429) |

There is **no** `after-save-hook`, no filesystem watch, no mtime-keyed cache
invalidation. The workflow is documented in the README snippet: after
re-defining rules, rebuild the session and swap; after editing rule files
without re-evaling, refresh. If the kondo tier (§8) is added later, its per-ns
analysis is cached under `[::source ns-sym]` keyed by **cache identity**, and
`reload-annotations!` is the invalidation point.

---

## 6. Split of responsibility

| Concern                                                     | Where                                                                            | Why                                            |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------------------------------- |
| Dep graph, producers/consumers, hierarchy                   | Clojure (existing `core`)                                                        | already computed + cached                      |
| Kind-explicit resolution of alias/refer/`::`                | Clojure (`serialize/resolve-type` + client)                                      | one source of truth for the kind vocabulary    |
| Constructor→fact-type mapping (`->Foo`, `map->Foo`, `Foo.`) | Clojure — **`analyze.ctor/resolve-record-type` / `resolve-ctor-form`, reused**   | subtle (hyphen/underscore, class-load check); must match annotations' resolved types |
| RHS callsite linkage (`:constructor-sym` → `:resolved-types`) | Clojure (client reads serialized `:dynamic-insert-types-detected` on the production summary) | already computed by `analyze.callsite` |
| Source location of productions                              | Clojure (var metadata; new kondo extraction only if needed)                      | kondo already has row/col/filename             |
| Refresh / session swap                                      | Clojure (`server/reload-annotations!`, `swap-session!`), wrapped by elisp commands | explicit, no watchers                          |
| Enclosing production, LHS/RHS side, token-at-point          | elisp                                                                            | editor structural knowledge                    |
| nREPL eval + EDN parse                                      | elisp                                                                            | CIDER + `parseedn` already present             |
| Popover / direct-jump                                       | elisp                                                                            | `completing-read` + `find-file`                |

---

## 7. Fact-type resolution contract

This is the one genuinely hard part, and it must live in Clojure so it can be
refined without touching either editor. Input is the **raw token text** at
point plus `P`'s namespace and side. Steps:

1. **Read the token in context.** Prefer `clojure.edn/read-string` plus
   explicit `::`-resolution: look up `(ns-aliases caller-ns)` and resolve
   `::alias/kw` / `::kw` manually — this avoids `read-string` entirely.
   Acceptable alternative: `clojure.core/read-string` with `*ns*` bound to the
   caller ns (which auto-resolves `::`) — in that case bind
   `*read-eval* false` and wrap in `try`, with a comment recording the trust
   context: the token is trusted editor state eval'd inside the user's own
   nREPL session (same trust boundary as `cider-eval-last-sexp`), not
   untrusted file or network input. Do not apply blanket read-safety rules
   without that context.
2. **Resolve by kind** (mirrors `serialize/resolve-type`):
   - keyword → `(str kw)` (`::foo` already resolved in step 1).
   - string → `(pr-str s)`.
   - symbol:
     - `ns-resolve` in the caller ns → `Class` → `(.getName ^Class c)`
       (imported/`:refer`'d class; hint required for reflection).
     - `ns-resolve` → ctor-ish var or symbol (`->X`, `map->X`, `X.`, `X/new`,
       `new X`) → **delegate to `analyze.ctor/resolve-record-type`**
       (ctor.clj:23) / `resolve-ctor-form` (ctor.clj:54). Do **not** hand-derive
       class names: `resolve-record-type` already handles the `map->`/`->`
       prefixes, `-`→`_` in package segments, and verifies the class actually
       loads (`resolvable-fact-class`). This closes the gap in
       `serialize/resolve-type` (which returns the var's `ns/name`, not the
       fact type's class name) by reusing the analyzer's own logic, so nav
       resolution can never drift from what the annotations computed.
     - unresolved → `symbol[<value>]` (then it will not match any `:lhs-types`
       / `:insert-types`, and we message "no fact type found under cursor").
3. **Match against the production's declared types** for the side:
   - `:lhs` → `:lhs-types`; `:rhs` → `:insert-types` ∪ `:retract-types`.
     This anchors on the same kind-explicit names the analysis already computed,
     so a token resolves only if it _is_ one of the production's own types.
4. **Callsite-aware resolution (primary for RHS, co-equal with step 2).** A
   token that names a *user-defined* ctor or a ctor reached through helper fns
   will not resolve by name derivation alone. Read the production's serialized
   `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected` entries
   from its analysis summary (core.clj:184-186, 235 — sourced from the
   `:clara-rules/dynamic-insert-types-detected` annotation and serialized by
   `serialize/serialize-dynamic-detection`, so the shape matches what the HTTP
   API returns). Fully-qualify the token to a ctor symbol **via `ns-resolve`
   in the caller namespace** and match it against callsite `:constructor-sym`
   (or `:fact-type` for var-as-fact aliases); the matching callsite's
   `:resolved-types` is `T`. This is what makes user-defined ctors
   (`:fact-constructors`), helper-function chains
   (`:boundary-to-constructor-path`), and var-as-fact aliases
   (`:fact-type-spec-fn`) resolvable instead of assuming an inline `insert!`
   argument.

   **Identity is namespace resolution, not source position.** We have a live
   REPL: `:constructor-sym` resolves against the ns it was found in. Do not
   match on `:filename`/row/col; source position is only ever a display aid.

   For the **outside-the-defrule** case (§2.2), run the same match *across*
   productions: fully-qualify the token in `:caller-ns`, find callsites whose
   `:constructor-sym` / `:fact-type` match that fq symbol, read their
   `:resolved-types` as `T`, and return the global consumers of `T`
   (`used-by-rules` / `used-by-queries`) rather than `P`'s scoped `:downstream`.

Example — cursor over `map->ApplicationOutcome` in
`app-outcome-approved?` (RHS): token reads as the symbol
`map->ApplicationOutcome`; step 2's `ctor/resolve-record-type` resolves it to
`clara.server.tools.graph.rules.loan_app_facts.ApplicationOutcome`;
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
`analyze/prune-and-rename-analysis` (analyze.clj:432 — note: currently
**private**, `defn-`) **removes** those real-source definitions during source
synthesis (they'd collide with the synthetic snippet defs); only the pruned
form is cached. Additive fix (only if a real non-var case bites):

- **A (recommended):** a dedicated cached pass
  `analyze/->production-source-locations` running clj-kondo over each
  rule-owning namespace's **real** source (via `analyze/find-ns-resource`,
  analyze.clj:386 — no synth, no pruning) with only `:var-definitions`
  analysis, extracting `{fq-name {:filename :row :col :end-row :end-col}}`
  under a separate cache key (`[::source ns-sym]`, keyed by cache identity —
  see §5.2).
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
`editor/neovim/` later; optional Spacemacs layer skeleton alongside at
`editor/emacs/spacemacs-layer/`, §10). No machine-specific paths anywhere in
the file.

### 9.0 Dependencies (explicit contract)

File header:

```elisp
;; Package-Requires: ((emacs "28.1") (cider "1.12") (parseedn "1.2") (clojure-mode "5.18"))
```

- **Hard deps:** `cider` (`cider-nrepl-sync-request:eval`,
  `cider-current-repl`, `cider-current-ns`, `cider-connected-p`,
  `cider-symbol-at-point`, `cider-find-var`, `cider-find-ns`), `parseedn`
  (`parseedn-read-str`), `clojure-mode` (syntax table, `clojure-find-ns`),
  Emacs 28.1 (Spacemacs baseline).
- **Not deps:** `dash`/`f`/`s` (nothing in ~180 LOC needs them), `helm`/`ivy`
  (built-in `completing-read` is sufficient — helm advises it in the user's
  setup; never `(require 'helm)`).
- **Runtime guard** at top of file, so a bare `load-file` fails loudly:

  ```elisp
  (unless (featurep 'cider)    (user-error "clara-explorer requires cider"))
  (unless (featurep 'parseedn) (user-error "clara-explorer requires parseedn — add to dotspacemacs-additional-packages"))
  ```

Verified in the user's install: `cider-20260402.444`,
`parseedn-20231203.1909`, `clojure-mode-20260325.811`, helm present, ivy absent.

### 9.1 Commands

```elisp
(clara-explorer-navigate-producer)   ; M-x clara-explorer-navigate-producer
(clara-explorer-navigate-consumer)   ; M-x clara-explorer-navigate-consumer
(clara-explorer-refresh)             ; M-x clara-explorer-refresh  (§9.9)
(clara-explorer-swap-session)        ; M-x clara-explorer-swap-session (§9.9)
```

All `interactive`. The two navigate commands delegate to a shared
`clara-explorer--navigate side`. No keybindings for now — call them via `M-x`
(after `M-x eval-buffer` on the file) during the spike.

`clara-explorer--navigate side` routes on whether point is inside a
`defrule`/`defquery`:

- **Inside** → scoped navigation: build fq production name + side + token,
  call `client/navigate`, feed `:targets` to §9.4. If the enclosing form is a
  `defquery` and `side` is `:rhs`/consumer, message "queries have no RHS" and
  stop without a round trip.
- **Outside** (no enclosing production) → global path: only meaningful for
  `:consumer`. Resolve the ctor token against the live ns
  (`{:production nil :caller-ns … :token …}`) and list the global consumers
  of `T` (§9.7). `:producer` with no enclosing production messages "not inside
  a rule/query".

### 9.2 Structural navigation (elisp)

- `clara-explorer--enclosing-production` — walk up from point to the nearest
  `defrule`/`defquery` form **with any alias prefix** (`defrule`, `r/defrule`,
  `rules/defrule`, …). The demo rules use `r/defrule`
  (loan_app_rules.clj:15-84), so alias handling is the common case, not an
  edge: match the head symbol's `name` against `defrule|defquery` regardless
  of its namespace prefix. Return the **unqualified** `NAME`, the kind
  (`rule`/`query`), and the form bounds; derive the namespace from
  `cider-current-ns` and combine to the fq name `ns/NAME`. Returns **nil**
  when point is not inside one — the signal for the outside-defrule path
  (§9.7). (The client-API verifies the fq name resolves to a real production.)
- `clara-explorer--side-at-point` — **sexp-aware**, not string search: find
  the top-level `=>` at depth 1 under the enclosing defrule form (walk
  forward-sexp from the form start, skipping the name/docstring/attr-map),
  then compare point to its position. Queries have no `=>` → always `:lhs`.
- `clara-explorer--token-at-point` — `(cider-symbol-at-point 'look-back)`,
  the same helper the user's dotfiles already use (`.spacemacs:799`). It
  reads `::kw`, `Foo.`, `map->Foo`, and alias-prefixed `laf/map->Foo` as one
  token. Do not hand-roll syntax-table hacks.
- `clara-explorer--context` — one call that gathers
  `(:production fq-name-or-nil :kind rule|query|nil :side :lhs|:rhs|nil
    :caller-ns "…" :token "…")`; `:production nil` means the global path.
  Keeps the two commands thin and the outside-defrule logic in one place.

### 9.3 Transport + parse

`cider-nrepl-sync-request:eval` has signature
`(input &optional connection ns)` (cider-client.el:245 — verified).
**There is no `cider-current-connection`**; capture the connection once per
command with `(cider-current-repl 'infer 'ensure)` and pass it in, so
navigation never targets the wrong session when several REPLs are connected
(sesman cross-talk).

```elisp
(defun clara-explorer--eval-edn (form conn)
  (let* ((code (format "%S" form))   ; elisp-printed form; strings print as valid EDN
         (resp (cider-nrepl-sync-request:eval code conn))
         (val  (nrepl-dict-get resp "value")))
    (when val (parseedn-read-str val))))
```

The elisp builds one form — and **requires the client namespace first**, so a
REPL that predates `clara.server.graph.client` still works:

```clojure
(do (require 'clara.server.graph.client)
    (clara.server.graph.client/navigate
      {:production "clara.server.tools.graph.rules.loan-app-rules/app-outcome-approved?"
       :side :rhs
       :token "map->ApplicationOutcome"}))
```

The server returns `(pr-str result)` as the nREPL `"value"` — a parsable EDN
string, guaranteed pure EDN per §5 (a `Class` never crosses the wire).

### 9.4 Popover + jump

- 0 targets → `(message "No %s of %s" direction type)`.
- 1 target → `clara-explorer--goto` directly.
- N targets → `(completing-read "Producer: " labels nil t)` (helm advises it),
  where labels are fq names with a `" (retract)"` suffix when `:via :retract`;
  then `clara-explorer--goto` the chosen one.
- `clara-explorer--goto` — for a var-backed target (`:var? t`, the common
  case) call `(cider-find-var nil fq-name)`, which resolves the resource path
  and lands on the `defrule`/`defquery` form. Otherwise use `:file` +
  `:row`/`:col` from the kondo tier, then fall back to §9.5.

### 9.5 Source-location fallback (elisp)

Last resort (non-var target with no kondo location, or a `jar:` file we cannot
open): jump to the namespace file and search:

1. `(cider-find-ns ns)` (or `clojure-find-ns`) to open the file.
2. `(goto-char (point-min))`, then search for `(defrule|defquery` with any
   alias prefix followed by `NAME` — e.g.
   `(re-search-forward (format "(%sdef\\(rule\\|query\\)[[:space:]\n]+%s\\b"
                        "[^ \t\n()]*" name) nil t)`.

Handles the rare macro-emitted / non-classpath-source cases.

### 9.6 Function inventory

Public (`interactive`):

- `clara-explorer-navigate-producer` — scoped producer navigation.
- `clara-explorer-navigate-consumer` — scoped consumer navigation, or the
  global path when outside a production (§9.7).
- `clara-explorer-refresh` — re-warm annotations/analysis (§9.9).
- `clara-explorer-swap-session` — swap in a rebuilt session (§9.9).

Private helpers:

- `clara-explorer--navigate` — dispatcher (§9.1).
- `clara-explorer--context` — `(:production fq-or-nil :kind … :side …
  :caller-ns … :token "…")` (§9.2).
- `clara-explorer--enclosing-production` / `--side-at-point` /
  `--token-at-point` — structural navigation (§9.2).
- `clara-explorer--eval-edn` — nREPL sync eval + `parseedn-read-str` (§9.3).
- `clara-explorer--edn-get` — keyword lookup in the parsed alist (§9.8).
- `clara-explorer--choose-target` — `completing-read` popover (§9.4).
- `clara-explorer--goto` — `cider-find-var` / `find-file`+`goto-char` (§9.4).
- `clara-explorer--goto-fallback` — `cider-find-ns` + regex (§9.5).

(Optional, later: a `clara-explorer-mode` minor mode + leader keys. Not needed
to test — `M-x` is enough.)

### 9.7 Outside-defrule path (global consumers)

When the cursor is over a fact-ctor token (e.g. `map->DocumentCheck` inside
`make-document-check`) and there is no enclosing `defrule`/`defquery`, there is
no production to scope to. `clara-explorer-navigate-consumer` instead sends
`{:production nil :caller-ns (cider-current-ns) :token "map->DocumentCheck"}`.
The client-API:

1. Fully-qualifies the token in `caller-ns` (`ns-resolve` in the live
   session — the REPL already has the class/var loaded) and matches it across
   every production's `:dynamic-insert-types-detected` /
   `:dynamic-retract-types-detected` callsites' `:constructor-sym` /
   `:fact-type` (§7.4), reading `:resolved-types` as `T`.
2. Returns the **global** consumers of `T` (the fact-type detail's
   `used-by-rules` / `used-by-queries`) rather than a production's scoped
   `:downstream`.

Targets are `ProductionDep`s, so §9.4 reuses unchanged. Token extraction is
identical to §9.2; only what the server returns differs.

### 9.8 Plumbing (connection, EDN, errors)

Necessary glue, in one place:

- **Connection guard.** Every command starts with
  `(unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))`
  and captures `(cider-current-repl 'infer 'ensure)` once, passing it as the
  `CONNECTION` arg to `cider-nrepl-sync-request:eval` so navigation never
  targets the wrong session.
- **EDN conversion.** `parseedn-read-str` returns an **alist** of keyword→value
  pairs (not a hash-table). Read fields with a helper
  `(defun clara-explorer--edn-get (k m) (cdr (assq k m)))`; `:targets` is a
  list of maps whose `:name`/`:ns`/`:type`/`:via`/`:source` are read the same
  way.
- **Error handling.**
  - `navigate` returns `{:error "…"}` (unknown production, no match) →
    `(message "%s" error)`, no pop/jump.
  - `clara-explorer--eval-edn` gets no `"value"` (e.g. an exception) → relay
    `"err"`/`"ex"` from the nREPL response dict first, then give up.
  - No token at point (`clara-explorer--token-at-point` → nil) → message "not
    on a fact type".
- **REPL bootstrap.** The elisp does not start the server; it assumes the
  user's REPL has already run `(client/register! (server/start! …))` (or used
  `server/start!` with the §5.1 `get-current-system` fallback). A missing system
  surfaces as `{:error "no explorer system registered"}` from the client-API,
  which the elisp just relays.

### 9.9 Refresh commands (explicit, no watch)

```elisp
(defun clara-explorer-refresh ()
  "Re-derive annotations and re-warm the explorer analysis."
  (interactive)
  (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
  (clara-explorer--eval-edn
   '(do (require 'clara.server.graph.server)
        (clara.server.graph.server/reload-annotations!))
   (cider-current-repl 'infer 'ensure))
  (message "clara-explorer: analysis refreshed"))
```

`clara-explorer-swap-session` is the session-level counterpart: it evals a
user-supplied expression that rebuilds the session (prompted with
`read-string`, defaulting to the last used) wrapped in
`(server/swap-session! …)`. Document the §5.2 table as the workflow:
re-eval'd rules → rebuild + swap; edited files without re-eval → refresh.

---

## 10. Spacemacs integration (machine-agnostic)

The user's dotfiles live in a Spacemacs config (install at `~/.emacs.d`), but
**nothing in the shipped file or docs may hard-code a path, home directory, or
port.** The editor never needs to know where the server's Clojure source lives
on disk — at nav time it talks to a live nREPL where `clara.server.graph.*` is
already on the classpath (via `cider-jack-in` / `clojure -M`). The only path
the editor ever needs is the **elisp file itself**, and that is supplied by
the installer, not baked into the repo.

**Spike workflow (no config change at all):** open
`editor/emacs/clara-explorer.el`, `M-x eval-buffer` (or `M-x load-file` with
completion), then call the commands with `M-x`. Keep
`dotspacemacs/user-config` out of the PR.

**Durable install — two portable patterns:**

```elisp
;; A: self-locating — at the top of clara-explorer.el, works wherever the file lives
(when load-file-name
  (add-to-list 'load-path (file-name-directory load-file-name)))

;; B: Spacemacs private layer — the checkout path is a layer *variable*
;; dotspacemacs-configuration-layers
;;   '(... (clara-explorer :variables clara-explorer-root "~/src/clara-rules-explorer"))
```

Both transfer across machines because the path comes from `load-file-name` or
a per-machine layer variable, never from the repo.

**What a Spacemacs layer buys** (vs. a bare `load-file` in
`dotspacemacs/user-config`) — pure integration, no semantics change:

1. **Dependency declaration** — the layer's `packages.el` lists
   `(cider parseedn clojure-mode)`; Spacemacs installs/loads them before
   config runs. No ordering bug where `user-config` runs before `cider`
   lazy-loads.
2. **Toggle** — removing `clara-explorer` from
   `dotspacemacs-configuration-layers` fully unloads it; `user-config`
   requires manual editing.
3. **Per-mode keybindings** — `spacemacs/set-leader-keys-for-major-mode`
   scopes `g p` / `g c` / `g r` to `clojure-mode` only, matching the user's
   existing `, g f` pattern (`.spacemacs:865`). Bare `global-set-key` pollutes
   every buffer.
4. **Load-path isolation** — the layer adds
   `(add-to-list 'load-path (expand-file-name "editor/emacs" clara-explorer-root))`.

For the spike, skip the layer. For the PR, ship `editor/emacs/clara-explorer.el`
plus an optional `editor/emacs/spacemacs-layer/` skeleton
(`packages.el`, `config.el`, `keybindings.el`, `funcs.el`) and document both
install paths.

**Portability guard:** add a CI check that
`grep -R "~/Projects\|/Users/" editor/` is empty.

---

## 11. Server-side changes checklist

Minimal set for the working spike + follow-ups:

| #   | Change                                                                                            | Required for spike?                                             |
| --- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| 1   | `clara.server.graph.client` namespace (`register!`, `get-current-system`, `navigate`, `get-production-source`), Prismatic schemas | ✅ yes                                           |
| 2   | Public `server/get-current-system` accessor (keep `default-system` private)                           | ✅ yes (0-arg convenience)                                      |
| 3   | Token→type resolution in `client` built on `analyze.ctor/resolve-record-type` (§7)                | ✅ yes — reuse, do not replicate                                |
| 4   | `client/navigate` callsite-aware RHS resolution via serialized `:dynamic-insert-types-detected` (§7.4) | ✅ yes (§2.2)                                                |
| 5   | `analyze/->production-source-locations` (kondo tier, non-var productions only)                    | ⚠️ phase 1 (spike uses `cider-find-var` for var-backed targets) |
| 6   | HTTP endpoints wrapping `client/navigate`                                                         | ❌ not planned (no remote-server support)                       |

Tests: `client/navigate` gets unit tests in
`server/test/clara/server/graph/client_test.clj` against the existing demo
rules (`clara.server.tools.graph.rules.loan-app-rules` is ideal — it has
imported classes, a record constructor in the RHS, an accumulator `:from`, a
`:not`, and uses aliased `r/defrule` throughout), asserting: single-target
direct result, multi-target ordering, retract `:via` flag, alias/refer
resolution, `::keyword` resolution, and the no-match error. Add a
callsite-linked case from
`clara.server.tools.graph.rules.analyze-test-rules` (its `make-document-check`
helper wraps `laf/map->DocumentCheck`, so a cursor over `map->DocumentCheck`
resolves through the recorded callsite linkage, not a literal `insert!`
argument). Gate: `cd server && make test lint reflection-check`.

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

See `docs/planning/explorer-server-emacs-roadmap.md` for the full phase
checklist. Summary:

**Phase 0 — spike (prove the semantics):**

1. `client/navigate` (LHS producer path first) + `server/get-current-system` +
   ctor resolution via `analyze.ctor`.
2. `editor/emacs/clara-explorer.el` with §9.0–§9.4; jump via `cider-find-var`
   (var-backed targets) with the §9.5 regex fallback.
3. RHS consumer path with callsite-aware resolution (§7.4); outside-defrule
   global path (§9.7).
4. Manual test via `M-x eval-buffer` +
   `M-x clara-explorer-navigate-{producer,consumer}` against the demo rules.

**Phase 1 — solidify:**

5. `clara-explorer-refresh` / `clara-explorer-swap-session` (§9.9).
6. Unit tests (`client_test.clj`); `make test lint reflection-check`.
7. Spacemacs layer skeleton + portable install docs (§10); README snippet with
   REPL bootstrap (`(client/register! (server/start! …))`).

**Phase 2 — optional:**

8. `analyze/->production-source-locations` (kondo tier) **only if** a real
   non-var production case bites.
9. neovim client (Conjure) against the same Clojure surface.

(HTTP endpoints are not planned — no remote-server support.)

Verification gates: `cd server && make test lint reflection-check` after
server changes; `M-x eval-buffer` + manual navigation for the elisp;
`grep -R "~/Projects\|/Users/" editor/` empty.

---

## 15. Decisions (resolved)

1. **Transport = nREPL eval over the existing CIDER connection.** No HTTP
   endpoints (no remote-server support). Connection captured once per command
   via `cider-current-repl`; `cider-current-connection` does not exist.
2. **Elisp is glue (~180 lines); all semantics live in a new
   `clara.server.graph.client` namespace** that both editors share. Schemas
   are **Prismatic** (`s/defschema`/`s/defn`/`s/validate`), matching the
   server.
3. **Not LSP now.** Revisit when cross-editor cost outweighs a language-server
   adapter (§13).
4. **Source location is var metadata first** (`cider-find-var` for
   `defrule`/`defquery`), with a kondo tier for non-var productions and an
   elisp regex fallback (§8).
5. **Popover labels are always fully-qualified production names**; retraction
   coupling is surfaced via `:via :retract` as a `(retract)` suffix.
6. **Constructor resolution reuses `analyze.ctor/resolve-record-type` /
   `resolve-ctor-form`** — never re-derived in `client` (§7).
7. **Symbol identity is nREPL namespace resolution** (`ns-resolve` in
   `caller-ns`), never filename/row/col matching (§7.4).
8. **Refresh is explicit** — `clara-explorer-refresh` → `reload-annotations!`,
   `clara-explorer-swap-session` → `swap-session!`; no file-watch, no
   `after-save-hook` (§5.2, §9.9).
9. **Machine-agnostic install** — no hard-coded paths/ports; spike uses
   `eval-buffer`, durable install uses `load-file-name` or a layer variable
   (§10).
10. **Explicit elisp deps** — `Package-Requires` header + runtime `featurep`
    guards; no `dash`/`f`/`s`/`helm` hard deps (§9.0).

## 16. Open questions

- Popover ordering: sort by fq name (chosen) vs. by load order / dependency
  distance. Start with fq name; load order is trivially available if desired.
- Whether the kondo source-location tier (§8) is worth building at all, given
  non-var productions are rare — start with `cider-find-var` + regex fallback
  and add the kondo tier only if a real non-var case bites.
- Whether `clara-explorer-swap-session` should prompt for a session-rebuild
  expression every time, or cache the last one per REPL connection (leaning:
  cache, prompt with prefix arg).
