# Analyze tool — action items

Design plan for making `clara.server.tools.graph.analyze` correctly handle
"real world" rule namespaces such as
`gateless-product-ruleset.loan-data.income`, whose rules use keyword-typed
facts built via `facts.model.core/->fact` and custom def-macros
(`rules.data/defn`, `rules.core/derive+`, `rules.data/defdata`).

Source under discussion: `src/clara/server/tools/graph/analyze.clj`.

---

### Status update (2026-07-11)

The analysis pipeline has been substantially refactored. The hand-rolled form parser
(`extract-constructors-from-form`) and string-based LHS sanitization have been replaced
with a clj-kondo-data-driven approach:

- **Type detection**: Record constructors (`map->X`, `->X`) are detected from clj-kondo's
  `:var-usages` analysis — no form parsing, no source reading.
- **LHS stripping**: Handled at the kondo hook level via our override hook
  (`hooks/strip_lhs.clj_kondo`), which emits only the RHS body for analysis.
- **Java constructors**: Deferred to dynamic types (clj-kondo does not expose them as
  structured data).
- **Config composition**: Our hook override lives alongside synced clara-rules imports.
  See `analyze.clj` namespace docstring for custom config guidance.

## Background — what we observed

Running the existing pipeline on `income.clj` (via the live nREPL with the real
classpath) produced:

- `:var-definitions` = **0**
- `:var-usages` with a non-nil `:from-var` = **0**
- `:to :clj-kondo/unknown-namespace` = **628 / 1122 (56%)**

Root cause is **not** anything special about the source file. clj-kondo only
treats a form as a var *definition* when it has a hook or `:lint-as` mapping for
the defining macro. Minimal probes (config-independent) confirmed:

| snippet | `:var-definitions` |
|---|---|
| `clojure.core/defn` | `[foo]` ✅ |
| `clara.rules/defrule` (no hook active) | `[]` ❌ |
| `rules.data/defn` (shadows core) | `[]` ❌ |

With no var-definitions, no usage gets a `:from-var`; `build-graph` /
`build-constructors` skip every usage where `from-var` is nil
(`analyze.clj:60-80`), so the call graph is empty and nothing is traceable. The
DSL tokens (`<-`, `=>`, `?vars`, constraint destructuring names) become
unresolved symbols → the large `:clj-kondo/unknown-namespace` count.

### Why the existing tests pass but `income.clj` did not

The `analyze_test_rules` tests pass only because they happen to run where
clj-kondo resolves the explorer's `.clj-kondo` (which imports the clara-rules
hook). `kondo/run!` resolves the config dir by walking up from the (absolutized)
`:filename`, which eventually reaches `<cwd>/.clj-kondo`
(clj-kondo `impl/core.clj` `config-dir`). That makes hook loading **cwd
dependent** — it silently broke when analysis ran from a different working
directory. This is the core fragility Action Item 1 removes.

### Proven fixes (validated live in the nREPL, not yet committed)

1. Passing `:config-dir` pointed at the clara import took `income.clj` from
   **0 → 19** var-definitions (every rule) and ~**81%** `:from-var` coverage.
2. After that, every rule still landed on `:clara-rules/insert-types
   [facts.model.core.fact]` because `->fact` matches the `->Name` constructor
   naming rule. Excluding non-resolvable constructors flips all 19 rules to the
   intended shape, e.g.:

   ```clojure
   loan-data-rule => {:clara-rules/insert-types []
                      :clara-rules/dynamic-insert-types-detected
                      {:callsites [{:source-str "(->fact :gateless/loan-data {...})" ...}]}}
   ```

The scope of *this* pass is to make keyword-fact rules reach
`:clara-rules/dynamic-insert-types-detected`. Actually resolving keyword fact
types (extracting the first arg of `->fact`) is deferred (see Item 5).

---

## Run models (see `../docs/rule-annotations.md`)

The two intended standalone run models both make a cwd/`.clj-kondo` dependency
unacceptable and constrain constructor detection:

1. **CLI (primary):** `clojure -M -m clara.server.graph.main -g <files>`
   (`src/clara/server/graph/main.clj`, `-g` flag → `generate-annotations-from-paths`).
   This is a **purely static** path: clj-kondo lints the given source paths; the
   target namespaces are **not required/loaded**, so record/`deftype` classes are
   generally **not on the runtime classpath**. It is also launched from an
   arbitrary working directory.

2. **REPL injection:** the explorer server lib is added onto a host project's JVM
   via `add-libs` / `:local/root`, then `analyze-session-rules` +
   `generate-annotations-from-analysis` run against an in-memory session. Here the
   record classes **are** loaded, but the JVM cwd is the **host project** (this is
   exactly the environment in which we reproduced the 0-var-definition failure).

