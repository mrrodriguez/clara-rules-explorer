# Unit provenance: detached checkouts and a `status` report

Status: **proposal**. Scope: `explorer/` manifest provenance and the offline bb
report. Additive manifest keys; one new `annotations_report.bb` subcommand.

## 1. Problem

Before a reader trusts a persisted unit, it has to answer: which commit, branch,
and working tree does this unit describe, and has any of that moved since? The
manifest records the inputs, but two gaps keep a reader from getting the answer
without reimplementing the comparison itself:

1. **A detached checkout records `:branch "HEAD"`.** `get-git-info` records
   `git rev-parse --abbrev-ref HEAD`, which is the literal `HEAD` when the checkout
   is detached. That is the normal state of a `git worktree add --detach <path>
   origin/main` worktree, a CI checkout, or a bisect. A reader asking "was this
   generated from the mainline branch?" then either reports a false mismatch or
   special-cases the string.
2. **No single command answers "is this unit current?"** The pieces exist:
   `:source` holds the checkout's sha and working-tree state, `:staleness` names
   the policy (`review-when-sha-drifts` for a source unit,
   `review-when-any-source-sha-drifts` with per-source shas for a composed unit),
   and `:analysis-run :mode` marks an aggregate. But every reader has to open the
   manifest, run `git` itself, and re-derive the verdict. Two readers doing this
   independently drift apart.

## 2. Detached checkouts

### 2.1 Resolution

In `get-git-info`, when `rev-parse --abbrev-ref HEAD` returns `HEAD`, resolve a
branch that points at the commit, in this order:

1. The remote's default branch, if it points at `HEAD`:
   `git symbolic-ref --short refs/remotes/origin/HEAD` gives e.g. `origin/main`,
   which is kept only if `git rev-parse` of it equals `HEAD`.
2. Any other remote-tracking ref on `origin` pointing at `HEAD`:
   `git for-each-ref --points-at HEAD --format='%(refname:short)' refs/remotes/origin/`,
   sorted, first.
3. Any local branch pointing at `HEAD`:
   `git for-each-ref --points-at HEAD --format='%(refname:short)' refs/heads/`,
   sorted, first.

The recorded shape:

```clojure
;; attached (unchanged)
{:sha "…" :branch "main" :working-tree "clean" …}

;; detached, resolved through origin/main
{:sha "…" :branch "main" :detached true :ref "origin/main" :working-tree "clean" …}

;; detached, nothing points at the commit
{:sha "…" :branch nil :detached true :working-tree "clean" …}
```

`:branch` has its remote prefix stripped, so a reader comparing branches doesn't
need to know whether the checkout was detached. `:ref` says where the name came
from. When nothing resolves, `:branch` is nil rather than `"HEAD"`: `HEAD` is not a
branch, and `:detached true` says why the branch is missing.

The sorted-first tie-break makes the choice deterministic, so the same checkout
always gets the same record. It is still a guess when several branches share a
commit, which is why `:ref` is recorded alongside it.

### 2.2 Schema

`schema/GitInfo` gains `(s/optional-key :detached) s/Bool` and
`(s/optional-key :ref) s/Str`. Both keys appear only on a detached checkout. As
with the manifest's top-level `:branch`, a missing key means the ordinary case.

The manifest's top-level `:branch` is the artifact-dir label a branch run chose,
and is unaffected.

### 2.3 Home

The git reads move to a bb-safe namespace,
`clara.explorer.artifacts.shared.git`, built on `clojure.java.shell` only.
`clara.explorer.artifacts.manifest/get-git-info` keeps its schema-checked
signature and delegates to it. §3's bb report reads git through the same
namespace, so the detached-checkout resolution has a single implementation.

### 2.4 Out of scope

CI environment variables that name the ref (a shallow CI clone often has no
remote-tracking refs at all). A host that knows the ref can already state it
through `:blocks` or `:working-tree-notes`.

## 3. `status` subcommand

### 3.1 Invocation

```
bb annotations_report.bb <dir> status [--checkout PATH [--ref REF]] [--root PATH] [--edn]
```

- `<dir>` is any unit directory: a source unit, a branch unit
  (`<repo>/branches/<label>`), or a composed unit.
- `--checkout PATH` is the git checkout to compare a source unit against. `--ref`
  names the commit in that checkout to compare against (default `HEAD`).
- `--root PATH` is the registry root, used to locate a composed unit's sources.
  It defaults to `<dir>` with the manifest's `:repo` (and `branches/<label>`, if
  present) stripped from the end.
- `--edn` prints the result map (§3.3) instead of the text report.

It reads `rules-inspect-manifest.edn` and nothing else from the unit. It needs no
JVM and no analysis parts.

### 3.2 Verdicts

**Source unit** (no `:analysis-run :mode`):

| Check | Needs | Verdict when it fails |
|---|---|---|
| checkout's `origin` remote equals `:source :remote` (normalized: scheme, `.git` suffix, trailing slash) | `--checkout` | `remote-mismatch`, and no sha comparison is made |
| `:source :sha` equals `--ref` resolved in the checkout | `--checkout` | `sha-drift <recorded> -> <current>` |
| `:source :working-tree` is `"clean"` | — | `generated-dirty`: the unit describes no single commit exactly |
| checkout working tree is clean | `--checkout` | `checkout-dirty` (informational) |
| `:updated` is within the policy's `:max-age-days` | — | `age-exceeded` |

