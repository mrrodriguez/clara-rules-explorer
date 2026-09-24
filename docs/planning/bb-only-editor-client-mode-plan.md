# BB-only editor client mode — plan

Status: proposal. Owner: tbd. Scope: `server/` + editor transport glue.

## Goal

Today the editor integrations (`editor/emacs/clara-explorer.el` and
`editor/neovim/lua/clara-explorer/*.lua`) answer navigation by evaluating
`clara.server.graph.client/navigate` over an nREPL session. That requires a
running JVM with the clara-rules engine and (for the HTTP surface) a Jetty
server.

We already have a *session-less* server mode — `server/start!` with
`{:registry {:root … :units […]}}` composes and rehydrates a selection of
artifact units and serves it with no live Clara session. What we do **not**
have is a mode with **no JVM at all**: the registry mode still loads
`clara.rules.engine`, `schema.core`, ring/Jetty, and the rest of the JVM
toolchain, because `server.clj` does.

This plan describes a **babashka-only editor client**: the same
`clara.server.graph.client/navigate` EDN-in/EDN-out contract, answered over the
persisted artifact registry constructs, running entirely under `bb`. No JVM, no
Jetty, no nREPL, no clara-rules engine.

The editor integrations are transport glue around one shared Clojure-side
contract. Making the client bb-capable is a `server/`-side change; the Emacs
work is one transport function, and the later neovim work is the same change in
Lua over the same contract — nothing in the client changes for it.

## Vocabulary

- **unit** — one persisted artifact set (a directory holding
  `rules-inspect-manifest.edn`, `merged-rulebase-analysis/`, the annotation
  layers, `rulebase-analysis-digest.edn`). Defined in
  [`server/docs/persisted-artifacts.md`](../server/docs/persisted-artifacts.md).
- **registry selection** — the caller-named, ordered list of units the server's
  `:registry` mode composes. Defined in
  [`server/docs/registry-architecture.md`](../server/docs/registry-architecture.md).
- **slim analysis** — what is on disk: the analysis with recomputable directions
  dropped. Its inverse is `rehydrate/rehydrate-analysis`.
- **navigate** — `clara.server.graph.client/navigate`, the editor-facing query.

## Why the current client cannot run under bb

`clara.server.graph.client` pulls the whole JVM through its `:require` list:

| require | what it drags in | needed by bb? |
| --- | --- | --- |
| `clara.server.graph.cache` | `clara.server.tools.graph.core` → `clara.rules.engine`; `memory` → working-memory inspection | no — analysis comes from disk |
| `clara.server.graph.server` | ring.adapter.jetty, reitit, muuntaja, jsonista | no |
| `clara.server.tools.graph.analyze.ctor` | live `ns-resolve` / class-loading record resolution | no — see "Token resolution" below |
| `schema.core` | Plumatic Schema | no — validation is optional at this boundary |
| `clojure.tools.logging` | slf4j | replaceable |

The one thing the client *does* need is the rehydrated `rulebase-analysis` map
its `navigate` walks: `:rules` / `:queries` summaries, `:fact-types` with the
four production directions, and `:dynamic-*-detected` callsite linkage. Every
one of those is either **on disk already** or **recomputable from what is on
disk** — and `server/bin/annotations_report.bb` already proves the latter.

## Feasibility assessment

The question is not "can bb read the artifacts" — `annotations_report.bb`
already does. The question is whether the *specific* inputs `navigate` consumes
survive to disk or are derivable there. The answer is yes, with three
documented degradations.

### What `navigate` needs, and where it lives offline

| `navigate` input | JVM source | offline source | fidelity |
| --- | --- | --- | --- |
| global producers of a type | `:fact-types :inserted-by-rules` / `:retracted-by-rules` (rehydrated) | recompute closure over `:insert-types`/`:retract-types` (production-index.edn) **closed over ancestors** (fact-types.edn) | exact — this is the same closure `rehydrate/->usage-maps` runs, and `annotations_report.bb producers` already does |
| global consumers of a type | `:fact-types :used-by-rules` / `:used-by-queries` | recompute closure over `:lhs-types` **closed over descendants** | exact — `annotations_report.bb consumers` |
| type hierarchy (ancestors/descendants) | `:fact-types :ancestors` / `:descendants` | `fact-types.edn` `:ancestors` is already closed; transpose for descendants | exact |
| scoped upstream/downstream targets | production `:upstream`/`:downstream` deps **filtered by `:match`** | `dep-graph.edn` `:upstream` (names only); **`:match` is not persisted** | **degraded** — see below |
| RHS constructor-token resolution | `resolve-token` + `ctor/resolve-record-type` + `:dynamic-*-detected` callsites | annotation layers carry `:constructor-sym` / `:fact-type` / `:resolved-types` (readable in bb); live `ns-resolve`/class-loading is not | **degraded** — see below |
| production source locations | var metadata via `ns-resolve` | none (no rule namespaces loaded in bb) | **degraded** — `:var? false`, editor regex/kondo fallback |

