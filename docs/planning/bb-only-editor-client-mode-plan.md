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
persisted artifact registry constructs, running entirely under `bb`. The
editor's repl (CIDER/Conjure) stays connected — it is still used to resolve
aliased symbols to fq (step 0b) — but **no clara session is loaded and no
Jetty server is started on it**; navigation reads the registry unit data with
bb instead.

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
- **shared namespace** — a dep-free namespace under
  `clara.server.tools.graph.shared.*` that both the JVM and bb `require`. See
  "Reuse between JVM and bb".

## Why the current client cannot run under bb

`clara.server.graph.client` pulls the whole JVM through its `:require` list:

| require | what it drags in | needed by bb? |
| --- | --- | --- |
| `clara.server.graph.cache` | `clara.server.tools.graph.core` → `clara.rules.engine`; `memory` → working-memory inspection | no — analysis comes from disk |
| `clara.server.graph.server` | ring.adapter.jetty, reitit, muuntaja, jsonista | no |
| `clara.server.tools.graph.analyze.ctor` | live `ns-resolve` / class-loading record resolution | only for bare/aliased symbol tokens — see below |
| `schema.core` | Plumatic Schema | optional at this boundary |
| `clojure.tools.logging` | slf4j | replaceable |

The one thing the client *does* need is the rehydrated `rulebase-analysis` map
its `navigate` walks: `:rules` / `:queries` summaries, `:fact-types` with the
four production directions, and `:dynamic-*-detected` callsite linkage. Every
one of those is either **on disk already** or **recomputable from what is on
disk** — and `server/bin/annotations_report.bb` already proves the latter.

## Feasibility assessment

The question is not "can bb read the artifacts" — `annotations_report.bb`
already does. The question is whether the *specific* inputs `navigate` consumes
survive to disk or are derivable there. The answer is yes.

### What `navigate` needs, and where it lives offline

| `navigate` input | JVM source | offline source | fidelity |
| --- | --- | --- | --- |
| global producers of a type | `:fact-types :inserted-by-rules` / `:retracted-by-rules` (rehydrated) | recompute closure over `:insert-types`/`:retract-types` (production-index.edn) **closed over ancestors** (fact-types.edn) | exact |
| global consumers of a type | `:fact-types :used-by-rules` / `:used-by-queries` | recompute closure over `:lhs-types` **closed over descendants** | exact |
| type hierarchy (ancestors/descendants) | `:fact-types :ancestors` / `:descendants` | `fact-types.edn` `:ancestors` is already closed; transpose for descendants | exact |
| scoped upstream/downstream targets | production `:upstream`/`:downstream` deps **filtered by `:match`** | `dep-graph.edn` `:upstream` (names only); **`:match` is not persisted** | equivalent — see below |
| RHS constructor-token resolution | `resolve-token` + `ctor/resolve-record-type` + `:dynamic-*-detected` callsites | annotation layers carry `:constructor-sym` / `:fact-type` / `:resolved-types` (readable in bb); live `ns-resolve`/class-loading is not | equivalent for fq tokens — see below |
| production source locations | var metadata via `ns-resolve` | none (no rule namespaces loaded in bb) | `:var? false` — editor regex/kondo fallback |

### `:match` is not needed for navigation

`:match` is the per-edge type-bridge list attached to a production's
`:upstream`/`:downstream` deps by `core/matching-type-pairs` (each entry is a
`{producer-type, consumer-type, :via?}` pair; `:via :retract` marks retraction
coupling). It is computed from the live `type-analysis-map` (raw
produced/consumed/retract types) and dropped by `slim` — it is not in
`dep-graph.edn`, and `rehydrate/rehydrate-production` states it "is not
rebuilt."

The client uses `:match` in exactly one place — `deps->targets`, which filters
the scoped `:upstream` (LHS) / `:downstream` (RHS) deps to those linked through
the cursor token's type. But that filtered set is **the same set the global
closure already yields**, because the dep-graph edge itself is built from the
same closure:

