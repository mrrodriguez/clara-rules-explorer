# Live session + registry compose — Plan

Status: **Proposed** (future potential; no roadmap yet) · Scope: `server/` (Clojure)
· Related: `graph/server.clj`, `graph/cache.clj`, `graph/api.clj`,
`graph/client.clj`, `tools/graph/artifacts/compose.clj`,
`tools/graph/artifacts/registry.clj`, `tools/graph/artifacts/rehydrate.clj`,
`tools/graph/artifacts/slim.clj`, `docs/planning/artifact-registry-plan.md`,
`docs/explorer-graph-api.md`, `editor/emacs/clara-explorer.el`,
`editor/neovim/lua/clara-explorer/init.lua`

## 1. Goal

Let a developer running the explorer against a **live session** — actively
editing one ruleset, with in-memory auto-detect annotations — decorate that
same running server with a selection of **persisted registry units** so they
can cross-reference related, unchanged rulesets without restarting or losing
their live session's working memory.

The rapid-reload editor flow (`clara-explorer-swap-session` in Emacs,
`:ClaraExplorerSwapSession` in Neovim) must be able to express this as a
single swap call, reusing `clara.server.graph.client/register-session-swap-opts-fn`
rather than inventing a new editor command.

This first cut uses **compose semantics**: the live ruleset and the added units
are asserted to be components of one rulebase. That is the case the two
checked-in `rules-annos/` units already demonstrate — `loan-app-ruleset`
produces `ApplicationOutcome`, `loan-disposition-ruleset` consumes its ancestor
`:loan-app/application-outcome`, and the cross-unit dep-graph edge only
materializes in the merge. Independent rulebases that merely share a fact-type
vocabulary are the federation case and are explicitly deferred (§8).

## 2. Current state (inventory)

### 2.1 Server modes are exclusive

`clara.server.graph.server/StartOpts` and `ServerState` each enforce exactly
one of two modes:

- **session mode** — `:session`, `:annotations-spec`, bare `:annotations`,
  optional `:memory-analysis`, `:analyze-cache`
- **registry mode** — `:rulebase-analysis`, `MergedAnnotations` `:annotations`,
  optional `:registry`

`ServerState`'s `s/conditional` keys on `(contains? % :rulebase-analysis)`.
Registry mode is documented as "compose a selection and serve it with **no
live session**"; session routes answer 409 `:no-session` (see
`docs/planning/artifact-registry-plan.md` §9 and
`server/docs/persisted-artifacts.md`).

There is no hybrid branch holding both a live `:session` and a precomputed
`:rulebase-analysis`.

### 2.2 The two mutation seams

`clara.server.graph.server/swap-session!` validates `SwapSessionOpts`:

```clojure
{(s/optional-key :session) SessionOrRulebase
 (s/optional-key :annotations) (s/maybe AnnotationsArg)
 (s/optional-key :warm-cache?) s/Bool}
```

It transitions the state through `transition-swap`, which rebuilds session-mode
state only.

`clara.server.graph.server/reload-annotations!` re-derives annotations from the
**stored** `:annotations-spec` against the **current** `:session`, then warms
the cache. It has no place to accept units, and — by contract — should not:
refresh means "re-derive what this spec already describes," not "change the
analysis universe."

### 2.3 Cache and API predicate on `:rulebase-analysis`

`clara.server.graph.cache/get-state` branches on the presence of
`:rulebase-analysis`: present means registry mode, absent means session mode.

`clara.server.graph.api/with-memory-analysis` likewise 409s `:no-session`
whenever `:rulebase-analysis` is present, regardless of whether a live
`:session` also exists.

A hybrid mode therefore needs both predicates re-expressed in terms of "is
there a live `:session`" rather than "is there no `:rulebase-analysis`".

### 2.4 The editor seam already exists

`clara.server.graph.client/swap-session!` is two-arity:

- 1-arity takes the full opts map and delegates to
  `server/swap-session!` on the current system.
- 0-arity delegates to the fn registered via
  `clara.server.graph.client/register-session-swap-opts-fn`.

Both editors already call the 0-arity form when the swap prompt is empty, and
cache the last explicit opts per connection/buffer. No editor change is needed
for the happy path if the decoration is expressible as an opts map.

## 3. Why an annotations-only merge is not enough

`clara.server.tools.graph.core/->rulebase-analysis` iterates the session's own
productions and looks annotations up by rule name. An annotation entry keyed by
a rule from a different ruleset that is not present in the session is never
visited — it does not become a production, a fact type, or a dep-graph edge.