### Degradation 1 — scoped `:match` type-bridge precision

`navigate`'s scoped path (`lhs-navigate` / `rhs-navigate`) prefers
`deps->targets` over `:upstream`/`:downstream`, which keeps only deps whose
`:match` pair names the token's type. `:match` is computed by
`core/matching-type-pairs` from the live `type-analysis-map` (raw
produced/consumed/retract types) and is dropped by `slim` — it is **not** in
`dep-graph.edn`, and `rehydrate/rehydrate-production` states it "is not
rebuilt — it needs raw types the persisted analysis does not carry."

This is a smaller loss than it looks, because the client already has the
fallback for exactly this case: when `deps->targets` returns nothing, both
`lhs-navigate` and `rhs-navigate` call `global-producer-targets` /
`global-consumer-targets` — the type-closure sets, which **are** derivable
offline. So the bb path can run the global closure unconditionally and produce
the same answer set the JVM client already produces as its scoped fallback.
The only thing lost is the per-edge `:match` attribution on the *narrowed*
subset (when a production has several type-bridge edges, bb returns the type's
full producer/consumer set rather than only the edges matching the cursor
token).

### Degradation 2 — live token resolution

`resolve-token` / `resolve-symbol-type` / `token->fq-sym` resolve symbol and
constructor tokens against the live caller namespace (`ns-resolve`,
`ctor/resolve-record-type` with hyphen→underscore class-load checks). bb has
none of that.

The parts that survive:

- keyword tokens → the literal `":ns/name"` string (already pure),
- string tokens → `pr-str` (already pure),
- the `:dynamic-insert-types-detected` / `:dynamic-retract-types-detected`
  callsite linkage — `callsite-matches-token?` matches `:constructor-sym` /
  `:fact-type` / `:fact-type-spec` names against the token string. That linkage
  is authored annotation data, persisted in the annotation layers, and readable
  in bb (it is exactly what `annotations_report.bb gaps`/`types` already read).

The parts that do not survive: resolving a bare record/Java constructor symbol
(`->X`, `map->X`, `X.`, `new X`) to a class name **without** a prior callsite
linkage entry. In bb these tokens fall back to a string match against the
callsite data and, failing that, the unresolved-symbol sentinel — which is the
same "no fact type found under cursor" outcome the JVM path gives for an
unresolvable token.

Net: LHS navigation (which resolves the fact-type keyword/symbol written in the
condition) and global navigation over keyword/string tokens are unaffected.
RHS navigation over a *constructor call* depends on the annotation callsite
linkage, which is present whenever the analysis pass resolved it — i.e. the
common case.

### Degradation 3 — source locations

`get-production-source` returns var metadata (`:file`/`:line`/`:column`) from
`ns-resolve`. bb loads no rule namespaces, so this is always `:var? false`.
Both editors already handle that: the elisp
`clara-explorer--goto` calls `cider-find-var` only when `:var?` is true, else
`clara-explorer--goto-fallback` (regex search in the ns file); the Lua `jump`
module has the same fallback. No editor logic changes for this — the bb client
just always emits the `:var? false` shape.

### Verdict

**Feasible.** The navigation questions the editor asks — "who produces/consumes
the type under the cursor, and where do I jump" — are answerable from the
persisted artifact set with the three degradations above. The two closures
(ancestors for producers/retractors, descendants for consumers) are the only
part that is easy to get wrong, and `annotations_report.bb` already implements
both directions correctly; `rehydrate`'s `->usage-maps` is the JVM reference
for the same computation.

## Design

### 1. One reader-conditional mechanism: `:bb`, not `:clj`

The stated mechanism — ".cljc with `:clj` read conditionals" — is not quite
right, and getting it wrong silently ships the JVM into bb. Empirically (bb
v1.13.224):

```clojure
;; in babashka, load-file of a .cljc:
#?(:clj :yes-clj)   ;=> :yes-clj  (bb *also* satisfies :clj)
#?(:bb :yes-bb)     ;=> :yes-bb  (bb satisfies :bb)
#?(:clj :a :bb :b)  ;=> :a       (:clj wins — first matching feature)
#?(:bb :a :clj :b)  ;=> :a in bb, :b on the JVM  ← the idiom to use
```

bb satisfies **both** `:clj` and `:bb`; the JVM satisfies only `:clj`.
Therefore the only conditional that distinguishes them is one where `:bb` comes
**first**:

```clojure
#?(:bb  (require '[clojure.edn :as edn]
                 '[clojure.set :as set]
                 '[clojure.string :as str])
   :clj (require '[clara.rules.engine :as eng]
                 '[schema.core :as s]
                 '[clojure.tools.logging :as log]))
```

