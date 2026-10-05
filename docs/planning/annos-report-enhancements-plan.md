# `annotations_report.bb` enhancements

> **Superseded.** See [`annos-report-changes-plan.md`](annos-report-changes-plan.md):
> it replaces Part A (`digest`) and removes federation; Part B (`production`)
> landed and stands. The `digest` / `federate` / `rule`-flag material below is
> history, not current.

Status: **proposal** (superseded). Scope: `explorer/bin/annotations_report.bb`, the bb-loadable artifact
namespaces it requires, `test/clara/explorer/artifacts/bb_report_test.clj`, and the
`annotations_report.bb` section of `explorer/docs/persisted-artifacts.md`. No artifact change.

Two persisted values have no reader in the offline report, so a caller who needs them writes
`bb -e '(-> (slurp …) clojure.edn/read-string …)'` by hand. Each hand-rolled read restates a
filename, a file shape, and a key path that the report already owns for every other part. Both
parts below are independent and can land in either order.

## 1. Part A: read `registry-digest.edn` by key

### 1.1 Problem

`federate/persist!` writes `registry-digest.edn` beside `registry-index.edn`, and
`flow/compose-persist!` can make the same directory a unit that the report reads. The digest
holds what a reader checks before it trusts a federated answer: `:coverage` (the units merged, the
namespaces in scope that no unit covers), `:hierarchy-conflicts`, `:unit-edges`, `:entry-points`,
`:orphans`, `:provenance`. `->digest` documents it as the agent-readable reduction of the index.

The report reads every other part of such a directory but not this one. The file is small enough
to read in one process (about 180 KB for a 20-unit federation), but it is too large to load into
an agent's context whole. So the caller selects keys with `bb -e`.

A host can add keys under `:coverage` (for example, the components its own topology names that no
unit supplies). A generic reader prints those without special cases.

### 1.2 Change

- **`digest [<key> …]`**, a new subcommand. It reads only `registry-digest.edn` in the target
  directory, through `layout/artifact-files :registry-digest`.
  - **No key:** print `:summary`, then one line per remaining top-level key with its element
    count. That is enough to choose what to read next.
  - **One or more keys:** print `(select-keys digest keys)`. A key may be written `coverage` or
    `:coverage`. If a key is not in the digest, the command fails and lists the keys that are.
  - **`--edn`:** print the selected value as EDN. Without it, `pprint` it. The value is already
    data, so text and EDN differ only in layout.
- **A directory with no digest** fails with a message that names the file, and says that only
  federation (`federate/persist!`) writes it. A plain ruleset unit never has one.
- **Optional, `coverage`:** a text view of `:coverage` plus `:hierarchy-conflicts`, so the check
  before trusting an answer is one command with readable output. Unknown keys under `:coverage`
  print as `key: value` lines, so host additions show up. Decide on this after `digest` lands, and
  only if callers need more than `digest coverage hierarchy-conflicts`.
- Add both to `subcommands`, so `help` lists them.

### 1.3 Tests (`bb_report_test`)

- `digest` with no key prints `:summary` and every other top-level key with a count.
- `digest coverage hierarchy-conflicts` returns exactly those two keys. `:coverage` and `coverage`
  give the same result.
- An unknown key fails, and the message lists the keys present.
- A ruleset unit with no `registry-digest.edn` fails with the message that names the file.
- An extra key under `:coverage` in a fixture digest is printed unchanged.

## 2. Part B: one production's full record, condition tree included

### 2.1 Problem

`rule <fq-name>` prints the merged annotation, with callsites and `:unit`. It reads the
annotations and `production-index.edn`. It never shows what lives in the other two production
parts:

- `production-conditions.edn`: `:lhs`, the condition trees. Polarity is visible only here.
  `:lhs-types` flattens it, so a type matched under `[:not …]` looks the same as a positive match.
  Join bindings (`:new-bindings`, `:binding-keys`, `:join-filter-join-bindings`) and accumulator
  shape (`:accumulator {:form :some-initial-value?}`, `:from`) are here too.
- `production-details.edn`: `:rhs-form`, `:doc`, `:props`.

So to answer "does this rule need T, or fire when T is absent?", a caller reads
`production-conditions.edn` (about 4 MB for a 3,400-rule rulebase) with `bb -e` and pulls one key.
Queries have the same gap: they are not in the annotations, so `rule` cannot find them at all.

`diff --rule NAME` already joins all three parts for one production
(`diff/read-productions` → `productions-of`), but it needs two units and prints a before/after.

### 2.2 Change

- **`production <fq-name>`**, a new subcommand: one production's joined record from the three
  production parts. It covers rules and queries.
  - **Name resolution:** the same as `rule`. `find-key-name` resolves over the
    `production-index.edn` keys (`:rules` and `:queries` merged). It prints `Resolved … -> …` for
    a unique substring match, and lists the candidates when there are several.
  - **Text output:** `kind`, `ns`, `unit` (when composed), `lhs-types`, `insert-types`,
    `retract-types`, `resolution`, `doc`. Then `lhs`, pretty-printed, one condition per entry.
    Then `rhs-form`.
  - **`--part lhs|rhs|props`:** print only that field. `--part lhs` is the common case, because a
    polarity or binding question needs nothing else.
  - **`--edn`:** the record as EDN, the same shape `productions-of` builds.
- **Read only what is asked for.** Extract the single-production join from `productions-of` into a
  bb-loadable function (in `clara.explorer.artifacts.diff`, or a small neutral namespace that
  `diff` then requires). It takes a unit dir and a resolved name. It reads `production-index.edn`
  for resolution and the index fields, then only the parts the output needs. `--part lhs` never
  opens `production-details.edn`. `:resolution` comes from the merged annotations, so read them
  only for the full record, not for `--part`.
- **Keep `rule` as it is.** It is the annotation reader, and `production` is the analysis reader.
  Update `rule`'s help line to name `production` for `:lhs` and `:rhs-form`.

### 2.3 Tests (`bb_report_test`)

- A rule with a `[:not …]` condition: `production … --part lhs` shows `:condition-type :not` and
  its children. `rule` on the same name does not show them.
- An accumulator condition: `:accumulator`, `:from`, and `:result-binding` are present.
- A query resolves and prints with `kind query`.
- A substring that matches one production prints `Resolved`. A substring that matches several
  lists them and exits non-zero.
- `--part lhs` does not read `production-details.edn` (assert that the file is never opened, or
  remove it from a fixture copy and make sure that the command still succeeds).
- `--edn` equals the `productions-of` entry for the same name.

## 3. Docs

- `explorer/docs/persisted-artifacts.md` §"Querying it offline": a row for each new subcommand,
  and which part files each one reads.
- §"Writing your own reader": point at `digest` and `production` before the `bb -e` advice, for the
  two values they now cover.

## 4. Verification

- `make test`, `make lint`, `make format-check`, `make reflection-check` (explorer).
- Smoke checks over a real federated directory (`digest`, `digest coverage`) and a real unit
  (`production` on a rule, a query, and a substring).
