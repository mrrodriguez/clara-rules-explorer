# Plan: a composed unit's status compares both links

Implements `composed-unit-status-code-drift-problem.md`. That doc is the design;
this one is the execution order, the decisions it left open, and the progress log.

Status: **shipped**. §1–2 are the approach as planned; §3 records what
changed while building it.

## 1. What has to change, in dependency order

The problem doc's §4 is one coherent change to one function, so the work is
ordered by what breaks the build first, not by file:

| # | Item | Where |
|---|---|---|
| 1 | `:checkout-off-ref` in the source-unit check (§4.4) | `status.clj`, `git.clj` |
| 2 | Composed unit takes `:source-checkouts` / `:source-status-fn` (§4.1, §4.2) | `status.clj` |
| 3 | `:sources` entries `{:unit-key :composition :unit-status :verdict}`, reasons `:unit-stale` / `:unit-unknown`, link-B reasons keyed `:unit-key` (§4.3) | `status.clj` |
| 4 | Docstrings for the new contract (`unit-status`, ns) | `status.clj` |
| 5 | `--source-checkout <repo>=<path>`, repeatable; usage rows; `sources` lines; reason summaries | `inspect-artifacts.bb` |
| 6 | §"Checking a unit: `status`" + the `:staleness` paragraph, in two-link wording | `persisted-artifacts.md` |
| 7 | The nine cases from §5, plus the off-ref case in the source-unit suite | `status_test.clj` |
| 8 | `make test lint reflection-check format-check`, `make bb-smoke-test` | verification |

Why this order: 1 is independent and smallest; 2 and 3 are one edit to the same
two functions (`check-source`, `composed-unit-status`) because the new
`:sources` shape *is* the per-source report — splitting them would mean writing
the old shape twice; 5 depends on 2–3 existing; 7 depends on all of them.

## 2. Decisions the problem doc left to implementation

- **Off-ref needs a branch read that does not exist yet.** `git/ref-sha`,
  `git/remote-url`, `git/clean-tree?` are the checkout seam `status.clj`
  compares through; none reports the checkout's branch. Add
  `git/attached-branch`: `rev-parse --abbrev-ref HEAD`, nil when detached
  (`HEAD`) or unreadable. It belongs beside the other three reads, since bb and
  the JVM must resolve the same way.
- **A detached checkout is not off-ref.** §4.4 says compare the checkout's
  branch with the unit's recorded `:source :branch`. A detached checkout has no
  branch, so there is nothing to compare and the sha comparison is the only
  evidence there is — a CI `--checkout` at exactly the recorded commit must stay
  `:current`. So the guard fires only when *both* branches are non-nil and
  differ. A nil recorded branch (a commit no remote branch points at, see
  `git/get-git-info`) likewise leaves the sha comparison alone.
- **Off-ref sits after the remote/ref checks.** A checkout that is not a repo,
  has a different origin, or whose explicit `:ref` does not resolve says
  something stronger than "another branch"; those reasons stay as they are, and
  off-ref only pre-empts the sha comparison.
- **Keeping `:ref-given?` internal.** `checkout-comparison` resolves `:ref` to
  `HEAD` for display, so it also records `:ref-given?`; the report's
  `:compared` key stays `{:checkout :ref :sha}`.
- **Recursion goes through a private entry point.** `:source-checkouts` is
  shorthand for a `:source-status-fn` that calls back into the comparison for
  each source unit. Rather than `(declare unit-status)`, the shorthand builder
  takes the status function as a parameter and the comparison passes itself;
  recursion inside a `defn` body resolves fine. (As built, the guards forced
  that function to be a private `unit-status*` — see §3.)
- **`:source-checkouts` / `:source-status-fn` on a source unit throw** at the
  public entry point, exactly as `:root` already does on one — they name what a
  composed unit's sources are judged by, and a source unit has none.
- **Per-source keys are named for what they judge.** `:composition` (link B)
  uses `:recorded` / `:unit-sha` per §4.3 — never `:current`, which §3 calls out
  as the word that reads like "what the code is now". Link-B top-level reasons
  move from `:source` to `:unit-key`, matching the `:sources` entry.
- **`aggregate-no-sources` stays `:unknown`.** A composition with an empty
  `:staleness :sources` has no per-source report to make, so there is nothing to
  improve; the existing behavior holds.
- **One verdict fold.** `merge-verdicts` gives per-source entries the same
  precedence `->verdict` gives reasons (`:stale` > `:unknown` > `:current`);
  `:unit-stale` / `:unit-unknown` join the reason check sets so the top-level
  verdict is still computed by `->verdict` from the top-level reasons.
- **CLI parsing.** `--source-checkout` is `:coerce [:string]` into a map of
  `repo -> path`, dying on a value without `=` and on a repo named twice. The
  flag is accepted by the `status` subcommand only; whether it *applies* is the
  manifest's business, so `unit-status` throws for a source unit and the CLI
  reports that message, symmetric with `--checkout` on a composed unit.
- **`--ref` does not reach sources.** `--source-checkout` fills only
  `:source-checkouts`; the per-source call passes no `:ref`, so each source is
  guarded by §4.4. `--ref` stays bound to `--checkout` and to source units.

## 3. Progress log

Worked top to bottom; each entry is a checkpoint, not a diary.

- [x] 1 `git/attached-branch`; `:checkout-off-ref` in `sha-reasons`
- [x] 2 `:source-checkouts` / `:source-status-fn` on a composed unit
- [x] 3 `:sources` shape + `:unit-stale` / `:unit-unknown`
- [x] 4 docstrings
- [x] 5 bb `status` presentation + `--source-checkout`
- [x] 6 `persisted-artifacts.md`
- [x] 7 tests
- [x] 8 full verification

