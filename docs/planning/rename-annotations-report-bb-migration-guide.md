# Migration guide: `annotations_report.bb` → `inspect-artifacts.bb` (plus kebab-case `explorer/bin` scripts)

This is a **breaking change with no backwards compatibility**. Old script paths
stop existing in the same commit that adds the new ones. There are no shims,
no symlinks under old names, and no deprecation period. Update every reference
in one move.

## 1. Rename table

| Old path | New path |
|---|---|
| `explorer/bin/annotations_report.bb` | `explorer/bin/inspect-artifacts.bb` |
| `explorer/bin/editor_client.bb` | `explorer/bin/editor-client.bb` |
| `explorer/bin/bb_shared_smoke_test.bb` | `explorer/bin/bb-shared-smoke-test.bb` |
| `editor/emacs/editor_client.bb` (symlink) | `editor/emacs/editor-client.bb` (symlink) |
| `editor/neovim/lua/clara-explorer/editor_client.bb` (symlink) | `editor/neovim/lua/clara-explorer/editor-client.bb` (symlink) |

Unchanged: `explorer/bin/bootstrap.bb`, `explorer/bin/clj-local`,
`explorer/bin/ci/check-reflection.sh`. Nothing under `src/` moves.

## 2. What to change

1. **Shell aliases, scripts, and CI** invoking any old path:
   - `bb explorer/bin/annotations_report.bb ...` → `bb explorer/bin/inspect-artifacts.bb ...`
   - `bb explorer/bin/editor_client.bb ...` → `bb explorer/bin/editor-client.bb ...`
   - `bb explorer/bin/bb_shared_smoke_test.bb` → `bb explorer/bin/bb-shared-smoke-test.bb`
   - Any absolute copy of these paths (e.g. `$CLARA_RULES_EXPLORER_HOME/explorer/bin/...`).
2. **Editor configuration** pointing at a custom bb script:
   - Neovim: `g:clara_explorer_bb_script` — point it at `editor-client.bb`, or
     unset it to use the shipped `editor-client.bb` symlink beside `conjure.lua`.
   - Emacs: `clara-explorer-bb-script` — point it at `editor-client.bb`, or
     unset it to use the shipped `editor-client.bb` symlink beside `clara-explorer.el`.
   - If you vendored a *copy* of `editor_client.bb` (not the symlink), replace
     the file with a fresh copy/symlink of `editor-client.bb`.
3. **Checked-out variant paths and docs** quoting the old names (runbooks,
   READMEs, wiki pages).

Subcommands, flags, arguments, `--edn` shapes, and exit codes are unchanged —
only the script filenames move. For example:

```bash
# before
bb explorer/bin/annotations_report.bb "$D" producers :loan/applicant
bb explorer/bin/annotations_report.bb "$D" status

# after
bb explorer/bin/inspect-artifacts.bb "$D" producers :loan/applicant
bb explorer/bin/inspect-artifacts.bb "$D" status
```

## 3. Verify you are clean

```bash
# from the repo root — must print nothing (apart from this guide and the plan):
grep -rn "annotations_report\|editor_client\|bb_shared_smoke" \
  --include='*.md' --include='*.clj' --include='*.bb' --include='*.lua' \
  --include='*.el' --include='Makefile' --include='*.sh' --include='*.yml' .
ls explorer/bin/
find editor -name '*.bb' | sort
```

Expected `ls`: `inspect-artifacts.bb`, `editor-client.bb`,
`bb-shared-smoke-test.bb`, `bootstrap.bb`, `clj-local`, `ci/`.
Expected `find`: only `editor-client.bb` symlinks (plus any non-script fixtures
unrelated to this rename).

## 4. If something still calls an old name

It fails loudly with "no such file" from `bb` (or the editor's
`editor-client.bb not found` error) — by design. Re-point the caller per §2;
there is no fallback path to enable.
