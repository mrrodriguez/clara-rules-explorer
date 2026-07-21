# Plan: Runtime Session-Based Rule Analysis

**Status:** Plan (exploration complete, implementation not started)
**Author:** AI-assisted exploration, verified against a live nREPL session
**Date:** 2026-02-15

---

## 1. Problem Statement

`clara.server.tools.graph.analyze` currently performs **pure static analysis**: it runs
clj-kondo over namespace *source files* found on the classpath and relies on a custom
clj-kondo `analyze-call` hook (`strip_lhs.clj_kondo`) to make `clara.rules/defrule`
comprehensible — stripping the LHS and analyzing only the RHS for `insert!`/`retract!`
call graphs.

This breaks down when rules are **emitted by custom macros**. Demonstrated by
`clara.server.tools.graph.rules.helpers/def-fact-fn`, which expands to a `defn` plus a
`r/defrule`. Without a bespoke clj-kondo hook for `def-fact-fn`, the emitted rule
`extract-doc-meta-rule` is invisible to the analyzer:

```clojure
;; Current session-based analysis of the demo session (verified in REPL):
(count (analyze/generate-annotations-from-analysis {:analysis <session-analysis>}))
;; => 13 — extract-doc-meta-rule is MISSING
```

The historical motivation for file-based analysis (a Java `-main` taking file paths)
is no longer the intended use case. The intended entry point is a **live Clara session**:
the rulebase already contains every rule as *data* (`:lhs`, `:rhs`, `:props`, `:ns-name`),
fully macro-expanded by the real Clojure compiler — no clj-kondo macro hooks required
to find rules.

### Goals

1. The **session is the source of truth for rules**. Rule LHS/RHS come from rulebase
   productions, never from clj-kondo macro interpretation.
2. clj-kondo is still used for what it is good at: **var-level call-graph analysis**
   (helper fns, record constructors) — fed with synthesized sources built from the
   live runtime.