- LHS on type `T`: an upstream producer `R` is linked to `P` by a `:match` pair
  whose `:consumer-type` is `T` exactly when `R` inserts/retracts `T` or a
  descendant of `T` — which is precisely
  `global-producer-targets T` (`:inserted-by-rules` ∪ `:retracted-by-rules`,
  the ancestors closure). Same set.
- RHS on type `T`: a downstream consumer `C` is linked by a `:match` pair whose
  `:producer-type` is `T` exactly when `C`'s declared LHS type is `T` or an
  ancestor of `T` — which is precisely `global-consumer-targets T`
  (`:used-by-*`, the descendants closure). Same set.

The retract distinction survives too: `global-producer-targets` reads
`:inserted-by-rules` and `:retracted-by-rules` separately and tags each dep
`:insert`/`:retract` — the same information `:match`'s `:via :retract` encodes.

The client *already* takes this path whenever `:match` is absent or non-matching
(both `lhs-navigate` and `rhs-navigate` fall through to the global closure when
`deps->targets` returns empty, and the hierarchy-reached `:else` branch goes
straight to the global closure). So the bb path can use the global closure
unconditionally and produce the identical target set — with **no change to the
navigation functions themselves**. `:match` remains useful only to the HTTP
API's edge-list display (`GET /v1/rules/:fq-name`), which the editors do not
consume for navigation.

**This is a simplification opportunity.** Since the scoped `:match` path and the
global closure yield the same target set, the client can **drop the scoped path
entirely** and always answer from `:fact-types` (`global-producer-targets` /
`global-consumer-targets`). That removes `:upstream`/`:downstream`/`:match` from
the navigation code path (they remain only for the HTTP API), collapses
`navigate` to one code path the bb port reimplements exactly, and is a candidate
for its own pre-bb cleanup change — pinned by a parity test that asserts
scoped ≡ global over every fixture *before* the scoped path is deleted. The one
thing to prove first: that a declared LHS/RHS type is always a key of
`:fact-types`, so the global lookup never returns empty where the scoped
`:match` lookup found targets (declared types are "known" by construction, but
pin it rather than assume it).

### Token resolution: the live part is only for *unqualified* symbols

`resolve-token` has three cases:

1. **keyword / string tokens** — already pure (`(str form)` / `(pr-str form)`).
   No resolution at all. Unaffected by the lack of a JVM.
2. **the `:dynamic-*-detected` callsite linkage** — `callsite-matches-token?`
   compares the token against `:constructor-sym` / `:fact-type :name` /
   `:fact-type-spec`. That linkage is authored annotation data, persisted in the
   annotation layers, and readable in bb (`annotations_report.bb gaps`/`types`
   already read these exact fields). Unaffected.
3. **`ns-resolve` / `ctor/resolve-record-type`** — the only JVM-dependent part.

Case 3 exists for one reason: to turn a **bare or aliased** symbol into its
fully-qualified name, and to *verify* a `->X`/`map->X` form is a record
constructor rather than a helper fn like `->fact`. Concretely:

- alias/import resolution — `Loan` (imported `com.example.Loan`) → fq name;
- record-ctor normalization — `->loan` → `my.ns.Loan` (strip prefix,
  hyphen→underscore, fq);
- class-load check — reject a constructor-named helper fn whose derived class
  does not exist.

If the token is **already fully qualified** (`com.example.Loan`, `my.ns/->Loan`),
none of that is needed: the fq name is literally in the token, and the syntactic
normalization (strip `->`/`map->`, `X.`/`new X` handling, hyphen→underscore) is
pure — doable in bb. The class-load check is the only genuinely JVM-dependent
step, and it is a heuristic the annotation callsite data **supersedes**: the
analysis only records a `:constructor-sym`/`:fact-type` for constructors that
actually resolved to fact types during analysis, so matching the token string
against those recorded fq names is *more* reliable than re-deriving and
load-checking at navigation time.

**Where the editor can help.** Today the emacs client does *not* resolve
anything before sending: `clara-explorer--token-at-point` extracts the raw
buffer text and `clara-explorer--navigate-code` ships it verbatim with
`:caller-ns` (`cider-current-ns`); all alias/symbol resolution happens in
`client.clj`'s `resolve-token` / `token->fq-sym` via `ns-resolve` /
`ctor/resolve-record-type`. `client-test` pins this exactly:
`test-global-consumers-callsite-linked-ctor` sends the *aliased*
`laf/map->DocumentCheck` and expects it resolved against `:caller-ns`.

