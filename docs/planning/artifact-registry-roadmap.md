# Artifact registry — implementation roadmap

Status: **In progress** · Companion checklist to
[`artifact-registry-plan.md`](artifact-registry-plan.md). Each phase leaves
`make test lint reflection-check` green in `server/`.

Namespaces are under `clara.server.tools.graph.artifacts.*` (the plan's short
`artifacts/…` names are the same namespaces).

## Phases

- [x] **1. `artifacts/registry.clj`** — discovery, per-unit reads, compatibility report.
  - [x] `discover` walks a root and finds units by `rules-inspect-manifest.edn`
  - [x] `branches/` children become `:branch` variants of the parent unit
  - [x] `read-analysis` / `read-digest` / `read-manifest` / `read-annotations`
  - [x] `->registry` with explicit `:units`
  - [x] `compatibility-report` compares `:slim :dropped` key sets
  - [x] tests build a registry of hand-written units in a temp dir

- [x] **2. `artifacts/rehydrate.clj`** — the inverse of `slim`.
  - [x] fact-type reverse directions (`:inserted-by-rules`, `:retracted-by-rules`, `:used-by-rules`, `:used-by-queries`)
  - [x] `:descendants` transpose
  - [x] production/dep-graph `:downstream` transpose
  - [x] `:id` + id indexes rebuild
  - [x] optional `:merged-annotations` restore
  - [x] round-trip property test against a real rulebase analysis

- [x] **3. `artifacts/compose.clj`** — `fold-layers` + `->composed-analysis`.
  - [x] qualified layer ids (`<repo>[@<branch>]/<layer-id>`)
  - [x] `->composed-analysis`: rules/queries merged by fq name, collision refusal
  - [x] fact-types merged per name with unioned `:ancestors`
  - [x] `:dep-graph` recomputed over the merged set, `:slim` union + `:nodes`, `:unit` per production
  - [x] test merging the two `test-resources/rules-annos/` rulesets (they connect via `ApplicationOutcome`)

- [x] **4. `artifacts/federate.clj`** — hierarchy union/re-closure, `->index`, query fns, polarity.
  - [x] `->index` over a selection
  - [x] global hierarchy closure + `:conflicts`
  - [x] query fns (`impact-of`, `producers-of`, `dependents-of`, `paths-between`, `unit-dependency-graph`, `coverage-report`)
  - [x] polarity walk over `:lhs`

- [ ] **5. `->digest` + `persist!`** — `layout/artifact-files` entries + babashka view.
  - [ ] `federate/->digest`
  - [ ] `federate/persist!`
  - [ ] `layout/artifact-files` gains `:registry-index` / `:registry-digest`

- [ ] **6. Serving** — `ServerState`, `cache`, the 409 `:no-session`, config schema, route tests.
  - [ ] `ServerState` gains `:rulebase-analysis` as alternative to `:session`
  - [ ] `cache/get-rulebase-analysis` returns supplied analysis as-is
  - [ ] session routes answer 409 `:no-session`
  - [ ] `:annotations` via `compose/fold-layers`
  - [ ] route tests over a registry-backed server with no session

- [ ] **7. `grade`** — `federate/grade index reference-analysis`.

## Cross-cutting

- [ ] `server/docs/persisted-artifacts.md` gains the registry chapter
- [ ] `docs/explorer-graph-api.md` gains the registry-backed server mode + 409 list
- [ ] `README.md` gains one Documentation line

## Notes

- The two checked-in rulesets under `server/test-resources/rules-annos/` are the
  end-to-end demonstration for the merge: `loan-app-ruleset` produces
  `ApplicationOutcome`, `loan-disposition-ruleset` consumes it (the same wiring
  `clara.server.graph.integration-test` proves live).