With no `--checkout`, only the checks that don't need one run, and the report says
the sha was not compared.

**Composed unit** (`:analysis-run :mode` present, `:staleness :sources`): for each
source, compare its recorded `:sha` against that source unit's current manifest
under `--root`. A source reports `current`, `sha-drift <recorded> -> <current>`, or
`missing`. The unit is current only if every source is. `--checkout` is rejected
for a composed unit, since it has one checkout per source.

An aggregate that has no `:staleness :sources` (a host's own whole-rulebase unit)
is reported by kind and source provenance, with no verdict.

### 3.3 Result shape

```clojure
{:dir "…/loan-app-ruleset/branches/alt"
 :repo "loan-app-ruleset"
 :label "alt"                                   ; manifest top-level :branch, if any
 :kind :source                                  ; or :aggregate, with :mode
 :source {:sha "…" :branch "feature-x" :working-tree "clean" …}
 :updated "2026-09-29"
 :staleness {:policy "review-when-sha-drifts" :max-age-days 90}
 :compared {:checkout "…" :ref "HEAD" :sha "…"} ; when --checkout was given
 :verdict :current                              ; or :stale / :unknown
 :reasons []                                    ; e.g. [{:check :sha-drift :recorded "…" :current "…"}]
 :sources [{:repo "…" :branch "…" :recorded "…" :current "…" :verdict :current}]}  ; composed only
```

The text report is a few aligned lines rendered from this map:

```
unit       loan-app-ruleset/branches/alt
kind       source unit (label alt)
source     ac51808 (feature-x, clean)   updated 2026-09-29
verdict    stale | sha-drift ac51808 -> 9b1e0f2
```

### 3.4 Home, and the host seam

The comparison is `clara.explorer.artifacts.shared.status/unit-status`, which
returns the §3.3 map. It is bb-safe and requires only `shared.git`, `layout`, and
`clojure.edn`. The subcommand is only presentation over it.

A host that persists extra inputs through `:blocks` (tool versions, its own
configuration slices) calls `unit-status` and appends its own `:reasons` before
rendering. That covers host-specific staleness without the explorer knowing those
inputs exist.

Finding a composed unit's sources needs the unit → directory mapping
(`<root>/<repo>` or `<root>/<repo>/branches/<branch>`) that registry discovery
inverts. If that mapping lives only in JVM code today, lift it into `layout.cljc`
so discovery and `status` share it.

### 3.5 CLI parsing

`annotations_report.bb` parses args today as positional `[target cmd arg]` plus
`--file`. Replace that with a small flag table (`--file`, `--checkout`, `--ref`,
`--root`, `--edn`) that strips known flags and their values before taking the
positional args. Each subcommand rejects flags it doesn't use. `subcommands` and
`help` gain the `status` row and describe its flags.

## 4. Changes

| File | Change |
|---|---|
| `explorer/src/clara/explorer/artifacts/shared/git.clj` | new: git reads with detached resolution (§2.1) |
| `explorer/src/clara/explorer/artifacts/manifest.clj` | `get-git-info` delegates to `shared.git` |
| `explorer/src/clara/explorer/artifacts/schema.clj` | `GitInfo` gains optional `:detached`, `:ref` |
| `explorer/src/clara/explorer/artifacts/shared/status.clj` | new: `unit-status` (§3.2–3.4) |
| `explorer/src/clara/explorer/artifacts/layout.cljc` | unit → directory mapping, if not already shared |
| `explorer/bin/annotations_report.bb` | flag table; `status` subcommand and help row |
| `explorer/docs/persisted-artifacts.md` | Provenance section: `:detached` / `:ref`; how to check a unit with `status` |

## 5. Tests

- **Detached resolution** (temp git repo; no network): make a commit, point
  `refs/remotes/origin/main` at it with `git update-ref`, set
  `refs/remotes/origin/HEAD` to it with `git symbolic-ref`, then:
  - `checkout --detach` gives `:branch "main" :detached true :ref "origin/main"`;
  - a `git worktree add --detach` worktree gives the same;
  - a commit that only a local branch points at resolves through step 3;
  - a commit nothing points at gives `:branch nil :detached true`;
  - an attached checkout carries neither key.
- **`unit-status`**, over the existing `test-resources/rules-annos` fixtures plus
  temp copies whose manifests are edited:
  - a source unit against a checkout at its sha is `:current`; with one extra
    commit it is `sha-drift`; with a different `origin` it is `remote-mismatch`;
  - a composed unit is `:current`; after one source manifest's `:sha` is edited,
    it reports that source as `sha-drift`; after one source dir is removed, that
    source is `missing`;
  - `--root` defaulting strips `:repo` and `branches/<label>` correctly;
  - `--checkout` on a composed unit is rejected.
- **bb report**: `bb_report_test.clj` gains `status` text and `--edn` cases, and
  one that runs `help`.

## 6. Consumer impact

- Manifests written from a detached checkout change once: `:branch "HEAD"`
  becomes the resolved branch (or nil) plus `:detached` / `:ref`. Existing
  manifests are corrected the next time their unit is regenerated.
- A reader that special-cased `"HEAD"` can drop that case.
- No change to any artifact other than the manifest.

## 7. Acceptance

- A unit generated from `git worktree add --detach <path> origin/main` records
  `:branch "main"`.
- For any unit directory, `bb annotations_report.bb <dir> status --checkout
  <path>` prints kind, source provenance, and a verdict that matches what the
  staleness policy recorded in its own manifest says.