A `.cljc` written this way loads under bb with none of the JVM deps touched.
(This applies to `load-file`/`require`, i.e. code. The EDN *data* files are
read with `clojure.edn/read-string`, which has no reader conditionals — no
interaction.)

### 2. Split the pure `navigate` out of `clara.server.graph.client`

Extract the navigation logic into a dependency-free `.cljc` namespace (proposed:
`clara.server.graph.client.navigate`), parameterized over two things the JVM
shell and the bb script supply differently:

- the **analysis map** — on the JVM, the rehydrated in-memory analysis from
  `cache/get-rulebase-analysis`; under bb, the slim analysis rehydrated in pure
  Clojure (see §3). The pure code only needs the rehydrated shape:
  `:rules`/`:queries` summaries with `:lhs-types`/`:insert-types`/
  `:retract-types`/`:dynamic-*-detected`, and `:fact-types` with
  `:used-by-*`/`:inserted-by-rules`/`:retracted-by-rules`.
- a **token resolver** — under `#?(:clj …)` the live
  `ns-resolve`/`ctor/resolve-record-type` path; under `#?(:bb …)` the
  keyword/string-literal + callsite-string-match path.

The navigation functions themselves (`lhs-navigate`, `rhs-navigate`,
`navigate-global`, `deps->targets`, the global producer/consumer targets) are
pure and condition-free. Because the bb analysis has no `:match` on its deps,
`deps->targets` naturally returns nothing and the code falls through to the
global closure — the existing fallback path becomes the bb primary path with
**no code change to the navigation functions themselves**.

`clara.server.graph.client` (the JVM `.clj` shell) keeps what is JVM-only:
system registration (`register!`, `get-current-system`),
`get-production-source` (var metadata), `swap-session!` /
`register-session-swap-opts-fn`, and the `schema.core` validation of
`NavigateInput`. It delegates the actual navigation to the shared `.cljc`.
This keeps the existing nREPL contract byte-for-byte identical for editors that
still use it.

### 3. Pure-Clojure rehydration of the slim analysis

`clara.server.tools.graph.artifacts.rehydrate` cannot be `require`d under bb
(it pulls `ann.merge`, `conditions`, `serialize`, `schema`). But its *logic* is
pure, and `annotations_report.bb` already reimplements the two hard parts. The
bb backend needs a small, dep-free reimplementation of exactly:

- transpose `:ancestors` → descendants (`annotations_report.bb ->descendants`),
- the four usage closures over `production-index.edn` + `fact-types.edn` —
  producers/retractors closed over **ancestors**, consumers closed over
  **descendants** — which `annotations_report.bb` implements as `producers` /
  `consumers`, and `rehydrate/->usage-maps` states as the reference,
- `:dep-graph :downstream` = transpose of `:upstream` (for the `edges` shape,
  not required by `navigate` itself).

To keep this from drifting from the JVM definition, mirror the `layout.cljc`
pattern: put the shared closure definitions in a `.cljc` file both
`rehydrate.clj` and the bb backend load, or (smaller first step) reimplement
them in the bb script and pin parity with a test that runs
`annotations_report.bb`'s `producers`/`consumers` against
`rehydrate`'s `->usage-maps` over the checked-in example registry.

### 4. A bb entry point with the same EDN contract

A new script (proposed `server/bin/editor_client.bb`, beside
`annotations_report.bb`) that is the bb twin of `navigate`:

```
bb server/bin/editor_client.bb <unit-dir> <navigate-input-edn>
```

- reads `<navigate-input-edn>` (the same `NavigateInput` map the editors build
  today: `:production :side :caller-ns :token`),
- reads the unit's `production-index.edn`, `fact-types.edn`, `meta.edn`
  (`:slim :unknown-fact-types`), and the annotation layers (for
  `:dynamic-*-detected` callsite linkage) via `layout.cljc`,
- rehydrates the four directions in pure Clojure (§3),
- prints the EDN `NavigateResponse` to stdout (the same shape the editors'
  parseedn/EDN decoders already consume); errors print `{:error "…"}`.

First milestone operates on **one unit directory** (a repo's artifact dir, or a
unit already materialized by `flow/compose-persist!` — both are the exact
directory `annotations_report.bb` already reads). This sidesteps registry
composition entirely: the composed unit *is* the "configured rulebase analysis
registry construct" handed to the editor.

Second milestone (optional) is a live registry selection: accept
`{:root … :units […]}` and reimplement `selection/->selection` +
`compose/->composed-analysis` in the dep-free `.cljc`, producing the same
composed slim analysis on the fly. This is more work and only needed if the
editor must compose ad-hoc selections rather than point at a persisted unit.

### 5. Editor transport

