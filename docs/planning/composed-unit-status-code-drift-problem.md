# A composed unit's status never compares code

Status: **implemented** — see
[`composed-unit-status-code-drift-plan.md`](composed-unit-status-code-drift-plan.md).

## 1. Summary

`clara.explorer.artifacts.status/unit-status` reports a composed unit `:current` once each source
unit's manifest still holds the sha that the composition recorded. It never compares any source
against its code. So when a source repo has moved and nobody regenerated its unit, the composition
still reads `:current`.

That contradicts how the same function treats a source unit. Called without a checkout, a source
unit reports `:sha-not-compared` and its verdict is `:unknown`. A composed unit makes the same
omission for every source, says nothing, and reports `:current`.

Reproduction: one source unit, plus one composition of it, both recording sha `aaa`:

```clojure
(s/unit-status {:dir "<root>/repo-a"})
;; => {:verdict :unknown, :reasons [{:check :sha-not-compared}]}

(s/unit-status {:dir "<root>/_compose/x" :root "<root>"})
;; => {:verdict :current, :reasons [],
;;     :sources [{:source "repo-a", :recorded "aaa", :current "aaa", :verdict :current}]}
```

A host that relies on the composed verdict reports a stale composition as current. The report
gives no sign that the code was never looked at.

## 2. Three records, two links

A composition depends on three records. Each is written at a different time:

```
  the checkout                 the source unit                    the composed unit
  (git HEAD of the repo)       (<root>/repo-a/ or _variants/…)    (<root>/_compose/<key>/)

        │   analysis of one repo         │   compose of N source units    │
        └────────────────────────────────►└───────────────────────────────►┘

        ◄──────────── link A ───────────►◄─────────── link B ────────────►
        is the source unit current        is the composition current
        with its code?                    with its source units?
```

- **The checkout** is the code: the sha a repo's working copy is at now.
- **The source unit manifest** is the receipt from the last analysis of that repo. Its
  `:source :sha` is the commit analyzed.
- **The composed unit manifest** is the receipt from the compose. Its `:staleness :sources` maps
  each source unit's key to the `:sha` that unit held when composed.

A composition is current only if both links hold for every source. Today `unit-status` checks link
A for a source unit when given `:checkout`. For a composed unit it checks only link B.
`:checkout` is rejected for it, and nothing else can supply link A.

How the links break one at a time:

| step | event | HEAD | source unit `:source :sha` | composition records | link A | link B | verdict today |
|---|---|---|---|---|---|---|---|
| 1 | analyze, then compose | `aaa` | `aaa` | `aaa` | ok | ok | `:current` |
| 2 | a commit lands, nothing regenerated | `bbb` | `aaa` | `aaa` | broken | ok | `:current` |
| 3 | the source unit regenerated | `bbb` | `bbb` | `aaa` | ok | broken | `:stale` |
| 4 | recompose | `bbb` | `bbb` | `bbb` | ok | ok | `:current` |

The verdict at step 2 is wrong. Step 2 can last as long as nobody regenerates the source unit.

## 3. Naming that adds to the confusion

- In `:sources`, `:current` is the sha the source unit's manifest holds. It is not the sha of any
  code. It reads as "what the code is now".
- A per-source `:verdict` is `:current`, `:sha-drift`, or `:missing`. The top-level verdict is
  `:current`, `:stale`, or `:unknown`. One word, two vocabularies.
- The per-source key `:source` names a unit key. The manifest's `:source` is a git identity.
- `persisted-artifacts.md` says `:staleness` lets a reader answer "is this current?" for "a
  composition that is stale as soon as any of its N independently-moving sources has moved".
  "Sources" there means source units, but it reads as the repos.

## 4. Proposal

A composed unit's status is link B for each source, plus that source unit's own status report:
the same report `unit-status` returns when called on the source unit's dir. The caller decides how
that per-source report is produced, by giving checkouts or by giving a function.

### 4.1 What the caller supplies

Where a reader's checkouts live is the reader's own business (see the
`clara.explorer.artifacts.manifest` docstring). So the explorer asks for them and never looks
for them. `unit-status` on a composed unit takes at most one of:

- **`:source-checkouts`**, a map of repo name to checkout path. Each source unit is checked with
  `unit-status` against the checkout for its repo. A source whose repo has no entry gets
  `:sha-not-compared`, exactly as a source unit without `:checkout` does. Two sources of one repo
  share its checkout. The one on another ref then reads `:unknown` (§4.4).
- **`:source-status-fn`**, a function called once per source unit. It returns that unit's whole
  status report. A host uses this when its own report for a source unit carries more than the
  explorer's checks, for example inputs the host persists in `:blocks`.

Giving both throws. `:source-checkouts` is shorthand for the default `:source-status-fn`:

```clojure
(fn [{:keys [repo dir]}]
  (unit-status (cond-> {:dir dir}
                 (get source-checkouts repo) (assoc :checkout (get source-checkouts repo)))))
```

