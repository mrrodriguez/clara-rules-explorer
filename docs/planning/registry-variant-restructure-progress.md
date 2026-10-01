# Registry variants restructure — progress

Tracks the implementation of
[`registry-variant-restructure-plan.md`](registry-variant-restructure-plan.md).
This doc is a log, not the design; the plan is the source of truth for the model
(§2), segment encoding (§3), the `ref` axis (§4), and the namespace-by-namespace
changes (§8).

## Key implementation decisions

- **`ref` is derived only on the write path.** `store/get-out-dir` reads git when
  `:canonical?` is present (the write path); when it is absent, `:variant` is
  treated as already complete (a reader reconstructing a known unit, or a host
  that has already decided its unit's identity). This keeps registry reads and
  the bb report free of git subprocesses.
- **`layout/write-variant` is the single mainline rule** (bb-portable; `store`
  re-exports it). Both `store/get-out-dir` and `manifest/->manifest` derive the
  full `:variant` (host axes + `[:ref …]`) through it, so the directory placement
  and the manifest's top-level `:variant` cannot disagree.
- **The checked-in example uses the read-path form** for both mainline and
  variant units, so the golden test stays deterministic. The variant ref is a
  hand-placed placeholder (`ref=feature/new-tax`, exercising `/` encoding). The
  git-derived write path (mainline rule, `ref` resolution) is covered by
  `store-test` with stubbed git, not by the golden artifact set.
- **Encoding lives in `layout`** (`encode-value` / `decode-value` /
  `variant->path` / `path->variant`); **validation of host axes lives in
  `store`** (replacing `get-branch-path`).
- **`:repo-path` moved from `ManifestOptions` into `ArtifactOpts`**, since
  `get-out-dir` now reads it (default: the process's cwd) to resolve the ref.

## Checklist

- [x] `artifacts.layout` — `variants-subdir`, public encode/decode, `variant->path`
  / `path->variant`, `->unit-dir` / `->default-root` take `:variant`
- [x] `artifacts.shared.git` — `get-git-info` adds `:default-branch`
- [x] `artifacts.schema` — `Variant`, `UnitRef`, `ArtifactOpts`,
  `ManifestOptions`, `GitInfo`, composed-unit entries
- [x] `artifacts.shared.registry` — `unit-key` over `:variant`
- [x] `artifacts.store` — `get-out-dir` resolves ref + mainline rule; `write-variant`;
  value validation replaces `get-branch-path`
- [x] `artifacts.manifest` — top-level `:variant`
- [x] `artifacts.registry` — discovery split; `unit-ref`/`->opts`/`->unit-info`
  carry `:variant`; path/manifest consistency report (`get-variant-mismatches`)
- [x] `artifacts.compose` / `artifacts.flow` / `artifacts.federate` — `:variant`
  wherever `:branch` was carried
- [x] `artifacts.shared.status` — unit-key parsing + `unit` line print the
  variant path; `:variant` replaces `:label`
- [x] `bin/annotations_report.bb` — `status` renders the variant path
- [x] `bin/editor_client.bb` — discovery mirror follows the new split
- [x] `docs/persisted-artifacts.md`, `docs/registry-architecture.md` — describe
  `_variants/`, axes, `ref`, encoding
- [x] Tests updated (`layout`, `store`, `registry`, `shared/status`,
  `shared/git`, `bb_report`, `editor_client_bb`, `federate`, `compose_persist`,
  `schema`, `regen_example`)
- [x] Checked-in example regenerated (`make regen-artifacts`)

## Verification

- [x] `make format-check`
- [x] `make lint` — 0 errors, 0 warnings
- [x] `make test` — 432 tests, 2818 assertions, 0 failures
- [x] `make bb-smoke-test` — all 11 bb-loaded namespaces require under bb
- [x] `make reflection-check` — no warnings

## Notes / deviations

- `branches/` was removed with no migration path, as the plan specifies. The
  checked-in `rules-annos/` example was regenerated:
  `loan-disposition-ruleset/branches/alt/` →
  `_variants/loan-disposition-ruleset/ref=feature%2Fnew-tax/`.
- The example's variant ref is a placeholder because the git-derived `ref` is
  environment-dependent and would break byte-identical golden testing. The
  mainline rule itself is pinned in `store-test` (`out-dir-mainline-rule-test`)
  with a stubbed `shared-git/get-git-info`, and the real default-branch read is
  pinned in `shared/git-test`.