- **Emacs**: add a `clara-explorer--eval-bb` transport (shell out to
  `bb server/bin/editor_client.bb`, parse stdout with `parseedn-read-str`)
  selected by a defcustom (e.g. `clara-explorer-transport` = `nrepl` | `bb`),
  and a defcustom for the unit dir / registry root. Reuse the existing
  `clara-explorer--navigate-code` map builder unchanged — only the eval
  function changes. `swap-session!`/`refresh` are nREPL-only and no-op (or
  message) in bb mode.
- **neovim**: the same change later, in `conjure.lua`'s `eval_edn` — an
  alternate executor that shells out instead of `conjure.eval`. Nothing in the
  client contract changes, which is why this is a later, independent step.

## What changes where

| file | change |
| --- | --- |
| `server/src/clara/server/graph/client/navigate.cljc` (new) | dep-free `navigate` + navigation fns + `#?(:bb/:clj)` token resolver |
| `server/src/clara/server/graph/client.clj` | becomes the JVM shell: keeps `register!`, `get-production-source`, `swap-session!`, schema validation; delegates to the `.cljc` |
| `server/bin/editor_client.bb` (new) | bb entry: read unit artifacts, rehydrate (§3), call the `.cljc` `navigate`, print EDN |
| `server/src/clara/server/tools/graph/artifacts/layout.cljc` | unchanged — already the shared dep-free vocabulary both sides load |
| `server/docs/persisted-artifacts.md` | note the new offline reader beside `annotations_report.bb` |
| `editor/emacs/clara-explorer.el` | add bb transport + config defcustoms |
| `editor/neovim/lua/clara-explorer/*.lua` | later, same transport change |

## Phasing

1. **Extract + parity (no bb yet).** Move navigation into the `.cljc`, have the
   JVM `client.clj` delegate to it, and pin parity with the existing
   `client`/`server` navigation tests. No behavior change; this de-risks the
   split.
2. **bb backend + entry script.** Implement §3–§4 for a single unit dir; verify
   `editor_client.bb` against `annotations_report.bb`'s `producers`/`consumers`
   and against `rehydrate` over the checked-in example registry
   (`clara.server.tools.graph.artifacts.regen-example/example-out-dir`).
3. **Emacs transport.** Wire the bb transport behind a defcustom; leave nREPL
   as the default.
4. **Registry selection (optional).** Add `{:root … :units […]}` composition in
   bb if ad-hoc selection is wanted.
5. **neovim.** Mirror step 3 in Lua.

## Risks and mitigations

- **Closure direction wrong in the bb reimplementation.** The ancestors/descendants
  split is the known footgun (`slim`'s header and `registry-architecture.md`
  both call it out). Mitigation: share the closure definitions via a `.cljc`
  both `rehydrate` and bb load, or pin a parity test over the golden registry.
- **`.cljc` accidentally loads a JVM dep in bb.** Enforced by the `#?(:bb …
  :clj …)` ordering (§1); additionally a bb smoke test that
  `require`s the `.cljc` under bb with no classpath and asserts it loads.
- **Token resolution regressions.** The bb resolver is a strict subset; symbol
  tokens that only the live classpath could resolve become "no fact type found"
  instead of a wrong jump. Acceptable — the editor message is the same shape
  the JVM path already produces for unresolvable tokens.
- **`:match`-less scoped navigation returns a superset.** Documented in
  Degradation 1; the popover already handles multiple targets, so a wider set
  degrades to "pick which producer/consumer," not a wrong answer.
- **`get-production-locations` / `swap-session!` / `refresh` have no bb twin.**
  They are nREPL-only by nature; the editors gate them on transport.

## Open questions

1. Should the bb mode read **one unit dir** first (my recommendation), or go
   straight to **registry selection** (`{:root … :units […]}`)?
2. Where does the editor get the unit dir / registry root from? A per-project
   config var, a `.dir-locals.el`/`.nvim.lua` value, or an env var — matching
   the "no hard-coded paths" rule both editors already follow.
3. Keep `schema.core` validation of `NavigateInput` in the shared `.cljc`
   (conditional), or drop validation in bb mode and rely on the editors'
   well-formed maps?

## Related

- [`../server/docs/persisted-artifacts.md`](../server/docs/persisted-artifacts.md) — the on-disk artifact set and the existing `annotations_report.bb` reader
- [`../server/docs/registry-architecture.md`](../server/docs/registry-architecture.md) — units, selection, compose, and the `:registry` server mode
- [`explorer-graph-api.md`](explorer-graph-api.md) — the HTTP routes, including `:registry`
- [`explorer-editor-navigation-emacs.md`](explorer-editor-navigation-emacs.md) / [`explorer-editor-navigation-neovim.md`](explorer-editor-navigation-neovim.md) — the transport contracts