### As built, and what changed on the way

- **The entry point split in two.** Passing `:source-checkouts` down so a
  composition-as-a-source recurses (§4.2) collides with rejecting it on a source
  unit: the shorthand calls one function for both kinds. So `unit-status` keeps
  the per-kind argument guards and delegates the comparison to a private
  `unit-status*`, which the shorthand calls with both `:checkout` and
  `:source-checkouts` — each ignored by the kind it does not apply to. The
  public function is still what a host calls and what the bb script uses; the
  guard messages name both the keyword and the flag.
- **`merge-verdicts` is needed, not just `->verdict`.** A source's `:verdict`
  folds two reports, and `->verdict` only folds reasons; the two share the same
  precedence, and the top-level verdict still goes through `->verdict`.
- **`:sha-not-compared` reads "sha not compared".** The old fragment said
  "(no --checkout)", which is a CLI fact and false when a host calls the
  function; nested under `:unit-unknown` it read as a doubled parenthetical.
- **The bb presentation grew three helpers** — `source-cells` (the three columns
  of a source row), `unit-fragment` (a source unit's verdict plus its reasons),
  and `source-checkouts` (flag values to `{repo path}`). Widths are computed
  from the rows, so a long variant key never misaligns the columns.
- **`reason-summary` recurses.** `:unit-stale` / `:unit-unknown` nest the
  source's own reasons, which may nest again for a composition-as-a-source.

## 4. Verification

```bash
cd explorer
make test             # 519 tests, 3477 assertions, 0 failures
make lint             # 0 errors, 0 warnings
make reflection-check # no warnings in project code
make format-check     # all source files formatted correctly
make bb-smoke-test    # all 15 bb-loaded namespaces require
```

The `inspect-artifacts.bb` suite also runs `status` end to end through `bb`, and
now covers the composed rows, `--edn`'s per-source shape, `--source-checkout`
against a path that is no checkout at all (so the covered source reads
`checkout-not-a-repo` and the uncovered one `sha not compared` — hermetic, with
no dependence on the runner's own checkout), a malformed flag value, either
empty side of `<repo>=<path>`, a repo named twice, `--source-checkout` on a
source unit, `--checkout` on a composed unit, and a composition-of-a-composition
(the nested `sources` and `verdict` lines, pinned whole).

A hand check over a manifest-only registry in `explorer/target/tmp/reg` (three
units, no git repos — this sandbox denies writes under `.git`, so a checkout
reads as "not a git checkout") confirmed the `sources` alignment and the
`verdict` summary lines of the problem doc's worked example:

```
sources    repo-a                   composition current  unit unknown (sha not compared)
           repo-b@env=dev/ref=main  composition current  unit unknown (sha not compared)
verdict    unknown | repo-a: unit sha not compared; repo-b@env=dev/ref=main: unit sha not compared
```

The real-checkout path (drift, off-ref, remote mismatch) is covered by the unit
suite's stubbed git reads. The one CLI assertion that still reads the runner's
own checkout is the pre-existing `--checkout` one; it needs that checkout to
have an `origin` remote, which a clone does.

## 5. Review pass: what the first implementation left open

A review of the shipped branch found the design implemented, and these gaps:

- **A report function that returns nothing restored the old bug.** `nil` (or a
  map without `:verdict`) was folded into "no report", so the source and the
  composition read `:current` — the very verdict the change exists to stop.
  Now such a source is `:unit-unknown` with `:unit-report-missing`, and a
  report that is not usable is never taken as current.
- **The new recursion had no cycle guard.** A composition that reaches itself
  stack-overflowed the CLI. The walk of unit dirs (`:seen`, seeded per call and
  threaded through `->source-status-fn`) now reports `:source-cycle` under
  `:unit-unknown` instead of recursing.
- **`merge-verdicts` was a second encoding of `->verdict`'s precedence** and is
  derivable from a source's own reasons. Deleted: a source's `:verdict` is
  `(->verdict reasons)`, so a per-source verdict can no longer disagree with
  the reasons nested under it.
- **`check-source` (5 args) and `composed-unit-status` (4) took positional
  argument lists**; both now take one map. `check-source` also returns
  `{:entry :reasons}` instead of building a `:unit-status nil` key for
  `composed-unit-status` to dissoc.
- **`unit-status` mixed argument guards with delegation**; the guards moved to
  `assert-kind-args!`.
- **`git/attached-branch` duplicated the attached-branch read** that
  `get-git-info` performed inline, guard included. `get-git-info` now calls it,
  so one function owns the read — and the new function inherits real-git tests.
- **CLI:** `--source-checkout =<path>` was silently ignored (no empty side
  check); the `subcommands` row still advertised `status [--checkout]`; the
  usage skeleton did not mark the flag repeatable; each `status` usage row
  aligned its own continuation lines, so the long flag shifted its column. The
  rows are now one table with a computed syntax width.
- **Tests:** `attached-branch` had none (now real-git + boundary cases);
  off-ref was unreachable in the composed suite (fixtures recorded no branch,
  and the stub defaulted to detached — it now defaults to the fixtures' branch,
  and a composed off-ref case exists); both-links-broken, report-missing,
  cycle, duplicate/empty `--source-checkout`, and `:source-status-fn` on a
  source unit are now covered; `:sources` entry key sets are pinned; each
  composed case gets its own root instead of mutating a shared one;
  `:checkout-off-ref` and the nested CLI lines are asserted.
