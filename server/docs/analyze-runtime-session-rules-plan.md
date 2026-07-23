# Plan: Runtime Session-Based Rule Analysis

**Status:** Design (revision 3 — incorporates `plan-feedback.md` and `plan-feedback-2.md`)
**Verified against:** live nREPL session, clara-rules source, clj-kondo source

---

## 1. Problem Statement

`clara.server.tools.graph.analyze` performs **pure static analysis**: clj-kondo over
namespace _source files_, with a custom `analyze-call` hook (`strip_lhs.clj_kondo`)
making `clara.rules/defrule` comprehensible by analyzing only the RHS.

This fails for rules **emitted by custom macros** — demonstrated by
`clara.server.tools.graph.rules.helpers/def-fact-fn`, whose emitted rule
`extract-doc-meta-rule` is invisible to the analyzer without a bespoke kondo hook
(verified: missing from generated annotations).

The intended entry point is a **live Clara session**: the rulebase already contains
every rule as _data_ (`:lhs`, `:rhs`, `:props`, `:ns-name`), macro-expanded by the
real Clojure compiler. The analyzer is built on that runtime truth.

## 2. Design Principles

1. **The session is the source of truth for rules.** Rule names, LHS, RHS, and props
   come from rulebase productions — never from clj-kondo macro interpretation.
2. **clj-kondo does _all_ Clojure syntax analysis.** We never hand-walk arbitrary
   CLJ forms (no ad-hoc `tree-seq` over RHS, no hand-tracked let bindings — syntax
   walking is notoriously full of caveats and kondo is battle-tested). Our code only
   (a) reads _individual forms at kondo-provided positions_ and (b) resolves
   _symbols against the live runtime namespace_.
3. **The runtime classpath answers what static text cannot.** Vars, classes,
   imports, aliases are resolved via the live namespace the rule was defined in
   (`ns-resolve` handles aliased, fully-qualified, same-ns, and imported symbols
   uniformly — verified).
4. **Session configuration is honored.** `fact-type-fn` and `ancestors-fn` are
   pluggable; the analyzer uses the ones the session was actually built with
   (available at `(:get-alphas-fn rulebase)` meta — verified), never assumed
   defaults. Fact-type _shapes_ are arbitrary (a LHS type may be a class symbol, a
   keyword, or even a vector like `[:vector :type :thing]` — `my-rule-test1` in the
   test rules proves this flows through as a normal production).