Implications:
- Item 1: the clara hooks must be shipped **inside the explorer lib as classpath
  resources** and made available via an explicit `:config-dir` — the only thing
  guaranteed present under both models.
- Item 2: constructor detection cannot rely on `Class/forName` alone (fails in
  the static CLI model), nor on naming heuristics — it needs the static
  clj-kondo signal, with runtime resolution as a fallback.

---

## Action Item 1 — Load the clara-rules clj-kondo hooks from the runtime classpath  ✅ DONE

**Goal:** the analyzer expands `clara.rules/defrule` / `defquery` etc. regardless
of the JVM working directory, so it runs as a standalone tool under both run
models above (CLI from a foreign dir; `add-libs` into a host JVM).

**Why cwd/`.clj-kondo` walking is not acceptable:** it only works when the
process is launched from a directory whose `.clj-kondo` tree happens to contain
the clara import. A standalone tool cannot depend on that.

**Key fact about the import config:** `.clj-kondo/imports/clara/rules/` is **not**
a hand-written or manually copy/pasted file. clj-kondo *generates* it from the
clara-rules dependency's **exported** clj-kondo config, via its
`--copy-configs --dependencies` mechanism. So the source of truth is the
dependency; our job is only to mirror that generated tree onto the classpath
resources path the analyzer reads at runtime — and to detect when it drifts.

### What was implemented

1. **Bundled resources.** The generated import is mirrored under the explorer's
   classpath resources (`resources/` is already on `:paths`):

   ```
   resources/clara/server/tools/graph/kondo-config/
     config.edn                                    ; top-level (minimal "{}")
     imports/clara/rules/config.edn                ; :lint-as + :hooks map
     imports/clara/rules/hooks/clara_rules.clj_kondo
     manifest.edn                                  ; {:files [...]} for jar-safe materialization
   ```

2. **Maintenance helper** `clara.server.tools.graph.kondo-config-sync`
   (`dev/…/kondo_config_sync.clj`, alias `:sync-kondo-config`):
   - `sync!` — mirror `.clj-kondo/imports/clara/rules/**` → the resources path,
     write the top-level `config.edn` and regenerate `manifest.edn`.
   - `check` — compare the resources against `.clj-kondo/imports` and fail
     (non-zero exit) on drift, so CI can gate staleness.

   ```bash
   # Refresh .clj-kondo/imports from deps first (clj-kondo does the real copy):
   clojure -M:lint --copy-configs --dependencies --lint "$(clojure -Spath)"
   # Then mirror onto bundled resources:
   clojure -X:sync-kondo-config
   # Verify not stale (CI):
   clojure -X:sync-kondo-config clara.server.tools.graph.kondo-config-sync/check
   ```

3. **Runtime materialization** (`analyze.clj`). Resources can live inside a jar,
   so `:config-dir` can't point at them directly. `materialize-bundled-kondo-config!`
   reads `manifest.edn` and copies each listed file into a fresh temp dir; the
   result is cached in a `defonce` `delay` (`bundled-kondo-config-dir`), built
   once per process. clj-kondo's explicit `:config-dir` is used verbatim (no
   `.clj-kondo` name / walk-up required), and configs under
   `<dir>/imports/**/**/config.edn` auto-merge.

4. **`:config-dir` threaded** through the API, defaulting to the bundled dir;
   pass `:config-dir nil` to disable (falls back to cwd `.clj-kondo` discovery):
   - `analyze-source-code` / `analyze-ns-source` / `analyze-ns-string` — add
     `:config-dir` to the `kondo/run!` opts (via `cond->`).
   - `build-analysis-from-namespaces` — accepts `:config-dir`, captures it in the
     per-ns analyze thunks.
   - `analyze-session-rules` — accepts + forwards.
   - `generate-annotations-from-paths` — adds `:config-dir` to its `kondo/run!`.

   Inline `:config {:analysis {...}}` still merges on top of the config-dir
   config (command-line config has highest precedence), so analysis buckets stay
   enabled.

### Validation (live nREPL, explorer on `:local/root`)

- `gateless-product-ruleset.loan-data.income`: **0 → 19** var-definitions,
  `:from-var` coverage **0% → 81%**, unknown-ns usages **628 → 25**, with hooks
  loaded from the bundled resources (no cwd `.clj-kondo`).
- `analyze_test_rules.clj` unchanged: `rule-record-constructor` →
  `LocalDummyRecord`, `rule-java-constructor-dot` → `DocumentCheck`,
  `rule-metadata-map-fact` → `[]`. No regression.
- `clojure -X:sync-kondo-config …/check` reports "up to date".

