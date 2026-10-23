# Progress: rename `annotations_report.bb` → `inspect-artifacts.bb`

Tracking the execution of [`rename-annotations-report-bb-plan.md`](./rename-annotations-report-bb-plan.md).
Consumer-facing instructions live in
[`rename-annotations-report-bb-migration-guide.md`](./rename-annotations-report-bb-migration-guide.md).

Branch: `rename-annos-report`. Started clean (no working-tree changes).

## Status

| Step | Description | State |
|---|---|---|
| 1 | `git mv` three scripts; rename + repoint two editor symlinks | done |
| 2 | Update `explorer/bin/*.bb` internals (usage strings, header comments) | done |
| 3 | Update explorer tests + `Makefile`; editor locators + editor tests | done |
| 4 | Update docs (`persisted-artifacts.md`, editor navigation docs) | done |
| 5 | Straggler grep + full verification | done |

## Rename table (authoritative)

| Old | New |
|---|---|
| `explorer/bin/annotations_report.bb` | `explorer/bin/inspect-artifacts.bb` |
| `explorer/bin/editor_client.bb` | `explorer/bin/editor-client.bb` |
| `explorer/bin/bb_shared_smoke_test.bb` | `explorer/bin/bb-shared-smoke-test.bb` |
| `editor/emacs/editor_client.bb` (symlink) | `editor/emacs/editor-client.bb` |
| `editor/neovim/lua/clara-explorer/editor_client.bb` (symlink) | `editor/neovim/lua/clara-explorer/editor-client.bb` |

Unchanged: `explorer/bin/bootstrap.bb`, `explorer/bin/clj-local`,
`explorer/bin/ci/check-reflection.sh`, everything under `src/`.

## Reference inventory (from the pre-change straggler grep)

Sites that name an old stem, excluding the plan and migration guide:

- `explorer/bin/` — `annotations_report.bb`, `editor_client.bb`,
  `bb_shared_smoke_test.bb`, one `bootstrap.bb` header comment.
- `explorer/Makefile` — `bb-smoke-test` target.
- `explorer/test/clara/explorer/artifacts/bb_report_test.clj`,
  `editor_client_bb_test.clj`.
- `explorer/docs/persisted-artifacts.md`.
- `explorer/dev/clara/explorer/dev/compose_artifacts.clj` (comment).
- `explorer/src/clara/explorer/artifacts/diff.clj` (docstring).
- `docs/explorer-editor-navigation-neovim.md`.
- `editor/emacs/clara-explorer.el`, `editor/emacs/test/clara-explorer-test.el`.
- `editor/neovim/lua/clara-explorer/conjure.lua`,
  `editor/neovim/test/clara-explorer/bb_transport_spec.lua`.

`docs/explorer-editor-navigation.md` and
`docs/explorer-editor-navigation-emacs.md` did **not** match the grep before
the change (no old stems); re-checked after.

Not a reference: `explorer/test/...` filenames like `editor_client_bb_test.clj`
are `.clj` test namespaces, so they keep underscores (JVM namespace rule).
Only the *strings and paths inside* them change.

## Log

### Step 1 — renames

`git mv` for all three scripts; `git rm` + fresh `ln -s` for both editor
symlinks (a symlink cannot be `git mv`-retargeted in one step). Verified both
new links resolve to `explorer/bin/editor-client.bb`. Executable bit on
`inspect-artifacts.bb` preserved (`-rwxr-xr-x`); the other two were `644` before
and after.

### Step 2 — `.bb` internals

- `inspect-artifacts.bb`: header usage line, `usage-line` string, `diff` die hint.
- `editor-client.bb`: two header usage lines, the `ex-info` message, both
  `die` usage strings.
- `bb-shared-smoke-test.bb`: header usage line.
- `bootstrap.bb`: header comment (only mentions the script, not a path).

Left alone deliberately: the descriptive prose in `inspect-artifacts.bb`'s
header (“the ANNOTATION-side reader”) — the plan scopes this change to
path/name strings, not to rewording what the tool does.

### Step 3 — tests, Makefile, editor locators

Token replacement of the three script names across the enumerated files, then
one hand fix the mechanical replace could not reach:
`bb_transport_spec.lua` matched `"editor_client%.bb$"`. The old stem needed
`-`→`%-` as well as `.`→`%.`, because `-` is a Lua pattern quantifier; the
test now matches `"editor%-client%.bb$"`. Everything else was a literal path
or message.

Kept as-is, intentionally: the explorer test filenames/namespaces
(`bb_report_test.clj` / `bb-report-test`, `editor_client_bb_test.clj`), and the
`report-script` vars inside them. Those are JVM namespace/file identities and
local var names, not the scripts under rename; §3.2 only asks for the paths and
strings inside. Renaming them is a separate cleanup, not part of this change.

Verified by grep: no old stem remains in `editor/`, `explorer/Makefile`,
`explorer/src`, `explorer/dev`, or `explorer/test`.

### Step 4 — docs

