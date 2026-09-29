# Explorer renaming migration guide

Consumer guide for the `clara.server.*` → `clara.explorer.*` rename
(plan: `docs/planning/server-naming-restructure-plan.md`,
progress: `docs/planning/server-naming-restructure-progress.md`).
The top-level directory moved `server/` → `explorer/` in the same pass.

## 1. Summary

Every namespace that started with `clara.server.` now starts with
`clara.explorer.`, and the meaningless `graph` / `tools.graph` middle segments
are gone. There is no behavior change to function signatures or HTTP routes. The
namespace strings, namespace-derived file paths, resource lookup strings, the
layer-`:id` keywords (§5.2), and the `:clara.explorer.internal/normalized`
marker (§5.1) all changed, and a few persisted-artifact sections shed prose —
the `:slim` block, the digests' `:more`, and the manifest's
`:analysis-run :method` (§4). If you `require` this library, merge its
artifacts, or speak its editor protocol, remap as below and regenerate your
derived data once.

## 2. Require table (namespaces a caller may use)

| Old | New |
| --- | --- |
| `clara.server.graph.server` | `clara.explorer.server.serve` |
| `clara.server.graph.main` | `clara.explorer.server.main` |
| `clara.server.graph.client` | `clara.explorer.server.client` |
| `clara.server.graph.api` | `clara.explorer.server.api` |
| `clara.server.graph.cache` | `clara.explorer.server.cache` |
| `clara.server.graph.navigate` | `clara.explorer.server.navigate` |
| `clara.server.graph.schema` | `clara.explorer.server.schema` |
| `clara.server.graph.tokens` | `clara.explorer.server.tokens` |
| `clara.server.tools.graph.core` | `clara.explorer.core` |
| `clara.server.tools.graph.analyze` (+ `.alias`, `.callsite`, `.ctor`, `.index`, `.kondo`, `.synth`, `.utils`) | `clara.explorer.analyze` (+ same leaves) |
| `clara.server.tools.graph.annotations` (+ `.callsite`, `.merge`, `.rebase`, `.report`) | `clara.explorer.annotations` (+ same leaves) |
| `clara.server.tools.graph.conditions` | `clara.explorer.conditions` |
| `clara.server.tools.graph.memory` | `clara.explorer.memory` |
| `clara.server.tools.graph.serialize` | `clara.explorer.serialize` |
| `clara.server.tools.graph.nodes` | `clara.explorer.nodes` |
| `clara.server.tools.graph.ns-deps` | `clara.explorer.ns-deps` |
| `clara.server.tools.graph.classpath` | `clara.explorer.classpath` |
| `clara.server.tools.graph.edn-io` | `clara.explorer.edn-io` |
| `clara.server.tools.graph.fact-types` | `clara.explorer.fact-types` |
| `clara.server.tools.graph.kondo-config` | `clara.explorer.kondo-config` |
| `clara.server.tools.graph.utils` | `clara.explorer.utils` |
| `clara.server.tools.graph.artifacts.<leaf>` | `clara.explorer.artifacts.<leaf>` (`compose`, `digest`, `federate`, `flow`, `hierarchy`, `layout`, `manifest`, `overlay`, `parts`, `registry`, `rehydrate`, `schema`, `selection`, `serve`, `slim`, `store`, `compact`, `flow`, plus `shared.*`) |
| `clara.server.vendor.tools.inspect` | `clara.explorer.vendor.tools.inspect` |

Note the two intentional collisions, disambiguated by bucket:
`clara.explorer.server.serve` (Jetty lifecycle) vs
`clara.explorer.artifacts.serve` (layer-file selection), and
`clara.explorer.server.schema` (navigate contract) vs
`clara.explorer.artifacts.schema` (artifact key schemas).

## 3. Entry points

```clojure
;; before
(clara.server.graph.server/start! {:session my-session :port 9999})
(clara.server.graph.server/swap-session! {...})
(clara.server.graph.server/reload-annotations!)
;; after
(clara.explorer.server.serve/start! {:session my-session :port 9999})
(clara.explorer.server.serve/swap-session! {...})
(clara.explorer.server.serve/reload-annotations!)
```