3. **Runtime callsite resolution**: resolve dynamic `insert!`/`retract!` argument forms
   against the live classpath (vars, classes, metadata), so macro-generated RHS forms
   (with gensym'd locals) resolve accurately.
4. A caller-supplied **`:callsite-resolver-fn`** for callsites that cannot be resolved
   automatically.
5. Session-less analysis from files/namespaces is **out of scope** (a future `-main`
   may load a session from a caller-supplied fn on the classpath).

### Explicitly out of scope (parallel in-flux work)

Do **not** modify or depend on:

- `clara.server.graph.api/enriched-annotations`
- `clara.server.tools.graph.analyze/enrich-annotations-from-session`
- `clara.server.tools.graph.analyze/add-auto-detected-annotations`

These derive annotations from working-memory fact instances and are changing
independently. They do not call the functions being reworked here
(`enrich-annotations-from-session` uses `memory/session-snapshot` +
`ann/resolve-annotations` only), so the blast radius is contained.

---

## 2. Verified Facts (exploration evidence)

All facts below were verified against the running REPL (`clj-nrepl-eval`, port 56014)
and by reading the clara-rules and clj-kondo sources directly.

### 2.1 Production structure (clara-rules)

Source: `clara/rules/dsl.clj` (`parse-rule*`, ~line 257; `split-lhs-rhs`, ~line 49).

- Production maps have keys `:ns-name :lhs :rhs :name :handler`, plus optional
  `:props`, `:doc`, `:params` (queries), `:env` (rare, REPL-captured env).
- `:rhs` is `(list 'quote (vary-meta rhs assoc :file *file*))` — **raw RHS forms as
  data**, with `{:file ...}` metadata. For plain `defrule` the data is the original
  source form (with namespace aliases like `r/insert!` intact). For macro-emitted rules
  the data is whatever the macro emitted (e.g. syntax-quoted, fully-qualified,
  gensym'd locals):

  ```clojure
  ;; extract-doc-meta-rule (emitted by def-fact-fn syntax-quote):
  :rhs (do (clojure.core/let [resolved__49581__auto__ (var extract-doc-meta)]
            (clara.rules/insert! resolved__49581__auto__)))

  ;; collect-doc-meta (plain defrule):
  :rhs (do (let [doc-metas (mapv ?extract-doc-meta ?docs)]
             (r/insert! (laf/map->AllGivenDocumentsMeta {...}))))
  ```

- Queries have **no `:ns-name` and no `:rhs`**; their `:name` still carries the
  namespace prefix as a string.
- `pr-str` of `:rhs` data is re-readable in tested cases (`#'x` prints as `(var x)`).
- Niche path: `clara.rules.dsl/build-rule-action` produces productions whose `:rhs` is
  **not quoted** (may embed live objects). Edge case, see §9.
- Default `fact-type-fn` is `clojure.core/type` (`compiler.clj` ~line 2100), which
  returns `(:type (meta x))` when present. Verified:
  `(:type (meta #'extract-doc-meta)) ; => :extract-doc-meta` — this is how the
  var-as-fact pattern gets its fact type at runtime.

### 2.2 clj-kondo behavior

Source: `clj_kondo/impl/analyzer.clj` (`analyze-call`, ~line 3095).

- **Unhooked macros**: catch-all branch calls `(analyze-children ...)` — argument forms
  are analyzed as expressions, but at top level there is no `:from-var`, so they never
  enter our call graph. **The `defn` emitted by `def-fact-fn` is invisible** (the
  `analyze-def-catch-all` heuristic exists only for `clj-kondo.lint-as/def-catch-all`,
  not for arbitrary `def*` macros).
- **Synthesized sources resolve correctly** (verified): feeding
  `real-ns-source + "\n" + "(def extract-doc-meta-rule (fn [] <pr-str-rhs>))"` through
  `kondo/run!` with `:lint ["-"]` + `:filename` produces:
  - a `:var-definitions` entry for `extract-doc-meta-rule`;
  - `:var-usages` with `:from-var extract-doc-meta-rule :to clara.rules :name insert!`
    — the boundary call is correctly attributed to the macro-emitted rule.
- A **"drop" hook** for `clara.rules/defrule`/`defquery` (emitting `(quote <name>)`)
  eliminates all source-side rule analysis: no var-definition, no var-usages, no
  duplicates with the synthesized snippet defs (verified: zero duplicate def-names).
- **Reconstructed ns forms** (built from live `(ns-aliases)` / `(ns-refers)` /
  `(ns-imports)`) resolve aliased usages (`r/insert!` → `:to clara.rules`, recorded
  with `:alias r`) but lose **same-namespace var attribution** (e.g. unqualified
  `map->AllIdCardGivenDocuments` → `:to :clj-kondo/unknown-namespace`) because the
  reconstructed source lacks the `defrecord`/`defn` forms. Acceptable as a fallback
  only; runtime resolution compensates (§4.3).
- Existing machinery tolerates the merged analysis:
  `generate-annotations-from-analysis` + `:rules-filter` + `:in-memory-sources`
  (keyed `ns-sym → combined-source-string`) works end-to-end (verified: 15 annotation
  entries for the demo session vs 13 baseline; the two additions are
  `extract-doc-meta-rule` and the `:no-output-types` marker for
  `collect-all-missing-required-docs`).

### 2.3 Target output shape already anticipated

- `serialize/serialize-dynamic-callsite` already allowlists
  `:status :resolved-types :resolution-method` alongside `:source-str :ns :filename`.
- `docs/explorer-graph-api.md` already documents the dynamic-detection object with
  `"resolution": "full"|"partial"|"none"` and per-callsite `"status"`.
- The hand-enriched `test-resources/.../loan-doc-rules-annotations.edn` and
  `core_test.clj/test-dynamic-detection-in-rules-list` demonstrate the exact target
  EDN shape (`:status :resolved`, `:resolved-types [...]`, `:resolution :full`).
  The new pipeline should generate these entries **automatically**.

---

## 3. Target Architecture

```
                 ┌────────────────────────────────────────────────┐
                 │           live session / rulebase              │
                 │  :productions [{:ns-name :lhs :rhs :props …}]  │
                 └───────┬────────────────────────┬───────────────┘
                         │                        │
              (rules as data)              (namespaces as roots)
                         │                        │
                         ▼                        ▼
        ┌─────────────────────────┐   ┌──────────────────────────────┐
        │ source synthesis        │   │ runtime RHS resolution       │
        │ per ns:                 │   │ (new ns, see §4.3)           │
        │  real source | in-mem | │   │ walk :rhs data; resolve      │
        │  reconstructed ns form  │   │ insert!/retract! arg forms   │
        │  + appended snippet:    │   │ against the live classpath   │
        │  (def rule (fn [] rhs)) │   │                              │
        └──────────┬──────────────┘   └──────────────┬───────────────┘
                   │                                 │
                   ▼                                 │
        ┌─────────────────────────┐                  │
        │ clj-kondo (bundled      │                  │
        │  drop-rule hook config, │                  │
        │  per-ns, cached, merged)│                  │
        └──────────┬──────────────┘                  │
                   │  var-usages call graph          │
                   ▼                                 ▼
        ┌────────────────────────────────────────────────┐
        │ generate-annotations-from-analysis             │
        │  - static ctor types (existing machinery)      │
        │  - dynamic callsites → runtime resolution →    │
        │    :callsite-resolver-fn → unresolved capture  │
        └────────────────────────────────────────────────┘
```

**Division of responsibility:**

| Concern | Owner |
|---|---|
| Rule set, rule names, LHS, RHS, props | **Session rulebase** (source of truth) |
| Helper-fn call graph, record-ctor tracing | **clj-kondo** on synthesized sources |
| Custom macro → *var* comprehension (e.g. the `defn` inside `def-fact-fn`) | **Caller-supplied kondo hooks** via `:config-dir` (optional) |
| `defrule`/`defquery` LHS/RHS comprehension | **Never kondo hooks** — bundled config *drops* these forms |
| Dynamic callsite arg resolution | **Runtime resolution** on form data + `:callsite-resolver-fn` |

---

## 4. Component Design

### 4.1 Bundled kondo config: replace strip-hook with drop-hook

`resources/clara/server/tools/graph/kondo-config/`:

- **New** `hooks/drop_rules.clj_kondo` (name TBD): `analyze-call` hooks for
  `clara.rules/defrule` and `clara.rules/defquery` emitting
  `(quote <production-name>)` — no var-definition, no body analysis.
  (Verified working in `server/dev/tmp-kondo-config/`.)
- **Delete** `hooks/strip_lhs.clj_kondo`.
- `config.edn` registers the drop hooks for `clara.rules/defrule` +
  `clara.rules/defquery`. Keep the synced `imports/clara/rules` (its
  `defhierarchy`, `parse-rule`, etc. hooks remain; root `config.edn` registrations
  take precedence over `imports/` for the same var — same mechanism strip-lhs used).
- Update file lists in **all three places**:
  - `manifest.edn` (`:files`)
  - `analyze.clj` `bundled-override-files`
  - `dev/clara/server/tools/graph/kondo_config_sync.clj` `override-files` (and its
    inline `config.edn` seed string at ~line 89)

Rationale to document: source-side `defrule` analysis is not merely redundant with
session snippets — it is *harmful*: duplicates pollute callsite extraction, and
LHS-internal constructors (custom accumulators) cause false insert-types. The
strip-lhs hook solved the second problem; the drop hook solves both.

**Caller configs (`:config-dir`)**: contract changes. A custom config's job is now
*only* to teach kondo about caller macros that expand to **vars** (helper fns,
record types) so the call graph sees them — e.g. a hook for `def-fact-fn` emitting
its inner `defn`. Configs must **not** register `clara.rules/defrule` hooks that
emit rule bodies (would reintroduce duplicates). This is exactly the user's
constraint: "callers can still provide clj-kondo analyzers for macros they build
that may expand to vars … but we do not rely on these kondo configs to find defrule
lhs/rhs themselves."

### 4.2 Source synthesis (session → kondo input)

New code (home TBD, see §6): for each namespace `ns-sym` that owns session rules:

1. **Base source**, first match wins:
   a. `:in-memory-sources` override (`{ns-sym source-string}`) — unchanged semantics;
   b. real source via existing `find-ns-resource` (`.clj` / `.cljc`);
   c. **reconstructed ns form** built from the live runtime ns:
      ```clojure
      (ns <ns-sym>
        (:require [clara.rules :as r] [… :as laf] … [other.ns :refer […]] …)
        (:import [pkg Class …] …))
      ```
      built from `(ns-aliases)`, `(ns-refers)` (minus `clojure.core`),
      `(ns-imports)` (minus `java.lang.*`). (Verified working; loses same-ns var
      attribution — runtime resolution compensates.)
2. **Append one snippet per rule** (productions with `:rhs`, grouped by `:ns-name`;
   queries skipped — no `:rhs`):
   ```clojure
   (def <local-rule-name> (fn [] <pr-str-of-rhs-data>))
   ```
   - Local name = last segment of the production `:name` string. (Edge: names with
     chars illegal in `def` symbols — sanitize + keep mapping, see §9.)
   - `pr-str` the `:rhs` *data* under `*print-length*/*print-level* nil`.
   - Sanitization walk: replace embedded non-readable values (anything not
     seq/map/set/vector/symbol/keyword/string/number/char/boolean/nil) with a
     placeholder symbol. Protects against `build-rule-action`-style unquoted RHS
     and macro-embedded objects. kondo parses but never evals, so placeholders are
     analysis-safe.
3. **One `kondo/run!` per namespace** over the combined string, via the existing
   `with-in-str` + `:lint ["-"]` + `:filename <ns-resource-path>` pattern
   (see `docs/analyze-clj-kondo-notes.md` — mechanics unchanged).
   - Derive `:lang` from the source extension (`.cljc` → `:cljc`); today
     `analyze-source-code` hardcodes `:clj` — fix while touching this code.
4. **Merge** per-ns analyses with the existing `merge-with into` machinery
   (`build-analysis-from-namespaces` stays as the internal engine for transitive
   dependency following; `:namespace-usages` still drive dep discovery since the
   real/reconstructed ns forms are intact).

**Caching**: `global-analysis-cache` is currently keyed by `ns-sym` only — invalid
once source content depends on the session's rules. Re-key to `[ns-sym source-hash]`
(or make the cache session-scoped). `get-or-analyze-ns-analysis` and
`clear-global-analysis-cache!` change accordingly. In-memory invalidation must be
covered by tests (two sessions over the same ns with different rules must not
collide).

### 4.3 Runtime RHS resolution (new namespace)

New namespace `clara.server.tools.graph.rhs` (name TBD). All functions operate on
**form data + the live runtime**, never on source text positions.

**`boundary-fn-sym`** — resolve a call head symbol against the live ns:
```clojure
(defn- resolve-aliased-sym [ns-obj sym]
  (if-let [ns-part (namespace sym)]
    (when-let [target (or (find-ns (symbol ns-part))              ; fully-qualified
                          (get (ns-aliases ns-obj) (symbol ns-part)))] ; alias
      (ns-resolve target (symbol (name sym))))
    (ns-resolve ns-obj sym)))
```
Matches `insert-fns`/`retract-fns` regardless of how the call site wrote it
(`r/insert!`, `clara.rules/insert!`, referred `insert!`, or a fully-qualified
`clara.rules/insert!` inside macro output). Replaces kondo's `:to` attribution for
boundary detection (both available; runtime resolution is exact).

**`rhs-callsites`** — walk `(:rhs production)` data (`tree-seq`), collecting
`let`/`clojure.core/let` binding pairs (needed for gensym locals) and every list
whose head resolves to a boundary fn. Returns per-callsite:
```clojure
{:direction :insert | :retract
 :boundary-fn clara.rules/insert!
 :arg-forms […]                 ; data
 :local-env {sym bound-form}}   ; enclosing lets (shallow, depth-limited)
```

**`resolve-fact-form`** — resolver chain per arg form, all runtime:
1. **Local symbol** → look up in `:local-env`; recurse (depth-capped ~5).
   Handles `resolved__49581__auto__` → `(var extract-doc-meta)`. **Verified.**
2. **Var reference** `(var x)` / `#'x` → `resolve-aliased-sym` →
   `(or (:type (meta v)) …)` — keyword or class/symbol type. **Verified:**
   `#'extract-doc-meta` → `:extract-doc-meta`.
3. **Record ctor** `(->X …)` / `(map->X …)` → reuse/relocate `resolve-record-type`
   logic (resolve ctor var → class name symbol), now driven by the live ns.
4. **Java ctor** `(X. …)` / `(new X)` / `(X/new …)` → resolve `X` via
   `(ns-imports)` of the callsite's live ns or `Class/forName` for FQ names →
   fq class symbol.
5. **`with-meta` literal** `(with-meta m {:type t})` with literal meta map → `t`.
   Covers metadata-map facts **when the meta map is a literal**; non-literal meta
   falls through.
6. **Helper-subgraph Java ctors** (enhancement, kondo-informed): if the form is a
   call to a var in the merged analysis and that var's reachable subgraph has
   `:java-class-usages`, resolve those class names in the helper's ns.
   Auto-resolves e.g. `build-compliance-review` → `ComplianceReview` (today a
   manual sidecar entry in the demo EDN).
7. **`:callsite-resolver-fn`** (caller hook, §4.4).
8. Unresolved → capture callsite.

**Result aggregation per rule** (matches existing serialization contract):
```clojure
{:clara-rules/insert-types […resolved types…]          ; promoted, feeds dep-graph
 :clara-rules/dynamic-insert-types-detected
 {:callsites [{:source-str "…"        ; pr-str of arg form (data-derived)
               :ns-name-sym …         ; ns where the callsite lives
               :filename "…"          ; rhs :file meta or ns resource path
               :status :resolved | :resolved-multi | :unresolved
               :resolved-types […]}]
  :resolution :full | :partial | :none}}
```

**Callsite extraction split** (important detail):
- **Rule-level callsites** (boundary fn directly in the rule's own `:rhs`) come from
  the data walk above — exact, gensym-proof.
- **Helper-level callsites** (boundary fn inside a helper fn body; rule merely calls
  the helper) are **not** in any rule's `:rhs`. Keep the existing kondo
  position-based extraction (`callsite->dynamic-entries` + `get-source`) for these —
  the helper's usages carry row/col into the combined source, and the in-memory
  source map makes extraction work. Then pass the extracted arg forms through the
  same `resolve-fact-form` chain (step 4 auto-resolves the
  `rule-helper-does-insert` / `rule-retract-helper-call` Java-ctor cases).
- Consequence: rule-level `source-str` values become `pr-str`-normalized (commas in
  maps, no line breaks) instead of verbatim source formatting. Accepted formatting
  change; tests updated accordingly. Helper-level callsites keep verbatim source
  formatting.

### 4.4 Public API surface

`clara.server.tools.graph.analyze`:

```clojure
(analyze-session-rules
  {:session-or-rulebase session      ; required
   :include-ns-prefixes [...]        ; optional dep-following filter (unchanged)
   :exclude-ns-prefixes [...]        ; optional (unchanged)
   :in-memory-sources {ns-sym src}   ; optional source override (unchanged)
   :config-dir "..."                 ; optional caller kondo hooks (new contract, §4.1)
   :cache-atom (atom {})})           ; optional (re-keyed, §4.2)
;; => merged clj-kondo analysis map (same as today)

(generate-annotations-from-analysis
  {:analysis analysis                ; required
   :session-or-rulebase session      ; NEW — enables runtime resolution;
                                     ;   derives default :rules-filter
   :rules-filter [...]               ; optional override (unchanged)
   :in-memory-sources {...}          ; optional (unchanged)
   :callsite-resolver-fn f})         ; NEW — (fn [callsite-map] -> resolution | nil)
;; => annotations map (same shape, plus :status/:resolved-types/:resolution)
```

**`:callsite-resolver-fn` contract:**

```clojure
(fn [{:keys [rule-name      ; string  — fq rule name, e.g. "my.ns/my-rule"
             ns-name-sym    ; symbol  — ns where the callsite was found (may be a helper ns)
             direction      ; :insert | :retract
             boundary-fn    ; symbol  — e.g. clara.rules/insert!
             arg-form       ; data    — the unresolved argument form
             source-str     ; string  — pr-str of arg-form
             filename       ; string  — best-known source attribution
             rule-rhs]      ; data    — full :rhs of the owning rule (context)
      :as callsite}]
  ;; Return nil when unresolved, or:
  {:resolved-types […]      ; symbols, keywords, or classes
   :status :resolved})      ; optional; defaults to :resolved when types present
```

Called only after the automatic chain (§4.3 steps 1–6) fails, once per unresolved
arg form. Errors propagate as unresolved (wrapped, logged) — a throwing resolver
must not abort the whole run.

**Removed / breaking:**

- `generate-annotations-from-paths` — **removed** (session-less analysis out of
  scope). Alternative: keep as deprecated thin wrapper? Decision point (§8) —
  recommendation: remove.
- `main.clj` `-g/--generate-annotations` flag — removed with it.
  `--generate-analysis` keeps working but **always** derives annotations from the
  loaded session (drops its `-g` variant).
- `build-analysis-from-namespaces` remains public-ish (internal engine + advanced
  use), but docs stop presenting it as a workflow.

**Unchanged:** `extract-session-rule-names`, `extract-session-namespaces`,
`ns->resource-base`, `find-ns-resource`, `generate-annotations-from-analysis`
output shape, `core/rulebase-analysis`, `annotations.clj` merging,
`serialize.clj` JSON contract.

---

## 5. Namespace-by-Namespace Changes

| File | Change |
|---|---|
| `src/clara/server/tools/graph/analyze.clj` | Rework `analyze-session-rules` (source synthesis orchestration). Add `:session-or-rulebase` + `:callsite-resolver-fn` to `generate-annotations-from-analysis`. Re-key analysis cache. Remove `generate-annotations-from-paths`. Derive `:lang` from extension. Update ns docstring (drop-hook contract). |
| **NEW** `src/clara/server/tools/graph/rhs.clj` | §4.3: boundary detection, `rhs-callsites`, `resolve-fact-form` chain, resolver-fn integration, detection-map assembly. |
| `resources/clara/server/tools/graph/kondo-config/` | Delete `hooks/strip_lhs.clj_kondo`; add drop hook; rewrite `config.edn`; update `manifest.edn`. |
| `dev/clara/server/tools/graph/kondo_config_sync.clj` | Update `override-files` + inline config seed + docstring. |
| `src/clara/server/graph/main.clj` | Remove `-g` flag + `run-generate-annotations`; `run-generate-analysis` always session-derived. |
| `src/clara/server/graph/api.clj` | **Untouched** (in-flux `enriched-annotations` lives here). |
| `src/clara/server/tools/graph/{core,annotations,serialize,memory,nodes}.clj` | **Untouched** — they consume annotations, whose shape only gains keys. |

---

## 6. Test Plan

### `test/clara/server/tools/graph/analyze_test.clj` (major rewrite)

- Replace all `generate-annotations-from-paths` fixtures with session-based ones:
  `(r/mk-session 'clara.server.tools.graph.rules.analyze-test-rules)` etc. All test
  rule namespaces are on the test classpath — sessions build directly.
- **Keep (mechanically updated):** static insert/retract types; helper tracing
  (`rule-nested-helper-call`); machinery-exclusion invariants; `rules-filter` →
  `:no-output-types`; in-memory source test (reframe as source *override* for a
  session ns).
- **Update expectations:** dynamic callsite `:source-str` values for rule-level
  callsites are now `pr-str`-normalized from RHS data (map commas, single-line);
  helper-level callsites keep source formatting. Java-ctor rules
  (`rule-java-constructor-*`, `rule-retract-java-*`) now **resolve** via the
  runtime chain (step 4) — move from "dynamic captured" to "resolved with
  `:status :resolved :resolved-types [...]` and promoted `:clara-rules/insert-types`".
- **New tests:**
  - `extract-doc-meta-rule` (macro-emitted) appears with
    `:clara-rules/insert-types [:extract-doc-meta]` + resolved callsite provenance.
  - Dep-graph linkage: `extract-doc-meta-rule` → downstream `collect-doc-meta`
    (keyword type matches LHS `:extract-doc-meta`).
  - Metadata-literal rule (`rule-metadata-map-fact`) resolves to
    `:custom-map-type`.
  - Unhooked-custom-macro invisibility: `helpers/def-fact-fn` never appears as an
    annotation key (regression guard for the old `NegationResult` false positive).
  - `:callsite-resolver-fn`: called with the documented payload; its result
    promotes types; throwing resolver degrades to unresolved capture.
  - Cache: two sessions over same ns with different rules don't collide.
  - No-source fallback: reconstructed-ns path produces annotations (may need a
    stubbed `find-ns-resource` or a genuinely source-less eval'd ns).
  - `with-redefs`-free, `:once` fixtures for session/analysis setup per
    clojure-engineering standards.

### Other test files

- `main_test.clj` — remove `test-main-generate-annotations` (`-g` gone); add
  `--generate-analysis` session-only coverage.
- `core_test.clj` — `test-dynamic-detection-in-rules-list` and
  `test-unlinked-rule-detection` currently rely on the hand-enriched sidecar EDN.
  Add a parallel suite feeding **generated** annotations; expect
  `extract-doc-meta-rule` to gain an upstream/downstream edge and to drop out of
  `:unresolved` (`core/detect-unresolved` string-hack currently flags it).
- `source_sink_test.clj` — re-baseline source/sink indicators for the demo session
  with generated annotations (extract-doc-meta-rule becomes a source rule with a
  downstream edge).
- `smoke_test.clj` — API shape unchanged; regenerate expectations only if the
  shipped demo annotations file changes.
- `test-resources/.../loan-doc-rules-annotations.edn` — decide role (§7 docs):
  keep as *sidecar input* for notes/no-output-types/merge-props, now that resolved
  entries are auto-derived. Trim the manually-resolved dynamic entries in tests
  that exercise generation; keep them where testing sidecar merge semantics.

### Verification commands (per AGENTS.md)

```bash
cd server
make test
make format && make format-check
make lint
make reflection-check   # new ns must hold *warn-on-reflection* true
```

---

## 7. Documentation Plan

| Doc | Change |
|---|---|
| `server/docs/rule-annotations.md` | Rewrite **Usage Workflows** around the two supported paths: "2. Generate annotations from a live session" and "3. Generate full static analysis from a live session" (API per §4.4). Remove workflow 5 (analyze-by-namespace), workflow 6 (in-memory as standalone), and Path B `-g` CLI section. Document the dynamic-detection schema (`:status`, `:resolved-types`, `:resolution`, `:resolution-method`) and the `:callsite-resolver-fn` contract with an example. Update the bundled-config description (drop-hook; custom configs document *vars* for caller macros, never `defrule` bodies). |
| `server/docs/analyze-clj-kondo-notes.md` | Keep the `with-in-str`/`:lint ["-"]`/`:filename` mechanics (still accurate); add a "Combined sources" section (real source + RHS snippets; reconstructed-ns fallback); replace strip-lhs rationale with drop-hook rationale. |
| `server/README.md` | CLI flags table: remove `-g`; `--generate-analysis` description loses the `-g` variant. Quick examples updated. |
| `docs/explorer-graph-api.md` | Verify the dynamic-detection JSON section (lines ~115–152) matches generated output — it was pre-written for this shape; only touch if drift is found. |
| `server/docs/analyze-clj-kondo-notes.md` + ns docstring in `analyze.clj` | Document the custom `:config-dir` contract change (vars only, no defrule). |

---

## 8. Phased Implementation

### Phase 1 — Session-driven kondo analysis

**Goal:** session is the source of truth for rules; macro-emitted rules appear in
annotations as captured dynamic callsites (resolution lands in Phase 2).

1. Add drop-rule hook + bundled config swap (resources, manifest, sync tool,
   `analyze.clj` `bundled-override-files`).
2. Source synthesis: ns grouping from productions, combined-source builder,
   reconstructed-ns fallback, RHS sanitization, `:lang` derivation.
3. Rework `analyze-session-rules` onto combined sources; re-key cache to
   `[ns-sym source-hash]`.
4. `generate-annotations-from-analysis`: accept `:session-or-rulebase`, default
   `:rules-filter` from session; wire combined sources into `get-source`.
5. Migrate `analyze_test.clj` to session-based fixtures (expectations unchanged
   where behavior is unchanged; callsites still position-derived at this phase).

**Exit criteria:** `extract-doc-meta-rule` present in annotations (dynamic
callsite); no duplicate rule defs; helper tracing intact; `make test lint
reflection-check` green.

### Phase 2 — Runtime callsite resolution + `:callsite-resolver-fn`

1. New `rhs.clj`: boundary detection, `rhs-callsites`, `resolve-fact-form` chain
   (steps 1–6 of §4.3, each behind its own small fn for testability).
2. Integrate into annotation generation; promote resolved types into
   `:clara-rules/insert-types` / `:retract-types`; populate
   `:status`/`:resolved-types`/`:resolution` provenance.
3. `:callsite-resolver-fn` option (payload per §4.4; error containment).
4. Update test expectations (rule-level `source-str` normalization; Java-ctor
   rules now resolved) + new tests per §6.

**Exit criteria:** `extract-doc-meta-rule` resolves to `[:extract-doc-meta]` and
links to `collect-doc-meta` in the dep-graph; demo-EDN "Resolved" entries are
reproduced *automatically* from a live session; suite green.

### Phase 3 — Remove session-less entry points + CLI cleanup

1. Remove `generate-annotations-from-paths`, `-g/--generate-annotations`,
   `run-generate-annotations`; `--generate-analysis` becomes session-only.
2. Update `main_test.clj`, README.
3. Delete strip-lhs remnants everywhere; delete `server/dev/scratch.clj` and
   `server/dev/tmp-kondo-config/` (exploration artifacts).

**Exit criteria:** no references to path-based generation remain;
`make test lint` green. (Breaking change — call out in commit message/changelog.)

### Phase 4 — Docs + demo artifacts

1. Rewrite `rule-annotations.md` workflows (paths 2 & 3) + resolver-fn docs.
2. Update `analyze-clj-kondo-notes.md`, `README.md`; check
   `docs/explorer-graph-api.md` for drift.
3. Trim/regenerate `loan-doc-rules-annotations.edn` (sidecar keeps notes /
   `:no-output-types` / merge control; generated entries no longer hand-written);
   re-baseline `core_test.clj` / `source_sink_test.clj`.

**Exit criteria:** docs describe only supported flows; demo EDN matches what the
pipeline generates.

---

## 9. Edge Cases & Open Questions

1. **Unquoted/embedded-object RHS** (`build-rule-action`, macros embedding
   atoms/fns): sanitization walk replaces non-readable values with placeholder
   symbols before `pr-str`. Verify against a `build-rule-action`-built production
   in Phase 1.
2. **Rule names illegal as `def` symbols** (e.g. containing spaces via
   `parse-rule` string names): sanitize snippet def names and map back via the
   production `:name`. Rare; guard with a test if encountered.
3. **Rules with `:env`** (REPL-captured environments): snippets can't reproduce
   env bindings → unresolved symbols in kondo (harmless, same as `?`-vars);
   runtime resolution may fail on env-bound locals → falls to resolver-fn/unresolved
   capture. Document as known limitation.
4. **Duplicate rule names across namespaces**: snippets are per-ns files; safe.
   Same-ns redefined rules: last production wins in rulebase; confirm grouping
   dedupes.
5. **`.cljc` sources**: derive `:lang` from extension (currently hardcoded `:clj`).
6. **Cache poisoning across sessions**: re-key to `[ns-sym source-hash]`; add
   regression test.
7. **Formatting drift of `source-str`** (rule-level callsites become
   `pr-str`-normalized): accepted; documented; tests updated. Alternative
   (rejected): keep position-based extraction — impossible post drop-hook, since
   source-side rule bodies no longer produce usages.
8. **`pr-str` of namespaced-map syntax or tagged literals in RHS data**: rewrite-clj
   parses tags syntactically; verify `#inst`/`#uuid` cases in Phase 1 tests.
9. **Decision point — deprecation vs removal** of `generate-annotations-from-paths`:
   plan recommends removal (user direction: session-less analysis not needed);
   confirm before Phase 3.
10. **`defsession`/durability-loaded rulebases**: productions come from the
    rulebase regardless of how the session was built, so durability-restored
    sessions work unchanged as long as their namespaces are loadable for
    source/helper analysis (reconstructed fallback covers missing sources).

---

## 10. Appendix — Exploration Artifacts (to delete in Phase 3)

- `server/dev/scratch.clj` — REPL experiments 1–7 validating: combined-source
  synthesis, drop-hook, reconstructed ns forms, end-to-end annotation generation,
  runtime var-as-fact resolution.
- `server/dev/tmp-kondo-config/` — proof-of-concept drop-hook kondo config.

Key REPL evidence:

```clojure
;; Baseline (current pipeline): 13 annotations, extract-doc-meta-rule MISSING.
;; Target pipeline (validated): 15 annotations, including
(get a7 'clara.server.tools.graph.rules.loan-doc-rules/extract-doc-meta-rule)
;; => #:clara-rules{:dynamic-insert-types-detected
;;      {:callsites [{:source-str "resolved__49581__auto__" …}]}}
;;   (Phase 2 resolves this through the let-bound (var extract-doc-meta) form:)
(rhs-data-resolution '…loan-doc-rules (:rhs (first productions)))
;; => ({:arg-form resolved__49581__auto__, :resolved-type :extract-doc-meta})
```