Because the editor has a live repl (CIDER/Conjure), it can resolve an aliased
symbol (`x/->loan` → `real.ns/->loan`) to fully-qualified form **before** calling
`navigate` — a concern that lives with the repl, orthogonal to whether the
navigation query runs on the JVM or bb. If that resolution moves into the
editor, the client's token handling collapses to pure syntactic normalization
(keyword → `":ns/name"`, string → `pr-str`, fq symbol/ctor-form → name),
identical on both runtimes. `:caller-ns` stops being needed *for resolution*
(it is used only for that today), but we keep passing it — harmless context the
client may want later.

Both editors already have the repl connection this needs: CIDER for Emacs
(`cider-nrepl-sync-request:eval` over the buffer ns), and Conjure for neovim —
`conjure.lua` gates every command on `conjure.connected()`, exposes
`current_ns()` (`conjure.extract.context()`), and evals over
`conjure.eval["eval-str"]`, so a symbol-resolution eval is available in both.

The one caveat: alias resolution needs a live repl. If bb-only mode must work
with **no repl at all**, aliased symbols cannot be resolved unless we start
persisting namespace alias maps (today dropped as `:ns-deps`, deliberately).
Recommendation: make alias resolution the editor's job over its repl, and treat
"zero-repl" as an explicit non-goal for now.

### Source locations are `:var? false`, and both editors already handle it

`get-production-source` returns var metadata from `ns-resolve`; bb loads no rule
namespaces, so this is always `:var? false`. The elisp
`clara-explorer--goto` calls `cider-find-var` only when `:var?` is true, else
`clara-explorer--goto-fallback` (regex search in the ns file); the Lua `jump`
module has the same fallback. No editor logic changes — the bb client emits the
`:var? false` shape and the existing fallback takes over.

### Verdict

**Feasible.** The navigation questions the editor asks — "who produces/consumes
the type under the cursor, and where do I jump" — are answerable from the
persisted artifact set with no JVM, no live session, and no loss of the answer
sets. The only hard-to-get-right part is the two closures (ancestors for
producers/retractors, descendants for consumers), and the reuse mechanism below
is precisely how we keep that from drifting.

## Reuse between JVM and bb: `shared.` namespaces + `bootstrap.bb`

Rather than reimplement navigation in a bb script and keep it in sync by hand,
we share real namespaces. Two mechanisms, in order of preference:

### 1. `shared.` namespaces — the primary reuse mechanism

A dep-free namespace under `clara.server.tools.graph.shared.*` is the home for
logic both the JVM and bb `require`. Convention: **`clara.server.tools.graph.shared.<name>`
holds logic extracted from `clara.server.tools.graph.<…>.<name>`** — one segment
`shared.` lower, so the extracted piece and its home are visually paired.

The discipline that makes it safe: **a `shared.` namespace must not `:require`
anything JVM-only.** This is self-enforcing — bb `require` of a namespace that
transitively requires `clara.rules.engine` fails with "Could not locate
clara/rules/engine… on classpath" — and it is pinned by a bb smoke test that
`require`s every `shared.` namespace with no classpath beyond `src`.

`clara.server.tools.graph.artifacts.layout` is the precedent: a dep-free file
whose docstring says "no dependencies is its whole reason to exist," loaded by
both sides. The convention generalizes that from one file to a namespace tree.

Candidate extractions (final naming to implementation):

