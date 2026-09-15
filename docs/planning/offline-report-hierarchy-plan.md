# The offline report ignores the fact-type hierarchy — plan

Status: **Proposed** · Scope: `server/bin/annotations_report.bb` · Related:
[`artifact-registry-plan.md`](artifact-registry-plan.md),
[`composed-artifact-persist-plan.md`](composed-artifact-persist-plan.md),
`server/docs/persisted-artifacts.md`, `tools/graph/artifacts/slim.clj`,
`tools/graph/artifacts/federate.clj`

## 1. The defect

`bin/annotations_report.bb`'s `producers` and `consumers` match fact-type names
literally. Neither closes over `:ancestors`, so both answer the wrong question,
and `producers` answers it with a number that reads as a finding:

```
$ bb bin/annotations_report.bb \
     test-resources/rules-annos/composed/loan-app-plus-disposition \
     producers :loan-app/application-outcome

0 producer rule(s) for loan-app/application-outcome
```

Three rules in that directory produce it. Everything needed to say so is in the
same directory:

- `merged-rulebase-analysis/fact-types.edn` records
  `clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome` with
  `:ancestors [":loan-app/application-outcome" …]` — inserting the record *is*
  producing the ancestor, because the fact it makes is one of each.
- `merged-rulebase-analysis/dep-graph.edn` already links them: the consuming
  rule `notice-approved-app`, whose only `:lhs-types` entry is
  `":loan-app/application-outcome"`, has `:upstream` naming the three
  `loan-app-rules` productions that insert the record.

The consumer side misses symmetrically. `consumers ApplicationOutcome` returns
the three rules that name the record exactly and omits `notice-approved-app` /
`notice-denied-app`, which match its ancestor and therefore *do* fire on an
`ApplicationOutcome` fact.

### Why this is a wrong answer rather than a limitation

The script is internally inconsistent about the same relationship. `edges` is
hierarchy-aware — not by doing any work, but because `:dep-graph` was built with
the closure already applied (`compose/->dep-graph` and the live
`core/->dep-graph` both walk each produced type's ancestors to find consumers).
So one subcommand reports the cross-type link and another denies it, over one
directory, with no way for the reader to tell which to believe.

It also lands on the least-defended reader. The offline report is what someone
with the files and no classpath uses, and `slim`'s `:slim :recover` prose sends
them there. A `0 producer rule(s)` for a type with three producers is exactly
the failure mode that audience cannot check.

### Scope

| subcommand | reads | affected |
| --- | --- | --- |
| `producers` | annotations `:clara-rules/insert-types` / `:retract-types` | **yes** |
| `consumers` | `production-index.edn` `:lhs-types` | **yes** |
| `edges` | `dep-graph.edn` | no — closure already in the data |
| `rule`, `summary`, `gaps`, `curated`, `layers`, `types` | annotations | no — none of them resolves a type across the hierarchy |

`fact-types.edn` is not opened by any subcommand today; the script's own header
comment lists it among the files it knows about.

## 2. The fix

Open `fact-types.edn` in the two affected subcommands and close each direction —
opposite directions, for the same reason `slim`'s `recovery` gives for
`:inserted-by-rules` versus `:used-by-rules`:

- **`producers T`** — rules inserting (or retracting) `T` **or any descendant of
  `T`**. A rule that makes a descendant makes a `T`. Descendants come from
  transposing the recorded `:ancestors`.
- **`consumers T`** — rules whose `:lhs-types` contain `T` **or any ancestor of
  `T`**. A rule matching an ancestor is activated by a `T`.

Getting the directions backwards produces a plausible-looking answer that is
wrong in a different way, so the two closures are worth naming in the code the
way this section names them.

### Output

Report the two classes separately rather than merging them, so a reader can see
why a rule is in the list — the same distinction `federate/impact-of` draws
between `:matched-by` and `:matched-via-ancestor`:

```
5 producer rule(s) for :loan-app/application-outcome
  (2 exact, 3 via descendants)

exact
  …/emit-outcome
    inserts: :loan-app/application-outcome

via descendant clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
  …/app-outcome-approved?
    inserts: clara.server.tools.graph.rules.loan_app_rules.ApplicationOutcome
```

A reader who wants only the literal answer can still see it; a reader who was
about to conclude "nothing produces this" cannot miss the rest.

### Three decisions

- **Substring matching happens first, then closure.** `producers` / `consumers`
  accept a substring today, which is a deliberate affordance for someone who
  half-remembers a name. Resolve the argument to the set of *known* type names
  it matches (from `fact-types.edn`), print what it resolved to when it is more
  than one, and close over those. Closing over a substring match directly would
  compound two kinds of fuzziness.
- **Do not filter the recorded ancestors.** A record type's `:ancestors`
  includes its Java interfaces (`clojure.lang.IHashEq`, `IPersistentMap`, …)
  because that is what the ancestor function returns and a rule may legitimately
  match one. Closing honestly means `producers clojure.lang.IHashEq` lists every
  record producer — correct, and the exact/via split makes it legible. Dropping
  interfaces would be the library deciding which of its own recorded ancestors
  count.
- **Cost is one small file, read lazily.** Parts are already loaded on demand
  (`->analysis-part`) and annotations through `delay`, so the seven unaffected
  subcommands pay nothing. `fact-types.edn` is 12KB in the checked-in composed
  unit and ~21KB in a large real one.

## 3. Secondary: show `:unit` when the set is composed

`compose/->composed-analysis` stamps `:unit` on every production and `parts`
routes it into `production-index.edn`, so a composed set already carries unit
attribution on disk — all 25 productions in the checked-in composed unit have
it. `consumers` and `rule` do not print it.

Printing it when present costs a `when-let` and answers "which component is this
rule from" without the reader opening the file. It is the one question a reader
of a composed set asks that the script currently cannot answer, even though the
data is in the part it already reads.

## 4. Tests

The checked-in fixtures cover it without new data — they exist for exactly this
cross-type relationship:

- `producers :loan-app/application-outcome` over
  `composed/loan-app-plus-disposition` finds the record producers and labels
  them as via-descendant (today: zero hits).
- `consumers <the record>` includes `notice-approved-app` and
  `notice-denied-app`, labelled via-ancestor (today: absent).
- The two agree with `dep-graph.edn`: every rule `edges` reports upstream of a
  consumer appears in `producers` for that consumer's `:lhs-types`. That
  cross-check is the regression guard — it is the invariant the current
  inconsistency violates.
- Over a single source unit (`loan-app-ruleset`), the exact-match results are
  unchanged, so the closure adds rather than rewrites.

## 5. Non-goals

- **No merge in babashka.** The script reads one artifact set; nothing here
  changes that.
- **No new artifact files.** `fact-types.edn` is already written and already
  listed in `layout/artifact-files`.
- **No registry awareness.** Cross-unit questions are `federate`'s; this is the
  within-one-directory answer being correct about the hierarchy that directory
  records.

## 6. Phases

1. Load `fact-types.edn` lazily, build the ancestor map and its transpose.
2. `producers` closes over descendants; `consumers` over ancestors; both print
   the exact / via-hierarchy split.
3. Resolve the substring argument to known type names before closing, reporting
   an ambiguous resolution.
4. Print `:unit` where present (§3).
5. Tests per §4, including the `edges` cross-check.
