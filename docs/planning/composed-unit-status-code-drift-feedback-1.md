# Feedback 1: integrating the composed-unit status as a host

Status: **proposed**. Follows `composed-unit-status-code-drift-plan.md`. It comes from integrating
that work into a host: a downstream tool whose source unit reports carry inputs of its own and
whose checkouts live where only it knows.

The design holds up. With `:source-status-fn`, a host's composed report reads each source exactly
as the host reads that unit alone. A source behind its code now makes the composition `:stale`,
which is the bug the problem doc set out to fix.

Two gaps remain:

1. A host's `:source-status-fn` has no cycle guard.
2. A host has to copy logic that only this library should own: the reason text, the `sources`
   rows, the verdict line, `--source-checkout` parsing, and the per-kind argument guards.

## 1. The cycle guard stops at the host's function

`:seen` reaches only the default source function. A host function that reports a source by calling
the public `unit-status` starts a fresh walk, seeded with that source alone. A cycle through two
compositions never ends:

```
_compose/a   sources: repo-x, _compose/b
_compose/b   sources: repo-y, _compose/a

unit-status a
  → host fn on _compose/b → unit-status b   (seen #{b})
    → host fn on _compose/a → unit-status a (seen #{a})
      → host fn on _compose/b → …           stack overflow
```

The direct case (`a` naming `a`) is caught, because `check-source` checks `:seen` before calling
any function. The indirect case is not.

### Proposal: the library walks compositions, and the host reports only source units

A host has nothing of its own to say about a composition. Its inputs belong to source units. So the
library recurses into a composition source itself, with `:seen`, and calls `:source-status-fn`
only for a source unit (a unit with no `:mode`).

- The host function never recurses, and never sees a composition.
- The walk is the library's alone, so every cycle is reported as `:source-cycle` whatever the
  caller passed.
- The `:source-status-fn` contract narrows to: report this source unit.

```clojure
;; _compose/a, with a host function
(status/unit-status {:dir "/reg/_compose/a" :source-status-fn host-report})

;; host-report is called for repo-x and repo-y only. _compose/b is walked by the library, and its
;; source _compose/a is :source-cycle under :unit-unknown.
```

## 2. The host's function gets the caller's checkout

Today `:source-checkouts` and `:source-status-fn` exclude each other. So a host that accepts
`--source-checkout` keeps the map itself and looks each repo up inside its function. It also has to
guard the flag on a source unit itself, because the library never sees the map. That duplicates
both the lookup and the guard.

### Proposal: allow both, and pass the entry to the function

`:source-status-fn` receives the caller's checkout for that source's repo, or nil:

```clojure
(status/unit-status {:dir "/reg/_compose/pair"
                     :source-checkouts {"repo-a" "/src/repo-a"}
                     :source-status-fn host-report})

;; host-report receives
{:unit-key "repo-a" :repo "repo-a" :dir "/reg/repo-a" :checkout "/src/repo-a"}
{:unit-key "repo-b@env=dev/ref=main" :repo "repo-b" :variant [[:env "dev"] [:ref "main"]]
 :dir "/reg/_variants/repo-b/env=dev/ref=main" :checkout nil}
```

A nil `:checkout` leaves the host free to use its own default. The library keeps guarding
`:source-checkouts` on a source unit, so the host drops its own guard. The "not both" throw goes
away.

## 3. Usage errors a host can tell apart

The guards throw a plain `ex-info`. A host command line can't tell bad arguments (exit 2) from a
failed comparison (exit 1) without matching message text.

- **Mark them.** Every argument guard's ex-data carries
  `:clara.explorer.artifacts.status/invalid-args true`.
- **`:ref` on a composed unit throws.** Today it is silently ignored. `:ref` names a commit in one
  checkout, and a composition has one checkout per source.
- **An aggregate with no `:staleness :sources`** (a whole-rulebase capture, for example) gets its
  own message for `:checkout`/`:ref`/`:source-checkouts`: it has no sources and no checkout. "One
  checkout per source" is wrong for it.

## 4. The presentation is library, not script

