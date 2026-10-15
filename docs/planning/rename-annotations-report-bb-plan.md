# Rename `annotations_report.bb` to `inspect-artifacts.bb`, kebab-case the `explorer/bin` bb scripts

Status: **proposal**. Scope: `explorer/bin/*.bb` (renames only, no behavior
change), every in-repo reference to those paths (tests, docs, usage strings,
editor symlinks + locators), and the consumer migration guide beside this plan.

## 1. Problem

`explorer/bin/annotations_report.bb` is poorly named for what it does today. It
is the offline triage/query CLI over the whole persisted artifact set — layers,
`production-index/conditions/details.edn`, `fact-types.edn`, `dep-graph.edn`,
`rules-inspect-manifest.edn`, composed units, `diff`, `status` — with 15
subcommands (`summary`, `gaps`, `types`, `producers`, `consumers`, `hierarchy`,
`production`, `edges`, `units`, `unit-edges`, `entry-points`, `curated`,
`layers`, `status`, `diff`). "Annotations" names one of four data sources;
"report" suggests one static document instead of a query tool.

Separately, the `explorer/bin` bb scripts use underscores (`annotations_report.bb`,
`editor_client.bb`, `bb_shared_smoke_test.bb`). Underscores are a JVM file
limitation (`.clj`/`.cljs` namespaces). Pure `.bb` scripts have no such
constraint and should use kebab-case with hyphens.

## 2. Decision

Breaking change in a single step. No backwards-compat shims, no symlinks under
old names, no deprecation period. Consumers re-point in one move; the migration
guide beside this plan (`rename-annotations-report-bb-migration-guide.md`) is
what they follow.

Rename table (`git mv`, executable bit preserved):

| Old | New | Notes |
|---|---|---|
| `explorer/bin/annotations_report.bb` | `explorer/bin/inspect-artifacts.bb` | the rename that motivates this plan |
| `explorer/bin/editor_client.bb` | `explorer/bin/editor-client.bb` | same stem, hyphens only |
| `explorer/bin/bb_shared_smoke_test.bb` | `explorer/bin/bb-shared-smoke-test.bb` | same stem incl. `bb-` prefix, hyphens only |
| `explorer/bin/bootstrap.bb` | unchanged | already single-word |
| `explorer/bin/clj-local` | unchanged | already kebab-case; bash, not bb |

Structural symlinks (not compat shims — they are how the editors consume the
canonical file) are renamed and repointed:

| Old symlink | New symlink | Target |
|---|---|---|
| `editor/emacs/editor_client.bb` | `editor/emacs/editor-client.bb` | `../../explorer/bin/editor-client.bb` |
| `editor/neovim/lua/clara-explorer/editor_client.bb` | `editor/neovim/lua/clara-explorer/editor-client.bb` | `../../../../explorer/bin/editor-client.bb` |

## 3. Changes

### 3.1 Rename the files

`git mv` the three `.bb` files per the table above. Recreate the two editor
symlinks under the new names pointing at the new target; delete the old
symlink paths. No file keeps its old name in any form.

### 3.2 Update in-repo references to the new paths

All of these are mechanical path/string updates, no logic changes:

- `explorer/Makefile` — `bb bin/bb_shared_smoke_test.bb` in `bb-smoke-test`.
- `explorer/bin/*.bb` internals:
  - header comments (`bb annotations_report.bb <dir|...>`, `bb bin/editor_client.bb ...`,
    `bb bin/bb_shared_smoke_test.bb`).
  - `load-file` of `"bootstrap.bb"` is unchanged (name did not change).
  - usage/die strings: `"usage: bb annotations_report.bb ..."`,
    `"diff needs two unit dirs: bb annotations_report.bb ..."`,
    `"editor_client.bb needs ..."`, `"usage: bb editor_client.bb ..."`.
  - ordinary comments naming the scripts.
- `explorer/test/clara/explorer/artifacts/bb_report_test.clj` — `(io/file "bin" ...)`,
  `"annotations_report.bb exited ..."`, `"usage: bb annotations_report.bb"`, ns docstring.
- `explorer/test/clara/explorer/artifacts/editor_client_bb_test.clj` — both script
  paths, docstrings, `"editor_client.bb exited ..."`, `"annotations_report.bb ..."` references.
- `explorer/docs/persisted-artifacts.md` — `## Querying it offline` and
  `## Navigating offline` sections, `S="$.../bin/..."` snippets, every
  `bb annotations_report.bb` / `bin/editor_client.bb` invocation.
- `explorer/dev/clara/explorer/dev/compose_artifacts.clj` — comment naming
  `explorer/bin/annotations_report.bb`.
- `explorer/src/clara/explorer/artifacts/diff.clj` — docstring naming
  `annotations_report.bb ... diff ...`.
- `docs/explorer-editor-navigation*.md` (incl. `-neovim.md`) —
  `editor_client.bb` paths and `g:clara_explorer_bb_script` discussion.
- Editor locators + tests (new symlink names, new suffix assertions):
  - `editor/emacs/clara-explorer.el`, `editor/emacs/test/clara-explorer-test.el`
    (`"editor_client.bb"` suffixes, `cannot locate` / `not found` messages).
  - `editor/neovim/lua/clara-explorer/conjure.lua`,
    `editor/neovim/test/clara-explorer/bb_transport_spec.lua`
    (`editor_client%.bb$` match, `/p/editor_client.bb` fixtures, `not found` message).
  - `editor/AGENTS.md` and editor `Makefile`s only if they name the scripts
    (verify with the grep below; update what matches, touch nothing else).
- Sweep for stragglers before finishing:
  `grep -rn "annotations_report\|editor_client\|bb_shared_smoke" --include='*.md' --include='*.clj' --include='*.bb' --include='*.lua' --include='*.el' --include='Makefile' .`
  must return nothing (excluding the two plan/migration-guide docs themselves
  and history).

### 3.3 Explicitly out of scope

- No output/behavior change to any subcommand; `--edn` shapes, exit codes, and
  flag spellings are untouched.
- No rename of anything under `src/` (JVM namespace limitation stands).
- No rename of `bootstrap.bb`, `clj-local`, or `bin/ci/check-reflection.sh`.

## 4. Order

1. `git mv` the three scripts; rename + repoint the two editor symlinks.
2. Update `explorer/bin/*.bb` internals (usage strings, header comments).
3. Update explorer tests + `Makefile`, then editor locators + editor tests.
4. Update docs (`persisted-artifacts.md`, editor navigation docs, migration guide
   paths if they quote any).
5. Straggler grep, then verification.

## 5. Verification

- `cd explorer && make test lint format-check reflection-check bb-smoke-test`.
- Both editor suites per `editor/AGENTS.md` (Tier-1/Tier-2 as defined there),
  since the symlinks and locator strings move.
- Manual smoke: `bb explorer/bin/inspect-artifacts.bb --help`,
  `bb explorer/bin/inspect-artifacts.bb <unit> summary`,
  `bb explorer/bin/editor-client.bb --list-units <registry-root>`,
  and one `diff` + one `status` invocation.
- Old names gone: `ls explorer/bin`, `find editor -name '*editor_client*'`,
  and the straggler grep in §3.2 are all empty of old stems.