| shared namespace | extracted from | holds |
| --- | --- | --- |
| `…graph.shared.hierarchy` | `annotations_report.bb` (`->descendants`, `with-hierarchy`) + `rehydrate` + `artifacts.hierarchy` | the transpose and the two closure directions — the one part that is easy to get wrong |
| `…graph.shared.rehydrate` | `rehydrate`'s `->usage-maps` / `->downstream` | the four usage closures + `:downstream` transpose, over slim-shaped maps |
| `…graph.shared.navigate` | `client/navigate` + its private fns | pure navigation over a rehydrated analysis map |
| `…graph.shared.tokens` | `client`'s `resolve-token` fns | keyword/string/ctor-form syntactic normalization + callsite string matching |
| `…graph.shared.selection` | `selection/->selection` | the shared merge preamble: read + narrow + assert-compatible + unioned hierarchy + coverage |
| `…graph.shared.compose` | `compose/->composed-analysis` | production merge by fq name, fact-type union, dep-graph recompute |

### 2. Reader conditionals — only for the genuinely-different boundary

Where the JVM and bb behavior *must* differ (the live `ns-resolve`/class-load
step in token resolution, the client shell's system/var-metadata access), use a
`.cljc` with the **`:bb` branch first**:

```clojure
#?(:bb  (require '[clojure.edn :as edn] '[clojure.set :as set] '[clojure.string :as str])
   :clj (require '[clara.rules.engine :as eng] '[schema.core :as s] '[clojure.tools.logging :as log]))
```

Empirically (bb v1.13.224), bb satisfies **both** `:clj` and `:bb`; the JVM
satisfies only `:clj`. So only a conditional with `:bb` **first** distinguishes
them — `#?(:clj …)` alone would still ship the JVM into bb. This also holds under
`require` via `babashka.classpath/add-classpath` (verified), not just
`load-file`.

This is the *exception*, not the norm: shared logic should be condition-free and
live in `shared.` namespaces; reader conditionals are reserved for the boundary
seams.

### 3. `bootstrap.bb` — how bb `require`s the shared namespaces

`server/bin/bootstrap.bb` puts `server/src` (and prismatic/schema, if a boundary
wants `NavigateInput` validation parity) on a bb script's classpath:

```clojure
;; server/bin/bootstrap.bb
(require '[babashka.classpath :as cp]
         '[babashka.deps :as deps]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn])

(let [project-root (fs/parent (fs/parent (fs/canonicalize *file*)))
      deps (:deps (edn/read-string (slurp (str (fs/path project-root "deps.edn")))))]
  (deps/add-deps {:deps (select-keys deps '[prismatic/schema])})
  (cp/add-classpath (str (fs/path project-root "src"))))
```

A bb script then does:

```clojure
(load-file (str (fs/path (fs/parent (fs/canonicalize *file*)) "bootstrap.bb")))
(require '[clara.server.tools.graph.shared.navigate :as navigate])
```

`load-file`d (not `require`d) because bootstrap is what makes `require` work in
the first place. The schema version is read from `deps.edn` so the JVM and bb
cannot pin different ones; only the namespaces a script actually requires are
loaded, which is what keeps this safe.

Notes:

- schema stays **out of `shared.` namespaces** — it is a bootstrap-provided
  convenience for boundary validation, not a dependency of shared logic. Shared
  namespaces operate on plain maps.
- `bootstrap.bb` + `add-classpath` could eventually supersede the
  `server/bin/layout.cljc` symlink that `annotations_report.bb` uses today
  (`require` `clara.server.tools.graph.artifacts.layout` directly). Optional
  cleanup; the symlink works and is already pinned.

## Step 0b in detail — editor-side token resolution

**Goal.** The editor resolves a symbol token to its fully-qualified form before
calling `navigate`, so the client's live `ns-resolve`/class-loading becomes a
back-compat escape hatch rather than the contract. The editor owns this because
it has the repl; the client keeps only pure normalization.

**CIDER due diligence.** There is no CIDER function that returns the fq name of
an arbitrary symbol (var *or* class). What exists in `cider-resolve.el`:

- `cider-resolve-alias` — ns alias → fq namespace. Covers the `x/` prefix of
  `x/->loan`, but nothing else.
- `cider-resolve-var` — var metadata, resolving the alias prefix and `refers`,
  then falling back to `clojure.core`. Returns a metadata *dict*, not an fq
  name string.

Both read the track-state ns cache (`cider-repl-ns-cache`) and never contact
the server; the cache holds `aliases`/`interns`/`refers` but **not `imports`**,
so they resolve **vars, not Java classes** — `DocumentCheck` (an imported
class) is invisible to them. `cider-symbol-at-point` explicitly does not expand
`::`. `cider-var-info` queries the server but is var-only. Conclusion: use a
nREPL eval, not these.

**Conjure due diligence.** The Clojure client exposes only generic
`eval-str` (plus `def-str`/`completions`/go-to-def over the `info`/`lookup`
ops). `server.eval` sends `ns = opts.context`, so an eval runs in the buffer
ns. There is no resolve-symbol helper; the `info` op returns `:ns`/`:name` for
a *var* it can resolve, but there is no uniform var-or-class resolver.

**Mechanism.** Both editors eval the *same* self-contained Clojure form over
their repl (clojure.core only — no explorer dependency, so it
also works when the editor's repl is the user's project repl without the
explorer on its classpath):

```clojure
(let [ns-sym (symbol CALLER_NS)
      form   (binding [*read-eval* false *ns* (find-ns ns-sym)]
               (try (read-string TOKEN) (catch Exception _ nil)))]
  (cond
    (symbol? form)
    (let [v (try (ns-resolve ns-sym form) (catch Exception _ nil))]
      (cond
        (class? v) (.getName ^Class v)                                   ; imported/aliased class -> fq
        (var? v)   (str (symbol (str (ns-name (:ns (meta v)))) (name form))) ; var -> fq var symbol
        :else      (str form)))                                          ; unresolvable -> pass through
    (keyword? form) (str form)   ; ::auto-resolved under *ns* by read-string
    :else TOKEN))                 ; string/vector — the client normalizes
```

`:caller-ns` is interpolated as `CALLER_NS`, the raw token as `TOKEN`. This
form resolves plain symbols (and `::` keywords), but **not** the Java
constructor syntaxes. Here is the concrete gap, using `DocumentCheck.` (the
`test-consumer-java-ctor-tokens` case):

`read-string` turns `"DocumentCheck."` into the symbol `DocumentCheck.` (`.` is
a valid symbol character, so it stays part of the name). `ns-resolve` on
`DocumentCheck.` finds nothing — no var or class is named that — so the
`symbol?` branch hits `:else` and returns the token unchanged, still
`"DocumentCheck."`.

- JVM client: fine — `resolve-ctor-token` sees the trailing `.`, strips it to
  `DocumentCheck`, and `ns-resolve`s the *imported class* to
  `clara.server.tools.graph.rules.loan_app_facts.DocumentCheck`.
- bb client: no live `ns-resolve`/imports — `DocumentCheck.` cannot be resolved,
  so it degrades to "no fact type found".

To close it, the form must *first* normalize the constructor syntax (strip the
trailing `.`, and pull the class out of `new X` / `X/new`) **before**
`ns-resolve` — exactly the prefix-stripping `resolve-ctor-token` does today.
Those few extra lines are why the full form is a port of
`client/resolve-token` (and why it then needs `clojure.string/replace` for the
`-`→`_` record-name step), and why it lands in
`clara.server.tools.graph.shared.tokens` so the editor form and the client
cannot drift.

**Editor changes.**

- Emacs: add `clara-explorer--resolve-token` (eval the form over CIDER, return
  the fq token or fall back to the raw token on `nil`/error) and call it in
  `clara-explorer--navigate` after `clara-explorer--token-at-point`, before
  building the navigate map. `:caller-ns` is still passed.
- neovim: add the same eval in `conjure.lua` (a `resolve_token` helper over
  `eval-str`), called from `init.lua`'s `M.navigate` before `navigate_code`.
  Because Conjure eval is async, this makes the navigate flow one extra nested
  eval (resolve → navigate), which `conjure.eval_edn`'s callback shape already
  supports.

**What stays in the client.** `resolve-token` remains as-is for back-compat
(nREPL callers may still send raw tokens; the JVM branch resolves them). The bb
client assumes fq-in and does only pure normalization + callsite string
matching. The residual risk is a bare/aliased symbol that neither the editor
resolved nor any callsite linkage covers — the same "no fact type found" shape
the JVM path already emits.

## Design

Two simplifications from the feasibility section land here first, because they
shrink the port to a single, pure code path:

- **global-closure-only navigation** — drop the scoped `:match` path, and
- **editor-side token resolution** — the editor resolves aliased symbols to fq
  over its repl before calling `navigate`.

With both, `shared.navigate` is pure over a rehydrated analysis map, and the
only `#?(:bb/:clj)` seam left is the optional live-resolve escape hatch for the
nREPL transport.

1. **Extract `shared.` namespaces** (§Reuse 1) — move the pure navigation body
   and the rehydration closures out of `clara.server.graph.client` /
   `clara.server.tools.graph.artifacts.rehydrate` into
   `clara.server.tools.graph.shared.*`. Both the JVM side and bb `require` the
   same code; the closures are defined once, so the drift risk disappears.

2. **Keep `clara.server.graph.client` as the JVM shell** — it keeps what is
   JVM-only: system registration (`register!`, `get-current-system`),
   `get-production-source` (var metadata), `swap-session!` /
   `register-session-swap-opts-fn`, `schema.core` validation of `NavigateInput`.
   It delegates navigation to `shared.navigate`, supplying the rehydrated
   in-memory analysis from `cache/get-rulebase-analysis`. With editor-side token
   resolution (§0b) the live `ns-resolve`/`ctor` resolver becomes an optional
   back-compat escape hatch (the `:clj` branch of `shared.tokens`) for nREPL
   callers that still send raw tokens, not a required part of the contract.

3. **A bb entry point with the same EDN contract** — `server/bin/editor_client.bb`
   (beside `annotations_report.bb`) is the bb twin of `navigate`:

   ```
   bb server/bin/editor_client.bb '{:root "…" :units [{:repo "…"} …]}' <navigate-input-edn>
   ```

   It `load-file`s `bootstrap.bb`, composes the selected units on the fly —
   `shared.selection` → `shared.compose` → `shared.rehydrate` (the pure forms
   of `selection/->selection`, `compose/->composed-analysis`,
   `rehydrate/rehydrate-analysis`) — then calls `shared.navigate/navigate` with
   the bb token resolver and prints the EDN `NavigateResponse` to stdout; errors
   print `{:error "…"}`. A fresh subprocess per query re-reads the selected
   units every time, so reload-on-change is free (Decision 3). Incremental
   starting point: implement against a single-unit selection
   (`{:units [{:repo …}]}`, or a unit materialized by `flow/compose-persist!`)
   first, then generalize to multi-unit composition.

4. **Editor transport** — Emacs: add a `clara-explorer--eval-bb` transport
   (shell out to `bb server/bin/editor_client.bb`, parse stdout with
   `parseedn-read-str`) behind a defcustom, plus a prompt for the registry
   selection (`:root` + `:units`) defaulting from `CLARA_RULES_REGISTRY` (Decision
   4); reuse the existing `clara-explorer--navigate-code` map builder unchanged.
   `swap-session!`/`refresh` are nREPL-only and no-op in bb mode.
   Neovim: the same change later in `conjure.lua`'s `eval_edn` — an alternate
   executor that shells out instead of `conjure.eval`. Nothing in the client
   contract changes.

## What changes where

| file | change |
| --- | --- |
| `server/src/clara/server/tools/graph/shared/hierarchy.cljc` (new) | transpose + the two closures, extracted from `annotations_report.bb` / `rehydrate` |
| `server/src/clara/server/tools/graph/shared/rehydrate.cljc` (new) | four usage closures + `:downstream` transpose over slim-shaped maps |
| `server/src/clara/server/tools/graph/shared/navigate.cljc` (new) | pure `navigate` + navigation fns over a rehydrated analysis map |
| `server/src/clara/server/tools/graph/shared/tokens.cljc` (new) | token normalization + callsite string matching; `#?(:bb/:clj)` live-resolve seam |
| `server/src/clara/server/tools/graph/shared/selection.cljc` (new) | the shared merge preamble (`selection/->selection`) |
| `server/src/clara/server/tools/graph/shared/compose.cljc` (new) | production merge + fact-type union + dep-graph recompute (`compose/->composed-analysis`) |
| `server/src/clara/server/graph/client.clj` | becomes the JVM shell: keeps `register!`, `get-production-source`, `swap-session!`, schema validation; delegates to `shared.*` |
| `server/src/clara/server/tools/graph/artifacts/rehydrate.clj` | delegates its closure bodies to `shared.rehydrate` / `shared.hierarchy` |
| `server/bin/bootstrap.bb` (new) | add `server/src` + prismatic/schema to the bb classpath (version from `deps.edn`) |
| `server/bin/editor_client.bb` (new) | bb entry: bootstrap, compose the registry selection (`shared.selection` → `shared.compose` → `shared.rehydrate`), call `shared.navigate`, print EDN |
| `server/bin/annotations_report.bb` | migrate to `bootstrap.bb` + `shared.hierarchy` after `editor_client.bb` is proven (drop its inline closure reimpls and the `layout.cljc` symlink) |
| `server/docs/persisted-artifacts.md` | note the new offline reader + the `shared.` convention |
| `editor/emacs/clara-explorer.el` | add bb transport + config defcustoms; pre-resolve aliased symbol tokens to fq via CIDER before sending |
| `editor/neovim/lua/clara-explorer/*.lua` | later, same transport + pre-resolution change |

## Phasing

0. **Two independent cleanups, done first — before any `shared.`/bb work.**
   They touch only the current JVM client + editors and shrink everything after.
   0a. Drop the scoped `:match` path to global-closure-only, pinned by a parity
       test (scoped ≡ global over every fixture).
   0b. Move aliased-symbol resolution into the editors (CIDER/Conjure resolve to
       fq before calling `navigate`), so the client does no live resolution;
       keep passing `:caller-ns` as context. See "Step 0b in detail".
1. **Extract + parity (no bb yet).** Move the navigation body and closures into
   `shared.*`, have the JVM `client.clj` and `rehydrate.clj` delegate to them,
   and pin parity with the existing `client`/`rehydrate`/`slim` tests. No
   behavior change; de-risks the split.
2. **`bootstrap.bb` + bb smoke test.** Add `bootstrap.bb` and a test that
   `require`s every `shared.*` namespace under bb with no classpath beyond
   `src`.
3. **bb entry script.** Implement `editor_client.bb` taking a registry
   selection (`{:root … :units […]}`). Start with a single-unit selection
   (reusing the read path `annotations_report.bb` already has), then generalize
   to multi-unit composition via `shared.selection`/`shared.compose`. Verify
   against `annotations_report.bb`'s `producers`/`consumers` and `rehydrate`
   over the checked-in example registry
   (`clara.server.tools.graph.artifacts.regen-example/example-out-dir`).
4. **Emacs transport.** Wire the bb transport behind a defcustom (shell out to
   `editor_client.bb`); prompt for the registry selection with defaults from
   `CLARA_RULES_REGISTRY`; leave nREPL as the default.
5. **neovim.** Mirror step 4 in Lua.

## Risks and mitigations

- **Closure direction wrong in a reimplementation.** Eliminated, not mitigated:
  the closures live in `shared.hierarchy` and are `require`d by both sides, so
  there is one definition to get right. Parity is pinned by `slim-test`'s
  dropped-directions-invert-back test on the JVM side and the bb smoke test.
- **A `shared.` namespace accidentally requires a JVM-only dep.** bb `require`
  fails loudly at load (verified), and the bb smoke test forces every `shared.`
  namespace through that load.
- **Reader-conditional ordering.** `#?(:clj … :bb …)` silently ships JVM code
  into bb. Mitigation: the rule is `:bb` first, stated in `shared.` docstrings
  and checked by the bb smoke test asserting the `:bb` branch is taken.
- **Token resolution regressions.** Moot if editor-side token resolution is
  adopted (the client never sees a bare/aliased symbol). Otherwise bb resolution
  is a strict subset; a bare/aliased symbol with no callsite linkage resolves to
  "no fact type found" instead of a wrong jump — the same shape the JVM path
  already emits for unresolvable tokens.
- **`get-production-locations` / `swap-session!` / `refresh` have no bb twin.**
  They are nREPL-only by nature; the editors gate them on transport.

## Decisions (from plan review)

1. **Editor-side token resolution** — adopt. Both editors have the repl (CIDER
   and Conjure `eval-str`), so the client stays pure on both runtimes; the
   editor resolves aliased symbols to fq before calling `navigate`, and
   `:caller-ns` is still passed as context. See "Step 0b in detail".

2. **Drop the scoped `:match` path** — adopt. Delete `deps->targets` /
   `:upstream`/`:downstream` from the navigation code path and answer from the
   global closure only, after a parity test pins scoped ≡ global. See the
   `:match` section.

3. **Registry selection, with reload.** The bb client is told which units to
   load as a registry selection (`{:root … :units [{:repo … :branch …}]}`) —
   the same shape the server's `:registry` mode takes — not a single unit
   directory. Reloading is a requirement: after a unit is re-persisted, the
   next query must read the new files. Because the bb client is a fresh
   subprocess per query, re-reading is natural and reload-on-change is free; if
   a cache or long-running process is added later, it must key staleness on the
   manifest head `:sha`/`:created` (already recorded by
   `clara.server.tools.graph.artifacts.registry/unit-info`).

4. **Editor config.** The editor prompts for the registry selection, defaulting
   to selections under `CLARA_RULES_REGISTRY` (the registry root — the
   `rules-annos/` tree) when that variable is set. The editor resolves the env
   var itself and passes an explicit `:root`, matching the library's "reads no
   env var; the host resolves `$…_HOME`" convention.

5. **`NavigateInput` validation in bb** — drop it in bb, keep it in the JVM
   shell. What "validation" refers to: the JVM `navigate` runs
   `(s/validate NavigateInput input)` against the Plumatic Schema
   `NavigateInput` — `{:production (maybe Str) :side (enum :lhs :rhs)
   :caller-ns Str :token Str}` (with `:production`/`:side`/`:caller-ns`
   optional) — so a malformed map fails at the choke point rather than deep
   inside the navigation logic. The editors already build well-formed maps, so
   bb needs none of it: a malformed map fails naturally or returns
   `{:error …}`, and pulling `schema.core` onto the bb classpath for this alone
   is not worth it.

6. **`annotations_report.bb` migration** — migrate it to `bootstrap.bb` /
   `shared.*` after `editor_client.bb` is proven; leave it on the symlink until
   then.

7. **Zero-repl bb mode** — not a goal. The editor keeps its connected repl for
   token resolution; the goal is only that no clara session is loaded and no
   Jetty server is started on it. Persisted namespace alias maps (currently
   dropped as `:ns-deps`) are therefore not needed.

## Remaining open questions

- **bb process model.** Stateless per-query subprocess (reload-on-change for
  free, but re-reads `production-index.edn` ~1.9MB + `fact-types.edn` each
  query) versus a long-running bb process with a staleness-aware cache keyed on
  the manifest head. Decide once latency is measured.
- **Env var naming** — resolved. `CLARA_RULES_REGISTRY` is the registry-root
  var; `CLARA_RULES_EXPLORER_HOME` is the canonical repo-root name (the stale
  `CLARA_HOME_EXPLORER` mentions in `docs/explorer-editor-navigation-neovim.md`
  and the plugin error message were corrected). `CLARA_HOME` (no suffix) remains
  the clara-rules *engine* checkout.

## Related

- [`../server/docs/persisted-artifacts.md`](../server/docs/persisted-artifacts.md) — the on-disk artifact set and the existing `annotations_report.bb` reader
- [`../server/docs/registry-architecture.md`](../server/docs/registry-architecture.md) — units, selection, compose, and the `:registry` server mode
- [`explorer-graph-api.md`](explorer-graph-api.md) — the HTTP routes, including `:registry`
- [`explorer-editor-navigation-emacs.md`](explorer-editor-navigation-emacs.md) / [`explorer-editor-navigation-neovim.md`](explorer-editor-navigation-neovim.md) — the transport contracts