`clara.server.graph.client/navigate` reads the **analysis** (`:rules`,
`:queries`, `:fact-types`, dep-graph), not the raw annotations map. Folding
foreign units' layers into `:annotations` alone would therefore make the
`/v1/annotations` map richer without adding a single cross-reference target.

Cross-reference requires the persisted units' **productions and fact types** to
be in the analysis. The artifacts layer already knows how to produce that merge;
what is missing is a way to feed a live, in-memory analysis into it as one unit.

## 4. What already exists that this reuses

- `artifacts.registry/->registry` + `read-analysis` read persisted units as slim
  analyses.
- `artifacts.compose/->composed-analysis` merges slim analyses, recomputes the
  dep-graph over the unioned fact-type hierarchy, refuses a production name
  claimed by two units, and tags each production with `:unit`.
- `artifacts.compose/fold-layers` folds every unit's layer stack into one
  `MergedAnnotations` with per-unit-qualified layer ids and provenance.
- `artifacts.rehydrate/rehydrate-analysis` rebuilds the reverse directions and
  id indexes over the whole merge.
- `artifacts.slim/slim-rulebase-analysis` turns a live analysis into the same
  slim shape a persisted unit carries.

The merge engine is not the hard part; the gap is the hybrid server state and
one small compose refactor.

## 5. Proposed design

### 5.1 New swap decoration

Add an optional `:registry` (name TBD) to `SwapSessionOpts`, reusing the
existing `RegistryConfig` shape:

```clojure
{:root s/Str
 :units [artifact-schema/UnitRef]}
```

A swap call then reads:

```clojure
(server/swap-session! system
  {:session    rebuilt-session
   :annotations {:enrichment :auto-detect}
   :registry   {:root "…"
                :units [{:repo "loan-disposition-ruleset"}]}})
```

Semantics: keep serving this live session, but serve the **analysis routes and
`navigate`** from a composed analysis of the live ruleset plus the selected
units; serve session routes from the live session's working memory as today.

The live ruleset's annotations continue to come from the `:annotations` spec
(auto-detect in the motivating workflow). The persisted units' annotations come
from their own layer files.

### 5.2 Hybrid `ServerState` branch

Extend `ServerState`'s `s/conditional` with a branch for `:session` and
`:rulebase-analysis` both present:

```clojure
{:session SessionOrRulebase
 :rulebase-analysis (s/pred map? 'rulebase-analysis?)
 :annotations ann.merge/MergedAnnotations
 (s/optional-key :annotations-spec) AnnotationsArg
 (s/optional-key :memory-analysis) MemoryAnalysis
 :analyze-cache (s/pred map? 'analyze-cache?)
 :registry RegistryConfig}
```

`StartOpts` may stay session-only or registry-only for the first cut; the hybrid
state is produced at swap time. (Opening `start!` to the same decoration is a
small follow-up, not required for the editor flow.)

### 5.3 Build the composed analysis in `transition-swap`

When `:registry` is present, after building the live session's annotations and
memory analysis:

1. Build the live slim analysis:
   `(slim/slim-rulebase-analysis (core/->rulebase-analysis session live-annos))`.
2. Read the persisted units' slim analyses via `registry/read-analysis`.
3. Merge live + persisted via a new `compose` entry that accepts an in-memory
   slim analysis as one unit (see §5.4), then `rehydrate/rehydrate-analysis`.
4. Fold `/v1/annotations` from the live layers plus
   `compose/fold-layers` for the persisted selection.

The live unit needs a stable `:unit` tag in the composed analysis — e.g.
`"<live>"` — so provenance and collision messages can name it.

### 5.4 Small `compose` refactor

`compose/->composed-analysis` currently takes `[registry selection]` and reads
units from disk itself. Split it:

- a merge core that takes an ordered vector of slim analyses plus the
  corresponding unit keys, and returns the composed slim analysis;
- the existing disk-backed entry point that reads units and calls the core.

This is the only meaningful refactor. The live unit's slim analysis is
constructed in `server` and handed to the merge core; it never touches disk.

`compose/fold-layers` stays disk-backed for the persisted units. The live
annotations are folded alongside them as one qualified layer (or as the live
session's existing layer stack, if `->resolved-annotations*` is extended to
return the layers — see §7 open questions).

### 5.5 Cache and API predicate changes

- `cache/get-state` gains a hybrid branch: when `:session` and
  `:rulebase-analysis` are both present, return/rebuild a cached map carrying
  both the supplied composed analysis and the live session's memory analysis.
- `cache/get-rulebase-analysis` returns the composed analysis.
- `cache/get-memory-analysis` continues to build from the live session and
  annotations.