- JVM main: `clara.server.graph.main/-main` → `clara.explorer.server.main/-main`
  (`clojure -M:demo-run`, `:demo-setup`, `:hierarchy-run` aliases unchanged).
- Dev-only demo/hierarchy runners (dev classpath, not library API):
  `clara.explorer.server.demo-setup/demo-run/hierarchy-run` →
  `clara.explorer.dev.server.demo-setup/demo-run/hierarchy-run`.
- Resource lookups: `clara/server/graph/editor-resolve-form.clj` →
  `clara/explorer/server/editor-resolve-form.clj`;
  `clara/server/tools/graph/kondo-config` → `clara/explorer/kondo-config`.
- REPL tooling: the `:sync-kondo-config` exec-fn is now
  `clara.explorer.dev.kondo-config-sync/sync!`.

## 4. Persisted artifacts: regenerate, except one file

The **files and top-level keys are unchanged**, but every namespace-bearing
**value** moved (`:name`, `:ns`, fact-type names, callsite
`:ns-name-sym`/`:filename`, derived `:id`s, manifest `:namespaces`), and a few
sections were simplified (below). Old and new artifacts must not be merged as
if they were the same namespace.

- **Regenerate** (pure functions of your rules/session — never hand-migrate):
  `auto-gen-annotations.edn`, `memory-annotations.edn`,
  `merged-annotations.edn`, `merged-rulebase-analysis/**`,
  `rulebase-analysis-digest.edn`, `rules-inspect-manifest.edn`,
  `registry-index.edn`, `registry-digest.edn`.
- **Migrate** (curated, never regenerate): `agent-annotations.edn`, with
  `clara.explorer.annotations.rebase/rebase-layer`:

```clojure
(require '[clara.explorer.annotations.rebase :as rebase]
         '[clojure.edn :as edn])
(let [layer (edn/read-string (slurp "agent-annotations.edn"))
      mapping {'my.old.rules.ns "my.new.rules.ns"}]
  (spit "agent-annotations.edn"
        (pr-str (rebase/rebase-layer layer mapping))))
```

`rebase-layer` remaps rule-name keys, `:ns-name-sym`, `:constructor-sym`,
`:filename`, `:resolved-types`, `:fact-type`, `:via`, and recomputes callsite
ids. There is deliberately no `rebase-analysis` helper: derived analysis,
digests, and manifests are regenerated, not rebased.

**`meta.edn`'s `:slim` block is smaller too.** It is now
`{:written-by <fn symbol>, :dropped <key set>, :unknown-fact-types <set>}`. The
old `:references` prose and `:recover` key→sentence map are gone — their
reasoning lives in `clara.explorer.artifacts.slim`'s source, resolvable at the
manifest sha — and `:written-by` is the producing function's fully-qualified
var symbol rather than a bare namespace string. Two more artifact values shed
prose: `rulebase-analysis-digest.edn` / `registry-digest.edn` no longer carry a
`:more` orientation paragraph, and `rules-inspect-manifest.edn`'s
`:analysis-run :method` is now the bare
`"clara.explorer.analyze/->rule-source-analysis"` (no `"live-session (…)"`
prefix). If you read any of these removed fields, stop expecting them — the
`:dropped` set is the contract; the rest is re-derivable or regenerated. The
`:session-hint` option that `merge-persisted!` / `compose-persist!` accepted is
gone too (it only appended to `:more`), so drop it from those calls.

**Shape-skew caveat.** `registry/compatibility-report` and
`assert-compatible!` compare the `:slim :dropped` key set across units. The
marker change in §5 alters that set once, so mixing pre- and post-upgrade
units in one registry selection is flagged/refused as shape skew even though
the shape is semantically identical. Regenerate all of your artifact sets once
after upgrading and the old key disappears everywhere.

## 5. Data keywords

### 5.1 The `:clara.explorer.internal/normalized` marker