#### Worked example: a composition of two units

The registry, rooted at `/reg`:

```
/reg/repo-a/                                  source unit, mainline       analyzed aaa1111
/reg/_variants/repo-b/env=dev/ref=main/       source unit, variant        analyzed ccc3333
/reg/_compose/pair/                           composed from both          recorded aaa1111, ccc3333
```

The composed manifest's `:staleness`:

```clojure
{:policy "review-when-any-source-sha-drifts"
 :sources {"repo-a"                  {:sha "aaa1111…" :created "2026-10-30"}
           "repo-b@env=dev/ref=main" {:sha "ccc3333…" :created "2026-10-30"
                                      :variant [[:env "dev"] [:ref "main"]]}}}
```

The reader's checkouts:

```
/src/repo-a     on main, HEAD bbb2222     moved since repo-a's unit was analyzed
/src/repo-b     on main, HEAD ccc3333     unchanged
```

**API, with `:source-checkouts`:**

```clojure
(status/unit-status {:dir "/reg/_compose/pair"
                     :source-checkouts {"repo-a" "/src/repo-a"
                                        "repo-b" "/src/repo-b"}})
```

**API, with a host function.** The function receives one map per source, built from the
composed manifest:

```clojure
(status/unit-status {:dir "/reg/_compose/pair"
                     :source-status-fn (fn [{:keys [dir]}] (host/unit-status {:dir dir}))})

;; called twice:
{:unit-key "repo-a"
 :repo     "repo-a"
 :dir      "/reg/repo-a"}

{:unit-key "repo-b@env=dev/ref=main"
 :repo     "repo-b"
 :variant  [[:env "dev"] [:ref "main"]]
 :dir      "/reg/_variants/repo-b/env=dev/ref=main"}
```

Either form returns, for those two calls:

```clojure
;; repo-a
{:dir "/reg/repo-a" :repo "repo-a" :kind :source
 :source {:sha "aaa1111…" :branch "main" :working-tree "clean" …}
 :updated "2026-10-30"
 :compared {:checkout "/src/repo-a" :ref "HEAD" :sha "bbb2222…"}
 :verdict :stale
 :reasons [{:check :sha-drift :recorded "aaa1111…" :current "bbb2222…"}]}

;; repo-b
{:dir "/reg/_variants/repo-b/env=dev/ref=main" :repo "repo-b"
 :variant [[:env "dev"] [:ref "main"]] :kind :source
 :source {:sha "ccc3333…" :branch "main" :working-tree "clean" …}
 :updated "2026-10-30"
 :compared {:checkout "/src/repo-b" :ref "HEAD" :sha "ccc3333…"}
 :verdict :current
 :reasons []}
```

**CLI.** `--source-checkout <repo>=<path>` fills `:source-checkouts`. It is repeatable, once per
repo, and applies only to a composed unit. `--checkout PATH` is unchanged: it applies only to a
source unit.

```
$ bb explorer/bin/inspect-artifacts.bb /reg/_compose/pair status \
    --source-checkout repo-a=/src/repo-a \
    --source-checkout repo-b=/src/repo-b
unit       _compose/pair
kind       aggregate unit (mode :compose)
source     d4e5f6a (main, clean)   updated 2026-10-30
policy     review-when-any-source-sha-drifts
sources    repo-a                    composition current   unit stale (sha-drift aaa1111 -> bbb2222)
           repo-b@env=dev/ref=main   composition current   unit current
verdict    stale | repo-a: unit sha-drift aaa1111 -> bbb2222
```

The same command without `--source-checkout`:

```
sources    repo-a                    composition current   unit unknown (sha not compared)
           repo-b@env=dev/ref=main   composition current   unit unknown (sha not compared)
verdict    unknown | repo-a: unit sha not compared; repo-b@env=dev/ref=main: unit sha not compared
```

`--edn` prints the result map. §4.3 gives its shape.

### 4.2 When the per-source report is produced

- The function is called once for each source whose unit dir holds a manifest. Link B is checked
  for every source, whatever the function returns.
- A source whose unit is gone fails link B with `:source-missing`. The function is not called,
  since there is no unit to check.
- A source that is itself a composition gets a composed report. The default function passes the
  same `:source-checkouts` down, so the check recurses.

### 4.3 The result: one vocabulary, fields named for what they judge

Every `:verdict`, at every level, is `:current`, `:stale`, or `:unknown`. The kind of drift goes
in `:reasons`. Each `:sources` entry names the two records it judges:

- `:composition`: does the composition's record still match this unit? (link B)
- `:unit-status`: this unit's own status report, as `:source-status-fn` returned it (link A, plus
  whatever a host adds)

The name is `:unit-status` and not `:source-unit`, because a source can be a composition (§4.2),
and "source unit" means a unit without `:mode`.

The `--edn` result for the worked example:

```clojure
{:dir "/reg/_compose/pair"
 :repo "_compose/pair"
 :kind :aggregate
 :mode :compose
 :source {:sha "d4e5f6a…" :branch "main" :working-tree "clean" …}
 :updated "2026-10-30"
 :staleness {:policy "review-when-any-source-sha-drifts" :sources {…}}
 :verdict :stale
 :reasons [{:check :unit-stale
            :unit-key "repo-a"
            :reasons [{:check :sha-drift :recorded "aaa1111…" :current "bbb2222…"}]}]
 :sources [{:unit-key    "repo-a"
            :composition {:verdict :current :recorded "aaa1111…" :unit-sha "aaa1111…"}
            :unit-status {…the repo-a report from §4.1…}
            :verdict     :stale}
           {:unit-key    "repo-b@env=dev/ref=main"
            :composition {:verdict :current :recorded "ccc3333…" :unit-sha "ccc3333…"}
            :unit-status {…the repo-b report from §4.1…}
            :verdict     :current}]}
```

- **`:composition`:** `:recorded` is the sha the composition recorded. `:unit-sha` is the sha the
  unit's manifest holds now.
- **A missing unit:** `:composition` is `{:verdict :stale :recorded "…" :unit-sha nil}`, and
  `:unit-status` is absent.
- **Precedence** for a source's `:verdict` and for the top-level verdict is the one `->verdict`
  already applies: `:stale` over `:unknown` over `:current`.
- **Top-level `:reasons`:** `:source-sha-drift` and `:source-missing` stay for link B, keyed by
  `:unit-key`. Two are added for the per-source report, each nesting that report's own reasons:

  ```clojure
  {:check :unit-stale   :unit-key "repo-a"   :reasons [{:check :sha-drift …}]}         ; stale
  {:check :unit-unknown :unit-key "repo-b@…" :reasons [{:check :sha-not-compared}]}   ; unknown
  ```

### 4.4 A checkout on another ref says nothing about the unit

Today a source unit's check compares only shas. A unit analyzed on `main`, checked against a
checkout on `feature-x`, reports `:sha-drift` and reads `:stale`. But that checkout can't tell
whether `main` moved.

When the caller gives no `:ref`, compare the checkout's branch with the unit's recorded
`:source :branch`. When they differ, report

```clojure
{:check :checkout-off-ref :recorded "main" :current "feature-x"}
```

and skip the sha comparison. `:checkout-off-ref` joins the unknown checks, so the verdict is
`:unknown`. An explicit `:ref` (`--ref`) names the commit to compare and skips the guard.

This applies to a source unit checked on its own as well as to each source of a composition.

### 4.5 Behavior change

A composed unit checked without checkouts goes from `:current` to `:unknown`. That is the fix, not
a side effect: the old verdict claimed a comparison that never ran.

## 5. Where it lands

- `clara.explorer.artifacts.status`:
  - `:source-checkouts` and `:source-status-fn` on a composed unit, and the per-source call;
  - the `:sources` shape and the `:unit-stale` / `:unit-unknown` reasons;
  - `:checkout-off-ref` in the source-unit check.

  Update the namespace and `unit-status` docstrings.
- `explorer/bin/inspect-artifacts.bb`:
  - `--source-checkout <repo>=<path>`, repeatable (`babashka.cli` `:coerce [:string]`), rejected
    on a source unit, as `--checkout` stays rejected on a composed unit;
  - the usage rows;
  - the `sources` lines;
  - the summaries for the new reasons.
- `explorer/docs/persisted-artifacts.md`: §"Checking a unit: `status`" and the `:staleness`
  paragraph, using the two-link wording.
- `explorer/test/clara/explorer/artifacts/status_test.clj`, one case each:
  1. both links hold, no checkouts → `:unknown`, `:unit-unknown` per source
  2. link B holds, unit behind its checkout → `:stale`, `:unit-stale`
  3. link B drifted, unit current with its checkout → `:stale`, `:source-sha-drift`
  4. source unit missing → `:stale`, `:source-missing`, no `:unit-status`, function not called
  5. `:source-checkouts` covers one source of two → `:unknown`
  6. `:source-status-fn` returning `:stale` for a unit whose sha matches → that source and the
     composition are `:stale`
  7. `:source-checkouts` and `:source-status-fn` together → throws
  8. checkout on another branch, no `:ref` → `:checkout-off-ref`, `:unknown`; with `:ref` → sha
     compared
  9. a composition as a source → its report nests under `:unit-status`

## 6. What stays with the host

- **Which checkout belongs to which source:** the host's conventions for where repos live. The
  host passes them in as `:source-checkouts`, or applies them inside `:source-status-fn`.
- **Inputs only the host persists**, such as configuration slices and tool versions. They go in
  the report the host's `:source-status-fn` returns, with the verdict the host gives them.
- Its own rendering and any planning built on these verdicts.