`explorer/bin/inspect-artifacts.bb` holds the only renderer of a status report. A host with its own
command line can't require a script, so it copies the renderer: every reason's text, the
absence cases of a `sources` row, the column alignment, and the verdict line. Each copy drifts the
next time a reason or a row changes here.

### Proposal: `clara.explorer.artifacts.status-report`, bb-loaded

The script becomes a thin caller of this namespace. A host requires the same namespace.

- **`reason-summary`**, a multimethod on `:check`. The library defines a method for each of its
  checks. A host adds a method for each of its own. `:default` prints the reason as data.
  `:unit-stale` / `:unit-unknown` recurse over the nested reasons and leave out the informational
  ones.

  ```clojure
  (defmethod report/reason-summary :config-slice
    [{:keys [was now]}]
    (format "config-slice %s -> %s" was now))
  ```

- **`->source-rows`**: a composed report's `:sources` as `[unit-key composition unit]` cells,
  absences included (`unit missing`, `unit not checked (composition cycle)`,
  `unit report missing`).
- **`->report-fields`**: the whole report as ordered `[label value]` pairs: `unit`, `kind`,
  `source`, `compared`, `policy`, `sources` (one pair per row, label only on the first), `verdict`,
  `notes`. A host splices in fields of its own, for example a `coords` line after `kind`, with
  `insert-fields-after`.
- **`print-fields!`** prints the pairs with an aligned label column, and aligns the `sources`
  cells as columns.
- **A `notes` line.** Informational reasons move from the verdict line to `notes`.
  `checkout-dirty` affects nothing, so it doesn't belong beside the reasons that decided the
  verdict.
- **A composed verdict line counts its reasons by kind:** `stale | unit-stale 2, source-sha-drift
  1`. The `sources` rows already name each source and what moved. Listing them again makes the
  verdict line unreadable once unit keys carry several variant axes.
- **`parse-source-checkouts`**: `--source-checkout` values to `{repo path}`. It throws a marked
  usage error (§3) on a value without `=`, on an empty side, and on a repo given twice. Any command
  line parser can call it.

A host's command line then reads:

```clojure
(let [result (status/unit-status {:dir dir
                                  :source-checkouts (report/parse-source-checkouts values)
                                  :source-status-fn host-report})]
  (-> (report/->report-fields result)
      (report/insert-fields-after "kind" [["coords" (coords-str result)]])
      report/print-fields!))
```

## 5. What a host keeps

- Where a source's checkout is when the caller named none.
- Its own source unit report: the library's report on that unit, plus the host's inputs.
- A `reason-summary` method for each of its own checks.
- Fields of its own in the printed report.

## 6. Where it lands

- `clara.explorer.artifacts.status`:
  - compositions walked by the library, with the host function called for source units only (§1);
  - `:checkout` in the function's argument map, and the "not both" throw removed (§2);
  - the marked usage errors, `:ref` on a composed unit, and the no-sources message (§3).
- `clara.explorer.artifacts.status-report`, new and bb-loaded (§4). Add it to the bb smoke test's
  namespace list.
- `explorer/bin/inspect-artifacts.bb`: its `status` subcommand calls `status-report`. The
  reason, row and checkout-parsing code there is deleted.
- `explorer/docs/persisted-artifacts.md`: the narrowed `:source-status-fn` contract, `:checkout` in
  its arguments, the usage marker, and `status-report` as the way a host renders.
- Tests:
  1. a cycle through two compositions with a host function → `:source-cycle`, no overflow
  2. the host function is never called for a composition source
  3. the host function receives `:checkout` from `:source-checkouts`, and nil for a repo without
     an entry
  4. `:source-checkouts` with `:source-status-fn` is accepted
  5. every argument guard carries the usage marker; `:ref` on a composed unit throws
  6. a host `reason-summary` method is used for its check, nested under `:unit-stale` too
  7. `->source-rows` for each absence case; `->report-fields` order; `insert-fields-after`
  8. the verdict line counts by kind for a composed unit; informational reasons go to `notes`
  9. `parse-source-checkouts`: well-formed, no `=`, either side empty, a repo twice