The internal LHS-normalization marker used to be the library-namespace keyword
`:clara.server.tools.graph.conditions/normalized` (written into
`meta.edn` → `:slim :dropped` and every normalized `:lhs` node before slimming).
It is now `:clara.explorer.internal/normalized` — an explicitly internal,
namespace-independent keyword, so a consumer can see at a glance it is
implementation detail rather than contract. If you snapshot or assert on
`:dropped` key sets, update that one keyword; otherwise just regenerate (§4).

### 5.2 Layer-`:id` keywords renamed

The annotation-layer `:id` values used to carry the old analysis-namespace
prefix and now track the real one:

| Old | New | Where it appears |
| --- | --- | --- |
| `:clara.tools.graph.analyze/generated` | `:clara.explorer.analyze/generated` | auto-generated annotation layer `:id`; `rules-inspect-manifest.edn` → `:layer-ids :auto` |
| `:clara.tools.graph.analyze/memory` | `:clara.explorer.analyze/memory` | live-session working-memory layer `:id` (`analyze/->memory-layer`; the artifact `store` path uses the plain `:memory` id, unchanged) |
| `:clara.tools.graph.analyze/unknown-fact-type` | `:clara.explorer.analyze/unknown-fact-type` | fallback fact-type name substituted when a session fact's type is missing from the analysis |

These are structural values in persisted artifacts (layer `:id`s and a
fact-type name), so regenerating derived artifacts (§4) picks them up — there
is no hand-migration. A curated `agent-annotations.edn` layer carries its own
curated `:id` and is unaffected.

### 5.3 Other `:clara-rules-explorer/*` and `:clara-rules/*` keywords

- `:clara-rules-explorer/bb-loaded` is the sole `:clara-rules-explorer/` keyword
  now: a namespace-metadata marker on `ns` forms, never persisted — `make
  bb-smoke-test` uses it to discover babashka-loadable namespaces. The persisted
  normalization marker moved to `:clara.explorer.internal/normalized` (§5.1).
- The annotation-schema keys under `:clara-rules/*` (`insert-types`,
  `retract-types`, `no-output-types`, `notes`, `dynamic-insert-types-detected`,
  `dynamic-retract-types-detected`, `merge-props`) are project-owned annotation
  provenance keys, never tied to the `clara.server.*` namespaces — unchanged by
  this rename.

## 6. Annotation sidecars

Annotation maps keep string rule-name keys (`"my.ns/rule-name"`) and the same
normalization; only the string contents change. Callsite `:ns-name-sym`
symbols, `:constructor-sym`, `:filename` resource paths, and `:fact-type`
values move with your own namespace renames — this library rename touches them
only for the bundled demo/test rules
(`clara.server.tools.graph.rules.*` → `clara.explorer.test.rules.*`).

## 7. Editor integration

- Eval snippets: `clara.server.graph.client/navigate`,
  `clara.server.graph.client/swap-session!`, and
  `clara.server.graph.server/reload-annotations!` become
  `clara.explorer.server.client/navigate`,
  `clara.explorer.server.client/swap-session!`, and
  `clara.explorer.server.serve/reload-annotations!`.
- The resolve-form template moved to
  `explorer/resources/clara/explorer/server/editor-resolve-form.clj`; the
  Emacs/Neovim copies are symlinks into it. If you vendored a copy, re-copy it
  (a JVM sync test enforces byte-identity).

## 8. Checklist

1. `grep -r "clara\.server" src test resources` returns nothing in your code.
2. `grep -r "clara/server/"` returns nothing in your resource strings.
3. `grep -r "clara\.tools\.graph\.analyze/"` returns nothing (layer `:id`
   keywords migrated, §5.2).
4. All derived artifacts regenerated (§4); curated `agent-annotations.edn`
   rebased via `rebase-layer`.
5. No pre/post-upgrade units mixed in one registry selection
   (`compatibility-report` clean).
6. Editor snippets + vendored resolve-form updated (§7).
7. No reader still destructures `:slim :recover` / `:slim :references` / a
   digest's `:more`, or passes `:session-hint` (§4).