- `api/with-memory-analysis` changes its gate from
  "`:rulebase-analysis` present → 409" to "`:session` absent → 409".
- `handle-get-annotations` needs no change: `AnnotationsMap` already accepts a
  `MergedAnnotations` value.

`client/navigate` needs no change — it already calls
`cache/get-rulebase-analysis` on the current system.

### 5.6 API/UI surface

The composed analysis carries the same shape the registry-only server already
serves, plus `:unit` on each production. `schema.core` maps are open, so the
existing `RuleListItem` / `Rule` response schemas accept the extra `:unit` key
without change. No UI work is expected in this pass beyond, if desired, an
optional `:unit` field in `ui/src/lib/types/api.ts`.

## 6. Editor expressibility

The existing indirection is the seam:

```clojure
(require '[clara.server.graph.client :as client])

(client/register-session-swap-opts-fn
  (fn []
    {:session    my-rebuilt-session
     :annotations {:enrichment :auto-detect}
     :registry   {:root "…"
                  :units [{:repo "loan-disposition-ruleset"}]}}))
```

Then `clara-explorer-swap-session` with empty input, and
`:ClaraExplorerSwapSession` with an empty prompt, eval the 0-arity
`client/swap-session!`, which delegates to that fn. Explicit opts and the
per-buffer cached-last-opts behavior keep working unchanged.

No editor command is added, and `reload-annotations!` is left alone: refresh
stays "re-derive the stored spec," swap is "change the analysis universe."

## 7. Open questions

- **Key name** — `:registry` vs `:registry-units` vs `:units` on
  `SwapSessionOpts`. `:registry` reuses `RegistryConfig` whole and reads
  consistently with `StartOpts`.
- **Live unit tag** — what string names the live unit in `:unit` and qualified
  layer ids. It must not collide with a real repo path and should sort
  predictably.
- **Live annotations as layers** — whether `->resolved-annotations*` should be
  extended to return its static layer stack so hybrid can fold it with
  provenance, or whether the live side is folded as one synthetic qualified
  layer. The former is more honest; the latter is less code.
- **Hybrid at `start!`** — defer or include. Deferring keeps `StartOpts`
  unchanged and makes swap the single way to enter hybrid mode.
- **Memory-analysis invalidation** — a registry-selection change does not
  affect the live session's memory analysis, but the composed analysis must be
  recomputed; `transition-swap` should rebuild only what changed and
  `cache/warm!` should warm both the composed analysis and the live
  memory analysis.
- **`/v1/annotations` return value of `swap-session!`** — session mode returns
  a bare map today; hybrid would return a `MergedAnnotations` value. Callers
  that assume bare should be identified before this lands.

## 8. Non-goals / deferred

- **No federation in this cut.** Independent rulebases that share a fact-type
  vocabulary but are not claimed to compose belong to
  `artifacts.federate/->index`, whose index is not `RulebaseAnalysis`-shaped.
  Wiring that into `client/navigate` is a larger, separate step and is only
  worth it once a concrete non-composable case appears.
- **No per-namespace / per-unit enrichment split.** Auto-detect remains a
  single mode for the live session; the persisted units keep their own stored
  layers. A future refinement could let "auto-detect the live unit, trust the
  persisted units' curated layers" be stated per unit.
- **No `reload-annotations!` registry support.** Refresh is re-derivation of
  the stored spec, not a change of scope.
- **No UI work in this pass** beyond an optional `:unit` type field.

## 9. Phases (future)

Each phase leaves `make test lint reflection-check` green in `server/`.

1. **Compose refactor** — split `compose/->composed-analysis` into a merge core
   over `[analyses units]` plus the disk-backed entry point; add tests for the
   in-memory-analysis input shape and unit tagging.
2. **Hybrid state + transition** — new `ServerState` branch, `SwapSessionOpts`
   decoration, `transition-swap` builds the live slim analysis, composes, folds
   annotations, and rehydrates.
3. **Cache + API predicates** — hybrid branch in `cache/get-state`;
   `with-memory-analysis` gates on `:session`; route tests over a hybrid system
   (analysis routes show both rulesets, session routes still answer from live
   memory).
4. **Editor seam verification** — a swap-opts-fn returning `:registry` works
   through `client/swap-session!` 0-arity; Emacs/Neovim transport tests pin the
   unchanged 0-arity form.
5. **Docs** — `docs/explorer-graph-api.md` gains the hybrid swap mode and the
   unchanged 409 list; this plan graduates to a roadmap once work starts.
