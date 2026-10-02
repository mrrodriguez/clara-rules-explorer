# Registry variants: absolute, multi-axis unit addresses

Status: **proposal**. Scope: `explorer/` artifact addressing (`layout`, `store`, `registry`,
`schema`, `manifest`, `compose`, `federate`, `flow`, `shared/*`) and the bb readers
(`annotations_report.bb`, `editor_client.bb`). Builds on `unit-provenance-status-plan.md`: the
`ref` axis below is read through its detached-checkout resolution in
`clara.explorer.artifacts.shared.git/get-git-info`.

## 1. Problem

A unit is addressed as `{:root :repo :branch}`. `:branch` is one opaque, caller-chosen label,
written at `<root>/<repo>/branches/<label>/`. Hosts that keep more than one kind of variant run
into four limits:

1. **One label, many dimensions.** A host that analyzes a repo at several build coordinates
   (say a region and a tier) *and* on several git branches has to pack all of them into one
   string. Whatever it packs is a private convention that no reader can parse back.
2. **Labels are relative.** A host that names only the axes a run deviates on has to relabel
   every variant whenever its baseline moves. One that lets a branch label win alone can keep
   only one set of coordinates per branch: re-running the branch elsewhere overwrites it.
3. **The label is not checked against the source.** `:branch` is whatever the caller passes. A
   run from a checkout on `feature/x` can be written as the mainline unit, or under another
   branch's label. Nothing in the address says which commit produced the artifacts. Only
   `:source` does, and few readers look there before trusting a directory.
4. **Variants sit inside the mainline directory.** `<repo>/branches/…` nests scratch work under a
   unit that is usually committed, so a host ignoring variants has to ignore a pattern inside
   every repo directory.

## 2. Model

A unit's address is **absolute**: the repo, an ordered set of host-defined axes, and the git
ref the artifacts were generated from.

```
<root>/
  <repo>/                                     mainline unit
  _variants/
    <repo>/
      <axis>=<value>/…/ref=<ref>/             variant unit
```

```
<root>/
  orders-ruleset/
  _variants/
    orders-ruleset/
      region=eu/tier=gold/ref=main/
      region=eu/tier=gold/ref=feature%2Fnew-tax/
      region=us/tier=gold/ref=feature%2Fnew-tax/
```

- **Axes are the host's.** clara-rules-explorer knows no axis names. The host passes them as an
  ordered vector of `[axis value]` pairs, and that order is the directory nesting. A host with
  no build coordinates passes none, and its variants are `_variants/<repo>/ref=<ref>/`.
- **`ref` is clara-rules-explorer's.** It is always the last level, and it is read from the
  checkout the run analyzed, never passed by the caller (§4).
- **Mainline is a host decision, checked against the source.** A run writes `<root>/<repo>/`
  only when the host marks the run's axes as canonical **and** the checkout's ref is the
  remote's default branch. Every other run writes under `_variants/`. A dirty working tree does
  not change this. It is recorded in `:source :working-tree` as it is now.
- **`_variants/` is a single root-level holder,** so `<repo>/` holds exactly the mainline unit,
  and a host that keeps variants out of version control ignores one path.

Because the address is absolute, it never needs relabeling. A host finds a unit by computing its
path from the coordinates and ref it wants, with no lookup of a baseline.

## 3. Segment encoding

A segment is `<axis>=<encoded value>`. It is split on its first `=`, so a value may contain `=`.

**Axis names** match `[a-z0-9][a-z0-9-]*` as unqualified keywords. `ref` is reserved. A host axis
named `ref`, a namespaced keyword, or a name outside that pattern is refused.

**Values** are non-empty strings, percent-encoded on exactly four characters, and passed through
otherwise:

| char | encoded | why it is reserved |
|---|---|---|
| `%` | `%25` | the escape character itself, which is what makes decoding unambiguous |
| `/` | `%2F` | the level separator; git branch names use it (`feature/new-tax`) |
| `@` | `%40` | the `repo@variant` unit-key separator (§6) |
| `+` | `%2B` | the join for a host that lists several values in one segment |

A value that encodes to `.` or `..` is refused, replacing `store/get-branch-path`'s check.

Encoding and decoding live together in `clara.explorer.artifacts.layout`, which bb also loads, and
both are public. A host that builds its own paths from values, such as a composed unit's
directory name, encodes through the same functions instead of writing its own escaping. Nothing
outside `layout` ever decodes a path.

## 4. The `ref` axis

At write time, `ref` is `get-git-info`'s `:branch` for the analyzed checkout: the attached
branch, or for a detached checkout, the remote branch pointing at the commit. When that is nil
(a detached commit no remote branch points at), `ref` is the short sha.

`get-git-info` also returns `:default-branch`: the remote default (`origin/HEAD`'s target, with
the remote alias stripped), or nil when the remote doesn't advertise one. A nil default branch
makes no run mainline, so it writes under `_variants/`.

Neither the caller nor the host can set `ref`. So a checkout on `feature/x` cannot produce the
mainline unit, or a unit at another branch's address. The guard is in the address, not in a
check someone has to remember to run.

## 5. Write side

`schema/ArtifactOpts` and `schema/ManifestOptions` replace `:branch` with:

```clojure
{:variant    [[:region "eu"] [:tier "gold"]]   ; host axes, in nesting order; may be empty
 :canonical? false}                             ; the host's claim that these axes are mainline
```

`store/get-out-dir` resolves the git info of `:repo-path` (default: the process's cwd, matching
`manifest/->manifest`) and returns:

- `<root>/<repo>/` when `:canonical?` and `ref` = `:default-branch`;
- otherwise `<root>/_variants/<repo>/<encoded axes…>/ref=<encoded ref>/`.

An explicit `:dir` still wins outright, as it does now.

`layout/unit-dir` and `layout/default-root` take `:variant` (now including `ref`) in place of
`:branch`, and strip `_variants/<repo>/<variant…>` when recovering the root.

## 6. Read side

**Discovery.** A unit path that starts with `_variants/` splits into a repo (every segment
before the first `<axis>=…` segment) and a variant (that segment and everything after it). Any
other path is a mainline unit whose repo is the whole path. A repo path may therefore contain
`/` but no `=` in any segment. The reserved `branches` segment is removed.

A path under `_variants/` that does not split this way — no `<axis>=…` segment, an empty repo
before the first one, or a non-`<axis>=…` segment after it — is skipped rather than thrown.
Discovery also compares each variant unit's decoded path with the manifest's `:variant`, and
reports any directory where they differ (e.g. one renamed by hand).

**`schema/UnitRef`** becomes:

```clojure
{:repo s/Str
 (s/optional-key :variant) [[(s/one s/Keyword "axis") (s/one s/Str "value")]]   ; ends with [:ref …]
 (s/optional-key :namespaces) [(s/cond-pre s/Str s/Symbol)]}
```

**Unit keys** (`shared.registry/unit-key`) become `<repo>` or `<repo>@<encoded variant path>`,
e.g. `orders-ruleset@region=eu/tier=gold/ref=feature%2Fnew-tax`. The status parser's split on
the last `@` still holds, because values encode `@`.

## 7. Manifest

The top-level `:branch` is replaced by `:variant`: the decoded `[axis value]` vector including
`ref`, absent on a mainline unit. `:source` is unchanged. A composed unit's `:analysis-run
:units` and its per-source staleness entries carry `:variant` in place of `:branch`.

## 8. Changes by namespace

| Namespace / file | Change |
|---|---|
| `artifacts.layout` | `variants-subdir` (`"_variants"`) replaces `branches-subdir`; public segment encode/decode; `segments->unit-ref` (shared discovery split); `unit-dir` and `default-root` take `:variant` |
| `artifacts.store` | `get-out-dir` resolves the ref and applies the mainline rule; repo + axis/value validation replaces `get-branch-path` |
| `artifacts.shared.git` | `get-git-info` adds `:default-branch` |
| `artifacts.schema` | `UnitRef`, `ArtifactOpts`, `ManifestOptions`, the git-info schema, composed-unit entries, `UnitInfo` + `VariantMismatch` |
| `artifacts.registry` | discovery walk delegates to `layout/segments->unit-ref`; path/manifest consistency report; `unit-ref`/`->unit-info` carry `:variant` |
| `artifacts.shared.registry` | `unit-key` over `:variant` |
| `artifacts.manifest` | top-level `:variant` |
| `artifacts.compose`, `artifacts.flow`, `artifacts.federate` | `:variant` wherever `:branch` is carried today |
| `artifacts.shared.status`, `bin/annotations_report.bb` | unit-key parsing and the `unit` line print the variant path |
| `bin/editor_client.bb` | discovery uses the shared `layout/segments->unit-ref` |
| `explorer/docs/persisted-artifacts.md`, `explorer/docs/registry-architecture.md` | describe `_variants/`, axes, `ref`, encoding |

`branches/` is removed outright, with no migration path. A registry rebuilds its variants by
re-running them.

## 9. What stays with the host

- Which axes exist, their order, and what values mean, including any word for "no value": a
  value is always a non-empty string.
- Whether a run's axes are canonical (`:canonical?`).
- Which checkout each repo is analyzed from. clara-rules-explorer reads the ref off whatever
  checkout it is given.
- Pruning a variant made redundant when the host's canonical axes move onto it.
- Naming composed units. Their directory is the caller's `:repo`/`:dir`, built with `layout`'s
  public encoder when it embeds values.

## 10. Tests

- **Encoding:** round-trip for each reserved character and for values containing `=`; refusal
  of `.`/`..`, blank values, bad axis names, and a host axis named `ref`.
- **Mainline rule:** canonical + default branch writes the base dir; canonical + other branch,
  non-canonical + default branch, and nil `:default-branch` all write under `_variants/`; a
  dirty canonical default-branch run writes the base dir and records `dirty`.
- **`ref`:** an attached branch, a detached commit at a remote branch, and a detached commit at
  no remote branch (short sha).
- **Discovery:** mainline and variant units, nested repo paths, `feature%2F…` values, and the
  path/manifest mismatch report.
- **Unit keys:** round trip through `status` parsing with `@` and `/` in a ref.
- **Compose and federate:** a selection mixing a mainline unit and variant units of the same
  repo, with `:variant` carried into the composed manifest and per-source staleness.