`explorer/docs/persisted-artifacts.md` (both `## Querying it offline` and
`## Navigating offline` sections, every `S="…/bin/…"` snippet and inline
invocation) and `docs/explorer-editor-navigation-neovim.md` (transport prose +
the `g:clara_explorer_bb_script` bullet).

`docs/explorer-editor-navigation.md`, `docs/explorer-editor-navigation-emacs.md`,
`editor/AGENTS.md`, and both editor `Makefile`s were checked and contain no
script-name references, so they are untouched.

### Step 5 — straggler grep

```
grep -rn "annotations_report\|editor_client\|bb_shared_smoke" . \
  --exclude-dir=.git --exclude-dir=.clj-kondo --exclude-dir=node_modules \
  --exclude-dir=target --exclude='rename-annotations-report-bb-*'
```

→ no matches (the only hits are the plan, this progress doc, and the migration
guide, which name the old paths on purpose).

## Verification results

### Explorer (`explorer/`)

| Command | Result |
|---|---|
| `make bb-smoke-test` | pass — 15/15 bb-loaded namespaces `require` |
| `make format-check` | pass — all files formatted correctly |
| `make lint` | pass — 0 errors, 0 warnings |
| `make test` | pass — 514 tests, 3373 assertions, 0 failures |
| `make reflection-check` | pass — no reflection warnings |
| targeted `bb-report-test` + `editor-client-bb-test` | pass — 10 tests, 237 assertions (ran, not skipped, so the new paths resolve) |

### Editors

| Command | Result |
|---|---|
| `editor/emacs` `make test-tier1` | pass — 105 tests, 0 unexpected (5 skipped: real-dependency stubs) |
| `editor/emacs` `make check-elisp` | pass — no byte-compile diagnostics |
| `editor/emacs` `make test` (Tier 2 / eldev) | pass — user-run in `editor/emacs`; the agent's run was blocked only by the sandbox proxy (see note) |
| `editor/neovim` `make test` | pass — 170 successes, 0 failures, 0 errors (exit 0) |
| `editor/neovim` `make format-check`, `make lint`, `make check` | pass — user-run; `stylua`/`selene` are present in their environment (the agent's sandbox lacked them) |

**Note on the two sandbox-only gaps.** Both were limitations of the agent's
sandbox, not of the change, and both are now closed: the user ran the full
`make` sets in `editor/emacs` and `editor/neovim` and all targets pass. That
includes Emacs Tier 2 (whose `eldev prepare` could not reach ELPA through the
sandbox's authenticated `srt:…@localhost:52261` proxy — Emacs' `url.el` does not
read the credential from the `*_proxy` env vars the way `bb`/`clj` do) and the
Neovim `format-check`/`lint` gates (where `stylua`/`selene` were missing).

Net: **both editor suites are fully green**, across both Emacs tiers and all
Neovim checks, not just the Tier 1 fallback.

### Manual smoke

| Command | Result |
|---|---|
| `bb explorer/bin/inspect-artifacts.bb help` | prints `usage: bb inspect-artifacts.bb …` |
| `… inspect-artifacts.bb <unit> summary` | 19 rules, insert-dimension tallies |
| `… inspect-artifacts.bb <unit> status` | unit/kind/source/policy/verdict rows |
| `… inspect-artifacts.bb <unit> diff <variant>` | “no differences” (identity diff) |
| `bb explorer/bin/editor-client.bb --list-units <root>` | four `{:key :entry}` units |
| `bb editor/emacs/editor-client.bb --list-units …` | same output — symlink resolves |
| `bb editor/neovim/lua/clara-explorer/editor-client.bb --list-units …` | same output — symlink resolves |
| `… inspect-artifacts.bb <unit> diff` (missing arg) | `diff needs two unit dirs: bb inspect-artifacts.bb …` |

### Plan/reality discrepancies (no action taken — behavior is out of scope)

- The plan's §5 says `bb explorer/bin/inspect-artifacts.bb --help`. The script
  has no `--help` flag (it errors “Unknown option”); help is the `help`
  subcommand. That was true before the rename too, so it is a plan typo rather
  than a regression. Smoke test used `help`.

### Old-name absence

- `ls explorer/bin` → `bb-shared-smoke-test.bb`, `bootstrap.bb`, `ci`,
  `clj-local`, `editor-client.bb`, `inspect-artifacts.bb`.
- `find editor -name '*editor_client*'` → empty.
- Symlinks recorded as mode `120000` in git; `inspect-artifacts.bb` stayed
  `100755` (`git ls-files -s` confirms).
- Only remaining occurrence of the old path anywhere under the worktree is the
  gitignored `.clj-kondo/.cache` (regenerated by `make lint`, which was run
  after the rename).

## Deliberately not done

- No compatibility shim, alias, or old-named copy exists.
- No rename of the explorer test file/namespace identities
  (`bb_report_test.clj` / `bb-report-test`, `editor_client_bb_test.clj`), nor
  of local vars (`report-script`). The plan scopes this change to script paths
  and the strings that name them.
- No rewording of the scripts' descriptive prose (only the paths/usage strings).
- No change to subcommands, flags, `--edn` shapes, or exit codes.