**Caveats / notes:**
- The hook namespace is `hooks.clara-rules` and the file uses the `.clj_kondo`
  extension — the exact `imports/clara/rules/hooks/` layout is preserved by the
  sync helper; do not flatten it or clj-kondo will not find the hook.
- The clara import currently is just `config.edn` + one hook file, but the sync
  helper mirrors the whole subtree (and the manifest enumerates every file), so
  additional hook files added upstream are picked up automatically on re-sync.

---

## Action Item 2 — Resolution-based constructor detection  ✅ DONE (superseded)

**Original problem:** `constructor->fact-type` treated any `->name` / `map->name`
as a record constructor.

**Resolution (2026-07-11):** Constructor detection is now driven entirely by
clj-kondo's `:var-usages` analysis. For each var that is on a call-graph path
under an insert/retract call, we collect `map->X` / `->X` var-usages and resolve
them at runtime via `resolve-record-type`. This resolves true record types while
rejecting non-constructor functions like `->fact`. Java constructors
(`ClassName.`, `new`, `Class/new`) are deferred to the dynamic fallback.

The old `record-constructor-index`, `record-class-index`, and
`extract-constructors-from-form` have been removed in favor of this simpler,
clj-kondo-native approach.

---

## Action Item 3 — Persist a diagnostic and fail loud on non-expansion

2. Precompute a `record-constructor-index` from the merged analysis:
`extract-constructor-types-from-reachable` (via clj-kondo var-usages) and
`resolve-record-type`.

---

## Action Item 3 — Persist a diagnostic and fail loud on non-expansion

**Problem:** when hooks are missing, the pipeline silently returns `{}` / empty
annotations instead of signaling that clj-kondo never expanded the def-macros.

**Design:** add `diagnose-rule-ns-analysis` (prototyped in the nREPL) to
`analyze.clj` and surface it. It reports, from a merged analysis:
- `:var-definitions` count and `:from-var` coverage,
- `:unresolved-ratio` (`:clj-kondo/unknown-namespace` share),
- `:suspected-unexpanded-def-macros` — def-macro names seen as *usages* (the
  direct "missing hook / lint-as" signal),
- a `:verdict`, e.g. `:NO-VAR-DEFS--def-macros-not-expanded-by-clj-kondo`.

Wire a guard into `generate-annotations-from-analysis` (or the session/paths
entry points) that raises/logs the verdict when `:var-definitions` is 0 while
rules were expected, so this failure mode is never silent again.

---

## Action Item 4 — Fix `::`-keyword rendering in dynamic callsites

**Problem:** `extract-insert-types` uses `read-string` for dynamic annotation
extraction, which resolves auto-resolved keywords (`::foo`) against the
current `*ns*` at analysis time.

**Design:** avoid `read-string` for splitting insert args. Either bind `*ns*` to
the callsite's `:ns-name-sym` while reading, or (preferred) split the argument
forms with `rewrite-clj`/clj-kondo node APIs so the original source text is
preserved verbatim. Low priority (cosmetic for the dynamic bucket) but affects
the fidelity of `:source-str`.

---

## Action Item 5 — Known limitations (out of scope this pass)

1. **Keyword fact-type extraction.** To populate real types for keyword facts,
   extract the first argument of `->fact` (e.g. `:gateless/loan-data`, or the
   vector form `[:gateless/loan-passive-income-data :report-data]`) instead of
   record/class detection. Deferred by request; dynamic detection is the target
   for now.
2. **Helper-delegated inserts via custom def-macros.** Functions defined with
   `rules.data/defn` are not recognized as var-definitions, so a rule that
   delegates its `insert!` to such a helper will not be traced. income.clj's
   rules insert directly in the RHS, so dynamic detection works, but this is a
   gap for helper-delegated inserts. Fully closing it needs `:lint-as`/hooks for
   the gateless macros, which is target-project-specific config and out of scope
   for the explorer tool.

---

## Verification plan

- Re-run the live checks used during investigation against
  `gateless-product-ruleset.loan-data.income` (nREPL on the real classpath):
  expect 19 var-definitions, high `:from-var` coverage, and all 19 rules with
  `:clara-rules/insert-types []` + `:clara-rules/dynamic-insert-types-detected`.
- Add an explorer regression test that runs the analyzer with the bundled
  config-dir default from an arbitrary working directory (assert it does NOT
  depend on cwd).
- Keep the existing `analyze_test_rules` record-based assertions green
  (resolution-based detection must still identify `LocalDummyRecord`,
  `DocumentCheck`, etc.).
- Add a unit assertion that `->fact` (lowercase, unresolvable) is NOT treated as
  a constructor.