5. **Automatic resolution stays in its lane.** The automatic chain resolves only
   what we _know_ the instance type of: deftype/defrecord constructors and Java
   constructors (including through let-bound locals, traced via kondo's `:locals`).
   Everything else — including the function-as-fact/var-as-fact pattern and
   metadata-typed facts — belongs to caller-supplied resolution: two pluggable
   points, `:fact-type-spec-fn` and `:callsite-resolver-fn` (§5.5).
6. **Greenfield API.** This branch evolves the API freely; no deprecation or
   backwards-compatibility scaffolding. Milestones exist only to make the
   implementation incrementally verifiable.

## 3. Verified Facts

### 3.1 Productions (clara-rules: `dsl.clj` `parse-rule*`)

- Keys: `:ns-name :lhs :rhs :name :handler`, optional `:props :doc :params :env`.
- `:rhs` is quoted form **data** (`(list 'quote …)`). Plain `defrule` ⇒ original
  forms with aliases intact (`r/insert!`, `laf/map->X`). Macro-emitted rules ⇒
  whatever the macro emitted (syntax-quoted, fully-qualified, gensym'd locals).
  Queries have **no `:ns-name`, no `:rhs`**.
- `defrule` compiles to several forms; the one that matters here is the one that
  emits the rule structure onto the session. Other compilation shapes are out of
  scope — no edge cases around them.
- `(:get-alphas-fn rulebase)` meta carries the session's `:fact-type-fn` and
  `:ancestors-fn` (verified: `#function[clojure.core/type]` + wrapped ancestors fn).
- `:env` captures closures for macro-defined rules (rare), e.g.
  `(let [x :thing] (defrule my-rule => (insert! {:x x})))`. The full production —
  including `:env` — is passed to the callsite resolver fn (§5.5).

### 3.2 clj-kondo

- **Snippet synthesis works** (verified): `real-source + "\n" +
"(def extract-doc-meta-rule (fn [] <pr-str-of-rhs-data>))"` yields a
  `:var-definitions` entry for the rule and `:var-usages` with
  `:from-var extract-doc-meta-rule :to clara.rules :name insert!`.
- **Prune-and-replace with the verbatim clara-rules hooks** (verified end-to-end):
  combined source analyzed with the synced `imports/clara/rules` config active;
  pruning source-region entries attributed to known production vars reduces
  var-definitions 41→30 and var-usages 238→149; afterwards every rule's defs and
  `insert!` usages come _only_ from snippet rows (181–192), and hook-emitted
  source-side noise (including LHS `acc/all` accumulator usages — the original
  strip-lhs motivation) is gone. `extract-doc-meta-rule` exists _only_ via its
  snippet. ⇒ The verbatim clara-rules kondo config is safe as our default.
- **Per-ns passes compose cross-ns** (verified): each var-usage records fq
  `:to`/`:from`; merging per-ns analyses yields a complete cross-ns call graph;
  transitive deps are discovered from `:namespace-usages` and analyzed with their
  own pass. (Answers plan-feedback-2 §3 — reconciliation happens at merge time,
  via `build-analysis-from-namespaces`.)
- **`:locals true` analysis** (verified) tracks let-bound locals for us: the
  gensym'd binding `resolved__61110__auto__` (let-bound to `(var extract-doc-meta)`)
  appears in `:locals` at its binding position; its use inside
  `(clara.rules/insert! resolved__…)` appears in `:local-usages` with the same
  `:id`. Gensym names vary per JVM (`__49581__` vs `__61110__` across restarts), so
  positions/ids are the currency, never name strings.
- **Weird `def` names don't fail loudly** (verified): `(def my rule (fn [] :x))`
  silently defines `my` (with an invalid-arity finding). So production names are
  never emitted raw into snippets — §5.1 sanitizes + maps them.
- **Record literals round-trip** (verified): `(pr-str record)` emits
  `#my.ns.Record{:x 1}`; `read-string` yields the record instance (class on
  classpath). Classifiable by construction.
- **`:refer-clojure` deviation detection** (verified mechanism):
  `(ns-publics 'clojure.core)` vs the target ns's `ns-map` ⇒ missing canonical
  names become `:exclude`, renamed become `:rename` (`:only` is covered
  semantically by excluding everything else). Default java.lang imports can't be
  excluded via the ns form — only dynamic `ns-unmap`; detect by diffing
  `ns-imports` against `clojure.lang.RT/DEFAULT_IMPORTS` and emit trailing
  `(ns-unmap …)` forms. Both deviations are rare.
- Feeding clj-kondo an explicit `:config-dir` disables cwd `.clj-kondo`
  discovery — necessary for deterministic analysis regardless of the host
  project's own lint config (observed cwd leakage during exploration).

### 3.3 Runtime symbol resolution (verified)

`(ns-resolve live-ns sym)` uniformly resolves, in the rule's original namespace:
`r/insert!` (alias) ⇒ `#'clara.rules/insert!`; `clara.rules/insert!` (fq) ⇒ same;
`extract-doc-meta` (same-ns var) ⇒ var; `StaleDocumentNotice` (import) ⇒ Class;
`map->AllIdCardGivenDocuments` (same-ns ctor) ⇒ var. One resolution path — same
shape as `serialize/resolve-type` uses.

### 3.4 End-to-end feasibility (verified)

Combined sources for both demo rule namespaces → merged analysis → existing
`generate-annotations-from-analysis` yields 15 annotations (baseline: 13),
including `extract-doc-meta-rule` as a captured dynamic callsite. Static
resolution (`map->X` tracing through helpers) is unchanged.

## 4. Target Architecture

```
                ┌────────────────────────────────────────────────┐
                │           live session / rulebase              │
                │  :productions [{:ns-name :lhs :rhs :props …}]  │
                └───────┬────────────────────────┬───────────────┘
                        │                        │
             (rules as data)              (rule namespaces as roots)
                        │                        │
                        ▼                        ▼
        ┌───────────────────────────────┐   ┌────────────────────────────┐
        │ source synthesis (per ns)     │   │ known production vars      │
        │  base = real source           │   │ (rules + queries, fq syms) │
        │       | reconstructed ns form │   └─────────────┬──────────────┘
        │  + appended:                  │                 │
        │    (def <tag> (fn [] rhs))    │                 │
        └──────────┬────────────────────┘                 │
                   │ one kondo run per ns                 │
                   │ (default: verbatim clara-rules       │
                   │  hooks config)                       │
                   ▼                                      ▼
        ┌─────────────────────────────────────────────────────┐
        │ prune-and-replace (§5.3):                           │
        │  drop source-region entries attributed to known     │
        │  production vars; snippet region is authoritative   │
        └──────────┬──────────────────────────────────────────┘
                   │ merged, pruned analysis (var-usages, :locals, …)
                   ▼
        ┌─────────────────────────────────────────────────────┐
        │ generate-annotations-from-analysis                  │
        │  static ctor tracing (existing machinery)           │
        │  dynamic callsites → ctor resolution chain (§5.4)   │
        │    → :callsite-resolver-fn (§5.5) → capture         │
        └─────────────────────────────────────────────────────┘
```

| Concern                                                     | Owner                                                                                 |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Rule set, names, LHS, RHS, props                            | **Session rulebase**                                                                  |
| Call graph, locals/binding tracking, macro forms, positions | **clj-kondo** on synthesized sources                                                  |
| Caller macros that expand to _vars_                         | **Caller's `:config-dir`** (optional)                                                 |
| `defrule`/`defquery` constructs produced by kondo hooks     | **Pruned** (§5.3) — session is authoritative                                          |
| Constructor → fact-type tokens                              | **Automatic chain** (§5.4)                                                            |
| Var-as-fact, metadata-typed facts, exotic shapes            | **Caller-supplied resolution** (§5.5: `:fact-type-spec-fn` + `:callsite-resolver-fn`) |

## 5. Component Design

### 5.1 Source synthesis

Per namespace owning session rules (productions with `:rhs`, grouped by
`:ns-name`; queries skipped — no `:rhs`):

1. **Base source**, first match wins:
   a. real source via existing `find-ns-resource`;
   b. **reconstructed ns form** (no source on classpath — jars, eval'd code).
   Built from the live `Namespace` object. **Do not enumerate defaults** —
   `clojure.core` refers and `java.lang.*` imports are automatic in any new
   ns; we only avoid excluding them, and handle deviations:
   - `(:refer-clojure …)` clause **only when a deviation is detected**:
     compare `(ns-publics 'clojure.core)` against `ns-map` entries that map
     to `clojure.core` vars. Missing canonical names ⇒ `:exclude […]`;
     canonical names mapped under a different symbol ⇒ `:rename {…}`
     (`:only` needs no special case — it is `:exclude` of everything else).
   - `(:require …)` clauses for every alias (`ns-aliases`) and every
     non-core referred var (grouped by source ns).
   - `(:import …)` clauses for every non-`java.lang` import, grouped by
     package.
   - Immediately after the ns form, in the rare case a default import is
     missing (`clojure.lang.RT/DEFAULT_IMPORTS` vs `ns-imports` — only
     possible via dynamic `ns-unmap` in the original ns), emit literal
     `(ns-unmap (the-ns '<ns-sym>) '<Class>)` forms.
2. **Append one snippet per rule**, each on its own line:
   ```clojure
   (def <snippet-tag> (fn [] <pr-str-of-rhs-data>))
   ```
   `pr-str` under `*print-length*/*print-level* nil`. **Snippet tags are
   sanitized, never raw production names** — kondo doesn't hard-fail on bad def
   names, it silently misreads them (verified: `(def my rule …)` defines `my`).
   Snippets are emitted in a known order (one per line, matching the ns's
   production list), so a deterministic tag (e.g. ordinal or encoded name) plus
   a tag→production mapping table makes attribution exact regardless of name
   weirdness. (Common names like `my.rule` are valid symbols and pass through
   unchanged.)
3. **One `kondo/run!` per ns** over the combined string via the existing
   `with-in-str` + `:lint ["-"]` + `:filename` pattern (mechanics in
   `docs/analyze-clj-kondo-notes.md` still apply), with analysis config:
   `{:var-definitions true :var-usages true :java-class-usages true :locals true}`.
   - **Always `:lang :clj`** and a `.clj` synthetic filename — clara-rules is
     `:clj`-only; `.cljc` sources are analyzed from the clj side. No `:lang`
     derivation.
4. Record the **snippet offset** (base-source line count) per ns: entries with
   `row > offset` belong to our snippets.
5. **Merge** per-ns analyses with the existing `merge-with into` engine.
   Cross-ns references need no special handling: each var-usage records its fq
   `:to`/`:from`, so the merged map is the complete cross-ns call graph;
   `build-analysis-from-namespaces` continues to discover transitive
   dependencies via `:namespace-usages` and gives each its own pass.

### 5.2 Bundled kondo config: the verbatim clara-rules config

The default config is the **verbatim clara-rules kondo config** (the synced
`imports/clara/rules` tree we already bundle) — the common case callers would
supply themselves (this repo's own `.clj-kondo` demonstrates the setup). The
synced hooks make kondo understand `defrule`/`defquery`/`defhierarchy` etc.;
any rule constructs that produces are **pruned** (§5.3), so they cannot pollute
the analysis. Verified: with these hooks active, prune yields snippet-only rule
attribution.

- **Delete** only our override: `hooks/strip_lhs.clj_kondo` and the root
  `config.edn` that registered it (root `config.edn` becomes `{}` so the
  imported clara-rules config applies unmodified).
- **Keep** the sync tooling (`dev/clara/server/tools/graph/kondo_config_sync.clj`,
  `:sync-kondo-config` alias, `manifest.edn`) — the imports tree is now the
  whole bundled config and still needs mirroring from the clara-rules dep.
- Keep the materialization machinery (resources may live in a jar) and the
  explicit `:config-dir` passing (disables cwd discovery).
- Caller `:config-dir` semantics unchanged: **replaces** the default. Callers
  will typically include the clara-rules imports plus hooks for their own
  var-emitting macros. Contract: their config may produce vars and
  defrule/defquery constructs — the former are kept, the latter are pruned.
  Hooks for rule structure are never _needed_.

### 5.3 Prune-and-replace

Known vars = fq symbols of **all** session production names (`extract-session-rule-names`
— rules _and_ queries).

After each per-ns kondo run (before merge), drop from that ns's analysis:

- `:var-definitions` where fq name ∈ known vars **and** `row ≤ snippet-offset`;
- `:var-usages` where fq caller (`:from` + `:from-var`) ∈ known vars **and**
  `row ≤ snippet-offset`.

Snippet-region entries (`row > offset`) are always kept — they are the
authoritative rule analysis. Attribution from snippet var back to production
uses the §5.1 tag→production mapping.

Why this is robust (vs. ignore-hooks, which can't scale to every defrule-like
macro a consumer may write):

- **No hooks at all:** defrule children analyze with `:from-var nil` — nothing
  to prune; snippets provide the only rule attribution. Verified.
- **Verbatim clara-rules hooks:** source-side rule analysis lands in the source
  region attributed to known production vars — pruned. Verified (41→30 defs,
  238→149 usages).
- **Caller macro emitting a defrule, hooked to produce rule analysis:**
  attributed to a production var in the source region — pruned; the snippet is
  authoritative.
- **Caller macro emitting helper _vars_:** different names, not in the
  known-vars set — kept. (Unhooked, those vars are invisible to kondo —
  acceptable: the rule still comes from the session; only helper-body tracing
  for that var is unavailable, which the caller's `:config-dir` exists to fix.)
- `:locals`/`:local-usages` are not pruned: consumed strictly by position-keyed
  lookup against snippet-region callsites (§5.4), so source-region leftovers
  are inert. `:namespace-*`, `:java-class-usages` likewise untouched.

### 5.4 Constructor resolution chain — `clara.server.tools.graph.analyze.rhs`

Annotation inference keeps the existing backbone (`build-graph`, reachability,
`inserter-type-map`, `extract-insert-types`): statically-traceable `map->X`/`->X`
constructor types resolve exactly as today, and when static types are found they
win (no dynamic detection). What changes is the **dynamic** path: captured
callsite argument forms go through a runtime resolution chain before being
reported unresolved. All syntax understanding comes from kondo; we only read
single forms at kondo positions and resolve symbols via the live ns.

The automatic chain resolves **only what we know the instance type of** —
constructors — everything else defers to §5.5:

1. **Record ctor form** — head symbol resolves via `(ns-resolve live-ns head)`
   to a `->X`/`map->X` ctor var ⇒ class-name token. (When kondo's `:to` is
   `:clj-kondo/unknown-namespace` — possible in the no-source fallback — resolve
   against the live _caller_ ns from `:from`; the form was written there.)
2. **Java ctor form** — `(X. …)`, `(new X)`, `(X/new …)`: strip the ctor
   marker, `ns-resolve` the class name ⇒ imported/loaded Class ⇒ fq class-name
   token.
3. **Local symbol arg** (e.g. gensym'd `resolved__…__`) — find its
   `:local-usages` entry at the arg's position → shared `:id` → `:locals`
   binding → read the _init form_ immediately following the binding symbol →
   recurse (depth-capped). Terminates at 1/2, otherwise defers.
4. Otherwise ⇒ **`:callsite-resolver-fn`** (§5.5) ⇒ unresolved capture.

**Deliberately NOT in the automatic chain** (over-assumptions, per feedback):

- `with-meta` + literal `:type` — only meaningful when the session's
  `fact-type-fn` honors `:type`; that is the caller's business → resolver-fn.
- **Literal arg forms** (maps, keywords, record literals, …) — a literal is
  source text read as data; when it contains rule bindings it is an
  unevaluated template, not a runtime fact. Classifying it would mean running
  the session's caller-configured `fact-type-fn` (arbitrary semantics,
  potentially value-dependent) on fabricated data. The resolver receives the
  read object as `:arg-form` — macro-emitted record literals read back as
  genuine instances — and may classify it with full knowledge of its own
  fact-type-fn. (Removed in M2 review; originally step 4 applied the session's
  fact-type-fn to literals.)
- `clojure.core/var` / the **function-as-fact (var-as-fact) pattern** — no
  hardcoding. This pattern _is_ a first-class requirement (it is how
  `def-fact-fn`'s `extract-doc-meta-rule` inserts), but it is resolved by
  **caller-supplied guidance** (§5.5), not by built-in special cases.

**Callsite identification (unchanged mechanism):** boundary-fn var-usages
(`insert-fns`/`retract-fns` by `:to` + `:name`) in a rule's reachable subgraph —
rule-level (`:from-var` = rule) and helper-level (`:from-var` = helper).

**Arg extraction (existing mechanism):** `source-text-at` the usage's positions
into the ns's combined source (the synthesized source string is what the
internal source loader returns for that ns), `read-string` the single form.

**Type tokens:** ctor resolutions publish class-name symbols — we _know_ the
instance type being inserted; connection to LHS conditions is determined during
graph analysis (`core/build-dep-graph`) using the session's `ancestors-fn`
(existing behavior, from the same `get-alphas-fn` meta). Arbitrary fact-type
shapes (keywords, vectors, …) are fully supported on the consumption side by
construction, and on the production side via §5.5.

**Result aggregation per rule** (matches the pre-existing serialization contract
in `serialize.clj` / `docs/explorer-graph-api.md` and the demo EDN):

```clojure
{:clara-rules/insert-types […]          ; resolved types promoted here → dep-graph links
 :clara-rules/dynamic-insert-types-detected
 {:callsites [{:source-str "…"          ; the literal form at the boundary call
               :ns-name-sym …
               :filename "…"
               :status :resolved | :resolved-multi | :unresolved
               :resolved-types […]}]
  :resolution :full | :partial | :none}}
```

`:callsites`/`:source-str` exist to show a consumer _precisely which form_ at the
`insert!`/`retract!` boundary had an undetermined type — for manual follow-up or
for their resolver. `pr-str` normalization (commas, single-line) is acceptable.

### 5.5 Caller-supplied resolution

Two pluggable points. The first teaches the analyzer about the _shape_ of
caller-specific fact patterns; the second resolves individual callsites.

#### `:fact-type-spec-fn` — structured guidance for the var-as-fact pattern

The function-as-fact (var-as-fact) pattern: a fact _is_ a function var (e.g.
`def-fact-fn` emitting `(insert! (var the-fn))` with the fact type on the var's
`:type` meta), matched downstream by `[?f <- :the-type]` and invoked as a fn in
the RHS (e.g. `collect-doc-meta` binds `?extract-doc-meta <- :extract-doc-meta`
and calls `(mapv ?extract-doc-meta ?docs)`). Nothing about this is hardcoded;
the caller declares the mapping:

```clojure
:fact-type-spec-fn
(fn [fact-type]
  ;; => spec map, or nil when the fact type follows no special pattern.
  ;; Currently one key (the spec is open for extension):
  {:aliases-var fully.qualified/var-name})
```

**Mechanism** (per rule production, when a spec fn is supplied):

1. Scan the rule's `:lhs` (constrained DSL data — already walked by
   `core/extract-lhs-fact-types`) for bindings of alias-mapped fact types:
   `:fact-binding ?sym` on fact conditions and `:result-binding ?sym` on
   accumulator conditions (`:from {:type t}`). (`:result-binding` binds a
   collection; both shapes are supported.)
2. When `(fact-type-spec-fn t) ⇒ {:aliases-var v}`, determine whether `?sym` is
   _used in the RHS_. Only RHS usage can influence inserts/retracts, so this is
   the usage that matters. Detection needs no form-walking: in the rule's
   snippet, kondo records free `?sym` occurrences as var-usages
   (`:name ?sym`, `:to :clj-kondo/unknown-namespace` — verified shape, incl.
   `:arity 1` in fn position). A `?sym` usage attributed to the rule's snippet
   var ⇒ used.
3. If used, inject a **synthetic var-usage** into the merged analysis —
   `{:from rule-ns :from-var rule-snippet-var :to (namespace v) :name (name v)}`
   tagged with `{:via-var-alias {:fact-type t :fact-type-spec spec :var v}}` —
   so the existing reachability explores `v`'s whole call chain for boundary
   fns. (If `v` is invisible to kondo — macro-emitted, unhooked — its chain is
   empty and nothing is found; that is the caller `:config-dir` situation from
   §5.3.)
4. Dynamic callsites reached _through_ an alias-tagged usage are **added to the
   consuming rule's detection but never automatically resolved** — they bypass
   the ctor chain of §5.4 and are recorded `:status :unresolved` with the
   alias context attached (`:fact-type`, `:fact-type-spec`, plus the callsite's
   own `:ns-name-sym`/`:filename` pointing at where the boundary call lives,
   possibly another ns).
5. Those callsites are then offered to `:callsite-resolver-fn` with the
   alias context included (below).

This is how `extract-doc-meta` and fns like it become visible to downstream
rules' dynamic analysis without any special-casing of `clojure.core/var` or
`with-meta` in the automatic chain.

#### `:callsite-resolver-fn` — per-callsite escape hatch

Invoked once per unresolved arg form, after the automatic chain (and for
alias-discovered callsites, immediately). Exceptions are contained (logged,
treated as unresolved).

```clojure
(fn [{:keys [rule           ; map     — the FULL production (:name :ns-name :lhs :rhs :props :env …)
             ns-name-sym    ; symbol  — ns where the callsite was found (may be a helper ns)
             direction      ; :insert | :retract
             boundary-fn    ; symbol  — e.g. clara.rules/insert!
             arg-form       ; data    — the unresolved argument form
             source-str     ; string
             filename       ; string
             ;; present when the callsite was discovered via a var-alias chain:
             fact-type      ; the LHS-bound fact type that linked the aliased var
             fact-type-spec ; map     — the spec returned for that fact type,
                             ;           e.g. {:aliases-var some.ns/the-fn}
             ]
      :as callsite}]
  ;; nil ⇒ still unresolved, or:
  {:resolved-types […]      ; symbols, keywords, classes — any fact-type tokens
   :status :resolved})      ; optional; defaulted when types present
```

The full production gives resolvers complete context — including `:env` for
macro-captured closures and `:lhs` for matching conventions. `:fact-type` /
`:fact-type-spec` tell the resolver _why_ the aliased var is in the chain, so
it can decide whether the callsite is resolvable in that context. These two
keys are part of the contract from the start; they are simply absent until a
`:fact-type-spec-fn` is supplied.

The _producing_ side of the var-as-fact pattern (a rule inserting the var
itself, e.g. `extract-doc-meta-rule`'s `(insert! (var the-fn))`) is also a
resolver-fn concern: the callsite arg form `(var the-fn)` is passed through and
the caller's resolver can resolve the var and return its fact type. No alias
context keys in that direction — no alias chain is involved.

### 5.6 Caching

Cache scope = **one session analysis run**. `analyze-session-rules` creates a
fresh cache atom per invocation (default); `:cache-atom` remains as an explicit
opt-in for advanced callers who want to share a cache across related runs of the
same session. The global cache atom and `clear-global-analysis-cache!` are
removed. Within a run, keying by ns-sym is sufficient (one combined source per ns
per run). The cache exists only to avoid re-analyzing namespaces reached through
multiple dependency paths.

### 5.7 Public API (final shape)

```clojure
(analyze/analyze-session-rules
  {:session-or-rulebase session      ; required
   :include-ns-prefixes [...]        ; optional dependency-following filter
   :exclude-ns-prefixes [...]        ; optional
   :config-dir "..."                 ; optional; defaults to the bundled verbatim
                                     ;   clara-rules config; replaces it entirely
   :cache-atom (atom {})})           ; optional; fresh per call by default
;; => merged, pruned clj-kondo analysis map

(analyze/generate-annotations-from-analysis
  {:analysis analysis                ; required
   :session-or-rulebase session      ; required — runtime resolution, fact-type-fn,
                                     ;   default :rules-filter (rules only, not queries)
   :rules-filter [...]               ; optional override
   :callsite-resolver-fn f})         ; optional — §5.5
   :fact-type-spec-fn f})            ; optional — §5.5 var-as-fact guidance
;; => annotations map
```

Removed from the namespace: `generate-annotations-from-paths`,
`:in-memory-sources` (public option), `global-analysis-cache`,
`clear-global-analysis-cache!`. `main.clj` loses `-g/--generate-annotations`;
`--generate-analysis` always derives annotations from the loaded session.
`build-analysis-from-namespaces` stays as the internal transitive engine.

## 6. Namespace-by-Namespace Changes

| File                                                                      | Change                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/clara/server/tools/graph/analyze.clj`                                | Rework `analyze-session-rules` (synthesis + prune/merge). `generate-annotations-from-analysis` gains required `:session-or-rulebase` + optional `:callsite-resolver-fn`. Session-scoped cache. Default config = verbatim clara-rules imports; strip-lhs override removed. Remove `generate-annotations-from-paths`, `:in-memory-sources`, global cache. |
| **NEW** `src/clara/server/tools/graph/analyze/rhs.clj`                    | §5.4 + §5.5: callsite extraction helpers, ctor resolution chain, locals tracing, resolver-fn integration, detection-map assembly.                                                                                                                                                                                                                       |
| `resources/clara/server/tools/graph/kondo-config/`                        | Delete `hooks/strip_lhs.clj_kondo`; root `config.edn` → `{}`; **keep** synced `imports/` and `manifest.edn`.                                                                                                                                                                                                                                            |
| `dev/clara/server/tools/graph/kondo_config_sync.clj`                      | **Kept** — still mirrors the clara-rules import; docstring updated (no more override files).                                                                                                                                                                                                                                                            |
| `src/clara/server/graph/main.clj`                                         | Remove `-g` flag + `run-generate-annotations`; `--generate-analysis` session-derived only.                                                                                                                                                                                                                                                              |
| `src/clara/server/graph/api.clj`                                          | Untouched (in-flux `enriched-annotations`).                                                                                                                                                                                                                                                                                                             |
| `core.clj`, `annotations.clj`, `serialize.clj`, `memory.clj`, `nodes.clj` | Untouched — annotation shape only gains keys they already handle.                                                                                                                                                                                                                                                                                       |

## 7. Test Plan

### `analyze_test.clj` (major rewrite, session-based)

- Fixtures become sessions: `(r/mk-session '…analyze-test-rules)`,
  `(r/mk-session '…loan-doc-rules '…loan-app-rules)` — all on the test classpath.
- **Preserved behavior** (mechanically re-based): static insert/retract types;
  nested helper tracing (`rule-nested-helper-call`); machinery-exclusion
  invariants; `:no-output-types` via `:rules-filter`.
- **New:**
  - `extract-doc-meta-rule` appears as a **captured** dynamic callsite by
    default (automatic chain does not hardcode var-as-fact); resolves via a
    test-supplied `:callsite-resolver-fn` (M2); downstream alias-chain
    visibility comes from `:fact-type-spec-fn` (M4).
  - Locals-chain resolution of ctor forms is gensym-name independent (assert
    resolution, never specific gensym strings).
  - Java-ctor rules (`rule-java-constructor-*`, `rule-retract-java-*`,
    helper-level ctors) resolve: `:status :resolved`, promoted types.
  - **Config parity:** `analyze-session-rules` with the default (verbatim
    clara-rules) config vs a caller-style config (clara imports + a var-emitting
    macro hook) ⇒ identical annotations; no duplicate defs/usages.
  - **No-config robustness:** explicitly empty `:config-dir` ⇒ same rule
    attribution (prune is a no-op, snippets carry everything).
  - Arbitrary fact-type shapes: `my-rule-test1` (LHS type
    `[:vector :type :thing]`) flows through as a normal production.
  - Weird production names: snippet tags are sanitized/mapped; attribution
    stays exact (e.g. a name containing a space must not leak into a `def`).
  - Record-literal arg form ⇒ classified via session `fact-type-fn`.
  - `:callsite-resolver-fn`: receives the full production; result promotion;
    throwing resolver degrades to unresolved capture; alias-context keys
    (`:fact-type`/`:fact-type-spec`) are absent without a spec fn and present
    on alias-discovered callsites (M4).
  - No-source fallback: reconstructed ns (aliases, refers, imports,
    `:refer-clojure` deviation detection) still yields annotations.
  - Queries produce **no** annotation entries (rules-only `:rules-filter`
    default — guards a real bug: unfiltered session names include queries,
    which would otherwise be mis-marked `:no-output-types`).
  - Session-scoped cache: two sequential `analyze-session-rules` runs are
    independent.
- **Removed:** all `generate-annotations-from-paths` tests,
  `:in-memory-sources` tests, global-cache lifecycle tests.

### Other test files

- `main_test.clj` — drop `-g` coverage; `--generate-analysis` session-only.
- `core_test.clj` / `source_sink_test.clj` — re-baseline against _generated_
  annotations.
- `smoke_test.clj` — API shape unchanged; adjust only if the shipped demo
  annotations file changes.
- `test-resources/.../loan-doc-rules-annotations.edn` — re-scoped as sidecar
  _input_ carrying notes / `:no-output-types` / merge control; the
  hand-written "Resolved" dynamic entries become generated output instead.
- Enrichment tests (`add-auto-detected-annotations`, `enrich-annotations-from-session`)
  — **untouched** (parallel in-flux work).

### Verification (per AGENTS.md)

```bash
cd server
make test
make format && make format-check
make lint
make reflection-check
```

## 8. Documentation Plan (docs shaped to the final design)

| Doc                                      | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `server/docs/rule-annotations.md`        | Rewrite Usage Workflows around the two supported session paths ("2. Generate annotations from a live session", "3. Generate full static analysis from a live session") with the §5.7 API. Remove the namespace-analysis workflow, the in-memory workflow, and the CLI `-g` workflow. Document the dynamic-detection schema (`:status`, `:resolved-types`, `:resolution`, `:resolution-method`), the automatic ctor resolution chain, and the `:callsite-resolver-fn` contract (full production context) with an example. Document the `:config-dir` contract: defaults to the verbatim clara-rules config; caller configs may add hooks for their own var-emitting macros; rule constructs from any config are pruned — the session is the source of truth. Note the var-as-fact pattern as caller-guided (later milestone). |
| `server/docs/analyze-clj-kondo-notes.md` | Keep the `with-in-str` / `:lint ["-"]` / `:filename` mechanics. Replace the strip-lhs rationale with: combined-source synthesis, verbatim-clara default config + prune-and-replace, `:locals` analysis for binding tracing, reconstructed-ns fallback with `:refer-clojure`/`ns-unmap` deviation handling.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `server/README.md`                       | Flags table without `-g`; `--generate-analysis` session-only; examples updated.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `docs/explorer-graph-api.md`             | Verify the dynamic-detection JSON section matches generated output (it already documents `:status`/`:resolved-types`/`:resolution`); adjust only on drift.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

## 9. Implementation Milestones

Milestones exist for incremental verifiability — each ends with a targeted,
checkable state (`make test` + named REPL probes).

### M1 — Synthesis + prune-and-replace + session-based `analyze-session-rules`

**Status: DONE (reviewed).**

- Bundled config swap: strip-lhs override out; verbatim clara-rules imports as
  the default (sync tooling kept). ✓
- Source synthesis (real source; reconstructed fallback with deviation
  detection). Snippet append with sanitized tags + tag→production mapping +
  offset tracking. `:locals true` config. ✓
- Prune-and-replace; `analyze-session-rules` reworked; session-scoped cache. ✓
- `analyze_test.clj` migrated to session fixtures. ✓
- **Check:** `extract-doc-meta-rule` appears as a captured dynamic callsite ✓;
  no duplicate rule defs under the default config ✓; static/helper cases
  unchanged ✓; `make test` green ✓ (except the failure set verified
  pre-existing at clean HEAD: `memory_test:198`, `api-test`/`session-api-test`/
  `smoke-test` muuntaja var-encode errors, `core_test` fact-type-summary drift;
  `make lint` is _cleaner_ than HEAD — its 2 warnings were the accidentally
  nested deftests in the old analyze_test, now properly structured).
- Note: kondo's `:locals`/`:local-usages` `:id` counters vary per run, so
  run-to-run determinism is asserted on annotations (the consumer contract),
  not on raw analysis maps.

### M2 — `analyze.rhs` ctor resolution chain + `:callsite-resolver-fn`

**Status: DONE (reviewed).**

- Resolution chain (record ctors, Java ctors, locals tracing, literal objects);
  caller-ns fallback for `:clj-kondo/unknown-namespace`. ✓
- `:callsite-resolver-fn` with full-production context. ✓
- Type promotion + provenance (`:status`/`:resolved-types`/`:resolution`). ✓
- **Check:** Java-ctor dynamic cases resolve ✓ (all six syntax variants, plus
  helper-level ctors); direct-ctor entries from the demo EDN reproduce
  automatically (`StaleDocumentNotice.`) — the demo's _helper-call_ entries
  (`build-compliance-review`) do **not** auto-resolve, per the deliberately
  shrunk chain (§5.4: helper calls defer to the caller); the demo EDN is
  re-scoped in M3 accordingly. `extract-doc-meta-rule` resolves via a
  test-supplied resolver-fn ✓ (locals traced to `(var extract-doc-meta)`;
  keyword fact-type token passes through); contract tests pass ✓; `make test`
  green ✓ (full suite 67 tests / 432 assertions — including the previously
  "pre-existing" failure set, which no longer reproduces: it was environment
  flakiness in the worktree baseline, not a real regression set).
- Implementation notes: kondo `:locals` `:id`s restart per analyzed namespace,
  so binding lookups are constrained by `:filename`; resolver receives the
  locals-**traced** arg form (e.g. `(var extract-doc-meta)`) while the callsite
  `:source-str` keeps the literal boundary arg (the gensym); `distinct` on a
  raw set throws (`nth`) — token sets are already distinct by construction.
- **Post-review change (§5.4):** the literal-classification step (apply the
  session's `fact-type-fn` to non-symbol/non-seq args) was removed — a literal
  arg is source text read as data, not a runtime fact instance, and running
  caller-configured `fact-type-fn` semantics on it is an over-assumption.
  Literals (incl. record literals, which read back as genuine instances) now
  defer to `:callsite-resolver-fn` like everything else non-ctor.

### M3 — API surface + docs + demo artifacts

**Status: DONE (awaiting review).**

- Removed `generate-annotations-from-paths`, the `-g` flag
  (`main.clj` + `main_test.clj` routing test replaced with a
  `--generate-analysis` routing test + a "flag rejected" test), and any global
  cache remnants (the public `:in-memory-sources` option had already been
  dropped in M1 — synthesized sources are internal via `::combined-sources`).
  ✓
- `:session-or-rulebase` is now **required** in
  `generate-annotations-from-analysis` (throws `ex-info` otherwise; covered by
  a test); `main.clj --generate-analysis` passes the loaded session through. ✓
- Docs rewritten to final shape (§8): `rule-annotations.md` (dynamic-capture
  section now documents the resolution chain, `:status`/`:resolved-types`/`
  :resolution`, promotion, and `:callsite-resolver-fn`; workflows are
  session-only — no `-g`, no `:in-memory-sources`),
  `analyze-clj-kondo-notes.md` (prune-and-replace, synthesis,
  `::combined-sources`, locals tracing, id non-determinism), `README.md`
  (two CLI modes), and a small accuracy fix in
  `../docs/explorer-graph-api.md`. ✓
- Demo EDN re-scoped to what the automatic pipeline actually produces:
  helper-call entries (`dynamic-insert-compliance-review`/`-metadata`) flipped
  to `:unresolved`/`:none` (per the deliberately shrunk chain — resolution of
  helpers is the resolver-fn's job), stale `:constructor`/`:reason` keys
  dropped, filenames updated to the classpath-relative form
  (`clara/server/...` — synthesized-source analysis) from the old path-mode
  form (`test/clara/...`). The direct-ctor retract entry stays resolved. ✓
- Re-baselines: `core_test.clj` (`test-dynamic-detection-in-rules-list` — two
  testings rewritten to the unresolved shape + filename updates;
  `test-fact-type-summary-order` — `ComplianceReview` dropped from the fact
  types); `source_sink_test.clj` verified unaffected (no dynamic-rule
  references); `api_test`/`smoke_test` verified unaffected. ✓
- Deleted exploration artifacts (`dev/scratch.clj`, `dev/m1_probe.clj`,
  `dev/m3_probe.clj`, `dev/tmp-kondo-config/`, `dev/tmp-empty-config/`,
  `dev/tmp-clara-config/`). ✓
- **Check:** no references to removed entry points ✓; full quality gates
  green ✓ (`make test` = 68 tests / 436 assertions, 0 failures;
  format-check / lint / reflection-check clean).

### M4 — `:fact-type-spec-fn` var-alias chains

**Status: DONE (awaiting review).**

- Implemented the `:fact-type-spec-fn` mechanism of §5.5:
  `rhs/lhs-var-bindings` (LHS scan: `:fact-binding` on fact conditions,
  `:result-binding` on accumulators — fact types taken from the `:from`
  subtree — with nested and/or/not/exists walking; production bindings are
  keywords like `:?t`, converted to symbols), RHS usage detection via the
  rule's snippet var-usages (kondo records free `?syms` as
  `:to :clj-kondo/unknown-namespace` usages attributed to the renamed snippet
  var — verified by probe), `rhs/alias-usage-map` (synthetic `:via-var-alias`
  -tagged var-usage emission, spec-fn exceptions contained),
  `generate-annotations-from-analysis` gains `:fact-type-spec-fn` and injects
  the synthetic usages into the analysis before `build-graph`, and
  `analyze/alias-context-for-fn` marks boundary usages whose caller is
  reachable from an aliased var. ✓
- Alias-discovered callsites bypass the ctor chain (recorded `:unresolved`
  with `:fact-type`/`:fact-type-spec` on the entry) and are handed to
  `:callsite-resolver-fn` with the same context keys; resolver-resolved
  alias callsites promote normally. Non-alias callsites (incl. the producing
  side's `(var the-fn)`) carry no alias keys. ✓
- `serialize-dynamic-callsite` passes the two new keys through to JSON
  (`:fact-type` via `resolve-type`, spec values stringified);
  `../docs/explorer-graph-api.md` callsite-entry table updated; the
  `:fact-type-spec-fn` section added to `rule-annotations.md`. ✓
- Test fixtures (`analyze_test_rules.clj`): `widget-transform` — a var-fact
  fn (`defn` with `:type` meta) whose body performs an unresolvable dynamic
  insert — plus `rule-insert-widget-transform` (producing side) and
  `rule-consume-widget-transform` (binds `[?t <- :widget-transform]`, invokes
  `(mapv ?t [?app-id])` in the RHS). ✓
- **Check:** with a spec fn mapping `:widget-transform ⇒ {:aliases-var
…/widget-transform}`, the dynamic callsite inside the aliased var's chain
  attaches to the consuming rule as `:status :unresolved` with the fact-type
  and spec attached ✓; the resolver-fn receives those keys (and resolving
  promotes `[:widget-output]`, `:resolution :full`) ✓; without a spec fn the
  consumer shows `:no-output-types` (nothing alias-derived appears) ✓;
  a throwing spec fn degrades to the no-spec-fn annotations ✓.
- Implementation notes: the spec-fn ran against a kondo-*visible* plain
  `defn`, which surfaced a pre-existing reachability semantic — a plain
  `(var the-fn)` reference in a rule RHS already explores the var's chain via
  kondo's var-usage of the var special form (the producer rule's detection
  includes the var body's callsites in the *baseline* too, without alias
  context keys). This is consistent with how any fn reference in an RHS pulls
  in the callee's chain; only alias-*derived* discovery carries the context.
  `lhs-var-bindings` unit-tested directly for the accumulator
  `:result-binding` shape (no accumulator fixture needed).
- **Gates:** `make test` = 70 tests / 454 assertions, 0 failures;
  format-check / lint / reflection-check clean.

## 10. Edge Cases & Open Questions (resolved per feedback round 2)

1. **Rule names illegal as `def` symbols** — _explored_: kondo does not
   hard-fail; `(def my rule …)` silently defines `my`. So raw production names
   are never emitted; snippet tags are sanitized deterministically and mapped
   back to productions via generation order (§5.1.2).
2. **Rules with `:env`** (macro-captured closures, e.g.
   `(let [x :thing] (defrule …))`): rare; the full production — `:env`
   included — is passed to `:callsite-resolver-fn`, which may incorporate it.
3. **Record literals in RHS data** (`#my.ns.Record{:x 1}`): supported by
   construction — kondo parses tagged literals syntactically; extraction reads
   them back as instances; the instance is passed to `:callsite-resolver-fn`
   as `:arg-form` (post-M2-review: no automatic `fact-type-fn` classification
   of literals — see §5.4).
4. **Arbitrary fact-type shapes** (e.g. `[:vector :type :thing]`): supported.
   Automatic resolution publishes only ctor instance types (class tokens we are
   certain of); how those connect to LHS conditions is decided in graph
   analysis via the session's `ancestors-fn`. Anything beyond ctors is
   caller-resolved.
5. **No-source namespaces** (jars without sources, eval'd code): reconstructed
   ns form with deviation detection (§5.1b). Helper-body tracing is unavailable
   there by nature; rule-level resolution still works; same-ns ctor
   attribution uses the live caller ns when kondo reports
   `:clj-kondo/unknown-namespace`.
6. **Intern stubs** — _dropped from the design_. Their only use was restoring
   same-ns var attribution in the no-source fallback; superseded by resolving
   ctor symbols against the live _caller_ ns (`:from`) at resolution time.
7. **Deeply nested local chains** (`(let [a (->X) b a] …)`): recursion through
   kondo locals, depth-capped; overflow ⇒ resolver-fn / capture.

## 11. Appendix — Exploration Artifacts (deleted in M3)

- `server/dev/scratch.clj` — REPL experiments: combined-source synthesis,
  prune evidence (empty + verbatim-clara configs), reconstructed ns forms,
  end-to-end annotations, locals tracing, symbol resolution, weird def names,
  record literals, refer-clojure detection.
- `server/dev/tmp-kondo-config/`, `server/dev/tmp-empty-config/`,
  `server/dev/tmp-clara-config/` — PoC configs.

Key evidence:

```clojure
;; prune with verbatim clara-rules hooks active:
;;   var-definitions 41 → 30, var-usages 238 → 149
;;   rule insert! usages afterwards only at snippet rows (181–192)
;;   source-side hook noise pruned incl. LHS acc/all accumulator usage

;; kondo :locals linkage (positions, not gensym names):
;;   :locals        {:name resolved__61110__auto__, :id 250, row 180, col 58-81}
;;   :local-usages  {:name resolved__61110__auto__, :id 250, row 180, col 127-150}
;;   init form at row 180 (after col 81) reads: (var extract-doc-meta)

;; (ns-resolve live-ns …): r/insert! ⇒ #'clara.rules/insert!,
;;   StaleDocumentNotice ⇒ Class …loan_doc_rules.StaleDocumentNotice, etc.

;; (:get-alphas-fn rulebase) meta ⇒ {:fact-type-fn #function[clojure.core/type]
;;                                   :ancestors-fn #function[…wrapped…]}
```

## 12. Feedback Incorporation Map

### Round 1 (`plan-feedback.md`)

| #     | Feedback                                                              | Design response                                                                                         |
| ----- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| 1, 14 | `build-rule-action` not an edge case                                  | Removed entirely                                                                                        |
| 2     | `fact-type-fn`/`ancestors-fn` configurable                            | Session's fns from `(:get-alphas-fn rulebase)` meta; graph matching already uses session `ancestors-fn` |
| 3     | Ignore-hook unsustainable; prune by known session vars                | §5.3 prune-and-replace; verified against verbatim clara hooks                                           |
| 4, 10 | RHS symbols always resolvable via live ns; drop `resolve-aliased-sym` | §3.3: single `ns-resolve` path; locals handled by kondo `:locals`                                       |
| 5     | Vet `:in-memory-sources`                                              | Removed as public option; source map is internal                                                        |
| 6     | Reconstructed ns must honor full ns structure                         | §5.1b (refined further in round 2)                                                                      |
| 7     | `:clj` only                                                           | Always `:lang :clj`, `.clj` synthetic filenames                                                         |
| 8     | Cache scope = one session                                             | §5.6: fresh atom per run; global cache removed                                                          |
| 9     | Name under `analyze`                                                  | `clara.server.tools.graph.analyze.rhs`                                                                  |
| 11    | No hand-rolled RHS walking                                            | §5.4: kondo syntax analysis only; position-guided reads + `ns-resolve`                                  |
| 12    | `:callsites` = literal boundary forms                                 | §5.4 aggregation                                                                                        |
| 13    | No breaking-change phasing                                            | §5.7 final-shape API; §9 reframed milestones                                                            |

### Round 2 (`plan-feedback-2.md`)

| #   | Feedback                                                                                                          | Design response                                                                                                                                  |
| --- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | Default to verbatim clara-rules config; verify hooks don't break us                                               | §5.2 rewritten (imports kept, sync tooling kept); verified: prune yields snippet-only attribution with hooks active (41→30 defs, 238→149 usages) |
| 2   | Don't enumerate core/java.lang; detect `:refer-clojure` deviations; `ns-unmap` for missing default imports        | §5.1b rewritten: deviation detection via `ns-publics`/`ns-map` and `RT/DEFAULT_IMPORTS`; verified detection mechanism                            |
| 3   | Cross-ns traversal with per-ns passes?                                                                            | §5.1.5 + §3.2: reconciliation at merge time via fq `:to`/`:from`; transitive deps via `:namespace-usages`                                        |
| 4   | Automatic chain over-specified; no `with-meta`/`var` special cases; var-as-fact is caller-guided, later milestone | §5.4 chain shrunk to ctors + locals-to-ctor; §5.5 var-as-fact noted as M4 caller-supplied guidance                                               |
| 5   | Resolver gets the entire rule structure                                                                           | §5.5: `:rule` = full production (incl. `:env`, `:lhs`, `:props`)                                                                                 |
| 6a  | Weird def symbols — explored?                                                                                     | §10.1: explored; silent misreads; sanitize + map                                                                                                 |
| 6b  | Record literals — how encountered? support them                                                                   | §10.3: hypothetical, now verified supported by construction                                                                                      |
| 6c  | Arbitrary fact-type shapes (e.g. `[:vector :type :thing]`)                                                        | §2.4 + §10.4: publish ctor instance types only; session `ancestors-fn` decides LHS connectivity; `my-rule-test1` fixture                         |
| 6d  | Intern stubs — elaborate                                                                                          | §10.6: dropped; superseded by live caller-ns resolution                                                                                          |

### Round 3 (var-as-fact design refinement)

| #   | Feedback                                                                                                                       | Design response                                                                                                                                                               |
| --- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `:callsite-resolver-fn` is not the only pluggable point for locals bound to facts and called as fns                            | §5.5 split into two pluggable points: `:fact-type-spec-fn` + `:callsite-resolver-fn`                                                                                          |
| 2   | Caller maps fact-types → spec `{:aliases-var fq/var}`                                                                          | §5.5 `:fact-type-spec-fn` contract; spec map open for extension                                                                                                               |
| 3   | Fact types bound as result bindings & used as locals ⇒ treat as var usages; explore the var's call chain for dynamic callsites | §5.5 mechanism: LHS binding scan (`:fact-binding`/`:result-binding`), RHS usage via snippet var-usages, synthetic alias-tagged usage injection into the existing reachability |
| 4   | Alias-discovered callsites are added but NOT automatically resolved                                                            | §5.5 step 4: bypass ctor chain, recorded `:status :unresolved` with alias context                                                                                             |
| 5   | Resolver-fn must receive the fact-type and the spec returned for it                                                            | §5.5 contract: optional `:fact-type` / `:fact-type-spec` keys, in the contract from the start (absent until a spec fn is supplied)                                            |
