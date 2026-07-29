# Defect: spurious record-constructor types outrank constructor-of-interest resolution

_Filed 2026-07-28 against `2e1e716`. Status: **fixed** — see the "Fix plan"
section below; implemented as planned (precedence inversion + labeled,
rulebase-scoped, per-inserter-var fallback + `:dynamic-type-fallback-resolution`
option + `tap>` skip tracing). Found while integrating a
downstream consumer that registers `:fact-constructors` hooks for a runtime fact builder._

## Summary

`extract-insert-types` treats the record/Java-constructor scan as **authoritative when non-empty**
and short-circuits the entire dynamic path on it. That scan is a *speculative, name-shape-based
guess over a whole reachable subtree*; the constructor-of-interest path it suppresses is an
*explicit, caller-registered, provenance-carrying resolution*. Precedence is therefore inverted:
the weakest evidence silently overrides the strongest.

When the guess produces a false positive, the affected rule does not merely gain a junk type — it
**loses its real `:insert-types` entirely** and its `:clara-rules/dynamic-insert-types-detected`
record disappears, so nothing in the output signals that resolution was skipped. The annotation
looks confidently, cleanly wrong.

## Symptom

A rule whose RHS is a plain, directly-resolvable constructor-of-interest call:

```clojure
(defrule some-condition
  ...
  => (insert! (->fact :some.ns/some-condition {...})))   ; ->fact = registered :fact-constructors hook
```

Correct annotation (what the constructor path produces when it is allowed to run):

```edn
#:clara-rules{:insert-types [:some.ns/some-condition]
              :dynamic-insert-types-detected
              {:callsites [{:constructor-sym some.lib/->fact
                            :status :resolved
                            :resolved-types [:some.ns/some-condition]
                            :via {:boundary-var-name-sym clara.rules/insert!
                                  :callstack [{:var-name-sym …/some-condition}
                                              {:var-name-sym some.lib/->fact}]}}]
               :resolution :full}}
```

Observed instead, because an unrelated third-party record constructor is reachable somewhere in the
rule's subtree:

```edn
#:clara-rules{:insert-types [malli.core.Tag malli.core.Tags]}
```

No callsite record, no `:resolution`, no trace of the real type. The rule's genuine output type is
gone from the graph, and two types it never inserts are now in it.

## Mechanism

Three layers compose into the failure.

### 1. The candidate scan is name-shape based

`server/src/clara/server/tools/graph/analyze/ctor.clj:11` — `constructor-fn-name?` accepts *any*
callee whose name starts with `->` or `map->`. `resolve-record-type` (`ctor.clj:23`) then confirms
the candidate only by checking that a same-named class **loads**. Loading is a weak filter: it
confirms the symbol names a record ctor, not that the record is a fact.

### 2. The scan is subtree-wide and argument-blind

`server/src/clara/server/tools/graph/analyze/index.clj:157` — `build-inserter-type-map` unions the
resolvable record ctors found across a direct inserter's **entire transitive reachable subtree**:

```clojure
(let [subtree (reachable-set v)
      types (into #{} (comp (mapcat #(get usages-by-caller %))
                            (filter #(-> % :name name ctor/constructor-fn-name?))
                            (keep #(resolve-record-type (:to %) (:name %))))
                  subtree)] ...)
```

Its own docstring concedes the over-approximation: clj-kondo's flat `:var-usages` cannot tell an
argument expression apart from an independent call in the same body, so "a var's reachable subtree
may include constructors from unrelated RHS branches." Nothing here checks that the constructed
value ever flows to the boundary call.

`analyze.clj:136` — `extract-constructor-types-from-reachable` then unions those sets again across
**every** inserter var reachable from the rule. Two dilution steps, no data-flow check.

The practical reach is much wider than "the rule's own code": one validation/coercion helper deep in
a shared utility namespace is enough. In the observed case the chain was
`rule → domain helper → validation helper → malli.core/->Tag`, where `Tag` is an internal
parse-result record of a schema library that the rule never inserts and never sees.

### 3. Precedence: any hit short-circuits everything

`server/src/clara/server/tools/graph/analyze.clj:159` —

```clojure
(let [static-types (extract-constructor-types-from-reachable reachable inserter-type-map)]
  (if (seq static-types)
    {:static-types static-types :resolved-types #{} :dynamic-forms nil}   ; ← hard exit
    ,,, ; constructor-of-interest resolution, locals tracing, :callsite-resolver-fn
    ))
```

One resolvable `->X` anywhere in the subtree discards, unexamined:

- the caller-registered `:fact-constructors` match + `:type-resolver-fn` resolution,
- boundary-argument locals tracing,
- the optional `:callsite-resolver-fn`,
- and the `:callsites` / `:resolution` record that would otherwise document what happened.

The docstring's stated rule — *"statically-traceable record constructors win when present"* — is the
defect, stated as intent. It is a reasonable default only under the assumption that a reachable
record ctor is evidence about the boundary argument. It is not; it is evidence that *some record is
constructed somewhere downstream*.

This also inverts the guarantee the constructor-of-interest feature advertises. `extract-insert-types`
is careful, further down, to let the constructor path run *first* against boundary args and to mark
which args it "owns" so `:callsite-resolver-fn` cannot double-report them — precedence is modelled
deliberately there. The record-ctor scan bypasses that whole design by exiting before any of it runs.

## Why it is not caught by existing mitigations

- **`:clara-rules/no-output-types`** (the escape hatch named in `build-inserter-type-map`'s
  docstring) suppresses a rule that produces *nothing*. It cannot express "these two of my types are
  junk, the third is real," and here the real type is not in the output to keep.
- **`:exclude-ns-prefixes`** does not help. It controls which namespaces are *linted*; the offending
  edge (`helper → ->Tag`) lives in the analysis of the **calling** namespace, and
  `resolve-record-type` resolves the class through the **live runtime**, not through kondo data.
  Excluding the record's own namespace changes nothing.
- **Registering `:fact-constructors`** does not help, which is the sharpest point: the downstream
  consumer did exactly what the extension API asks for, and the resolution is discarded before the
  hook is consulted.

## Evidence

Measured on a real ruleset (3367 rules) with `:fact-constructors` hooks registered for a runtime
fact builder:

| | rules with a `malli.*` type in `:insert-types` | of those, **only** `malli.*` types |
|---|---|---|
| before a call-graph-widening config fix | 36 | 36 |
| after | 50 | 47 |

Two things to read here:

1. **36 rules were already fully mis-annotated before any change** — every one of them had its real
   types displaced, none had a mixed result. The defect is long-standing and silent, not an artifact
   of the change that surfaced it.
2. Widening the call graph (making previously-invisible macro-defined helper fns resolvable, which
   is otherwise a strict improvement) added 14 more. **9 of those demonstrably had correct,
   `:resolution :full` annotations immediately before** and regressed to `malli.*`-only. Improving
   graph completeness makes this defect worse, monotonically — every future fidelity gain in the
   call graph increases the blast radius.

Representative regression: a condition rule whose RHS is a single direct
`(insert! (->fact :calculations.conditions/paystub-monthly-variable-income {...}))` went from a
`:resolution :full` callsite carrying the correct keyword to
`:insert-types [malli.core.Tag malli.core.Tags]` with no callsite record at all.

## Impact

- **Silent.** A displaced type is indistinguishable in the output from a correctly resolved one.
  There is no `:resolution`, no `:status`, no note. Consumers reading `:insert-types` have no signal.
- **Graph-corrupting.** The rule is unlinked from its real downstream consumers and linked to
  fictitious `malli.core.Tag` producers/consumers, so fact-flow tracing silently loses edges.
- **Anti-correlated with progress.** The better the call graph gets, the more rules it hits.
- **Defeats the documented extension contract**, which is the reason a downstream consumer registers
  constructor hooks in the first place.

## Fix options

Ranked by directness. (1) is the minimal change that resolves the reported defect.

1. **Invert the precedence.** Run the constructor-of-interest path first; fall back to the
   record-ctor scan only for boundary arguments the constructor path did not own — reusing the
   `:owned-arg-idxs` mechanism already present below the short-circuit. Explicit registration should
   never lose to an unregistered heuristic.
2. **Stop short-circuiting; union and label.** Emit record-ctor-derived types as callsites with a
   distinguishable status (e.g. `:status :heuristic`, or `:via {:source :record-ctor-scan}`) so they
   are visible and filterable rather than silently authoritative. Preserves today's recall, removes
   the silence.
3. **Constrain the scan to the boundary argument.** Only credit a record ctor that appears in — or
   is traced into — the boundary call's argument form, the same locals tracing the dynamic path
   already performs. Removes the false positive at its source; the largest change.
4. **Give callers a veto.** A `:record-ctor-exclude-fn` / ns-prefix predicate consulted by
   `build-inserter-type-map`. Cheap and unblocks consumers immediately, but it is a workaround: it
   asks every caller to enumerate third-party record namespaces they have never heard of.

(1) and (2) compose and are independent of (3).

## Reproduction sketch

```clojure
(defrecord Unrelated [x])                       ; stands in for a 3rd-party lib record

(defn helper [m]
  (when (:validate? m) (->Unrelated 1))         ; unrelated branch, never inserted
  (->fact :my/type m))                          ; registered constructor of interest

(defrule r [:some/trigger [{:keys [m]}] (= m ?m)] => (insert! (helper ?m)))
```

Expected `:insert-types` `[:my/type]` with a `:resolution :full` callsite; actual
`[user.Unrelated]` with no callsite record.

---

# Fix plan

_Combines fix options (1) + (2) with a rulebase-scoped constraint on the heuristic
scan. Supersedes the "Fix options" ranking above — (3) is not attempted in this
pass; (4) is subsumed by the option in step 3._

## Locked decisions

1. **Invert the precedence (fix option 1).** Caller-given resolution mechanisms
   (`:fact-constructors` + boundary-arg tracing + `:callsite-resolver-fn`) always
   run first and are never displaced by the record/Java-ctor scan. The scan
   becomes a *fallback*, never a short-circuit.
2. **Label heuristic output (fix option 2).** Types derived from the scan are
   emitted as callsites carrying `:via {:source :record-ctor-scan}` so
   downstream consumers can see and filter by confidence. The scan stays
   **record-ctor only** (`->X`/`map->X`): Java constructors are not vars, must
   be handled differently, and are already resolved precisely at the boundary
   by `resolve-ctor-form` — they are deliberately out of scan scope.
3. **Scope the heuristic to rulebase fact types by default, respecting the
   session's type hierarchy.** The scan credits a resolved record/Java ctor
   class `C` only when `C` **or any of its ancestors** appears as a fact type
   on the LHS of at least one rule/query production in the session. Ancestors
   come from the session's own `:ancestors-fn` (LHS matching is hierarchical,
   not `=` — an inserted `Circle` must satisfy an LHS on `Shape`). The scan is
   evidence that "some record is constructed somewhere downstream"; intersecting
   with the session's known fact types removes the third-party noise (e.g.
   `malli.core.Tag`) that dominates the false positives. Facts whose type no
   rule or query consumes have little value and are difficult to extract in
   general, so dropping them by default is acceptable — with optional `tap>`
   tracing (step 3) so consumers can observe what was skipped.
4. **Make the scoping explicit and overridable** via a new
   `generate-annotations-from-analysis` option:

   ```clojure
   :dynamic-type-fallback-resolution #{:none
                                       :rulebase-fact-types-only   ; default
                                       :all-resolvable-fact-types}
   ```

   * `:none` — the scan never runs. Strictest; annotations come only from
     caller-registered resolution paths.
   * `:rulebase-fact-types-only` (default) — scan results intersected with the
     session's LHS fact types.
   * `:all-resolvable-fact-types` — today's scan behavior (minus the precedence
     bug): any loadable record/Java ctor class in a reachable subtree counts.

## Design

### Step 1 — Per-inserter-var fallback in `extract-insert-types` (`analyze.clj`)

Remove the `(if (seq static-types) …)` hard exit. New flow:

1. Run the existing dynamic path unconditionally (boundary usages →
   `trace-boundary-args` → `resolve-constructor-callsites` →
   `resolve-boundary-callsites` with `:owned-arg-idxs` exclusion). Unchanged.
2. **Fallback granularity is the direct-inserter var, not the rule.**
   `inserter-type-map` is keyed by inserter var and boundary usages know their
   caller, so group boundary usages by caller var. For each reachable
   direct-inserter var whose boundary args were *not* resolved (not owned by a
   constructor of interest, and no resolved types from the boundary path),
   fall back to that var's scan types from `inserter-type-map` (already
   filtered per step 3).
   * This is fix option 1's "only for boundary arguments the constructor path
     did not own" at the coarsest granularity the subtree-wide scan can
     support: the scan cannot attribute types to individual args, but it can
     attribute them to an inserter var.
   * The reproduction sketch dies here: `helper` owns its boundary arg via the
     registered `->fact`, so `user.Unrelated` is never consulted.
   * **Granularity limit (accepted):** multiple insert sites in the *same* var
     share that var's verdict — a rule whose own body both `(insert! (->fact
     …))` and `(insert! (opaque-helper …))` gets no scan fallback for the
     second site, because the var was already handled. Different inserter vars
     (e.g. a helper that itself calls `insert!`) are judged independently.
3. Fallback types are returned in `:resolved-types` (so they still promote into
   `:clara-rules/insert-types` / `:retract-types` in `infer-annotation-for-var`)
   **and** as heuristic callsites in `:dynamic-forms` — one entry per
   (inserter-var, type) — never in `:static-types`. The `:static-types` slot
   disappears from the return map (it exists only to serve the short-circuit).

### Step 2 — Heuristic labeling (`callsite.clj`, `serialize.clj`, `api.clj`)

* `callsite/ViaChain` gains `(s/optional-key :source)` with the enum value
  `:record-ctor-scan`; `:boundary-var-name-sym`/`:callstack` become optional
  (a heuristic entry has a known inserter var but no traced callstack — when
  the inserter's boundary fn is known from the graph, include
  `:boundary-var-name-sym`; omit `:callstack`).
* To support that labeling, `inserter-type-map` values change from
  `#{type-sym}` to `{type-sym {:usage kondo-usage}}` (first usage by source
  position, kept deterministic) so heuristic callsites carry real provenance:
  ctor name as `:source-str`, ctor ns as `:ns-name-sym`, and `:filename`.
* Heuristic callsites use the existing `:status` enum (`:resolved` /
  `:resolved-multi`). `resolution-status` is unchanged: a heuristic-only rule
  reports `:resolution :full`, with `:via :source` carrying the confidence
  signal. (Rejected: a `:resolution :heuristic` enum value — ripples into
  `api.clj` `DynamicDetectionInfo` and all consumers for marginal gain.)
* `serialize/serialize-dynamic-callsite` passes `:via :source` through;
  `api.clj` `ViaChain` schema gains the optional `:source` key.
* `docs/explorer-graph-api.md` updated for the new `:via :source` shape and the
  new option (API-contract change per repo guidelines).

### Step 3 — Hierarchy-aware, rulebase-scoped filter (`analyze.clj`, `alias.clj`, `index.clj`)

* Collect the session's LHS fact types once in
  `generate-annotations-from-analysis`: walk every production's `:lhs` with
  `alias/subtree-fact-types` (promote it from private or add a public
  `rulebase-fact-types` wrapper in `alias.clj`). **Verified against the
  clara-rules source (`/Users/mrrodriguez/Projects/gateless/clara-rules`): no
  existing function extracts LHS fact types from a rulebase** —
  `clara.tools/inspect` walks network nodes, not production `:lhs` — so this
  walk is ours to keep.
  * **Verified:** defquery productions do **not** live in rulebase
    `:productions` (rules only) — queries hang off `:query-nodes` (each node
    carries its query map under `:query`). `alias/rulebase-fact-types` unions
    both, since queries are the terminal consumers we care about.
* Obtain the session's `:ancestors-fn` from
  `(-> rulebase :get-alphas-fn meta :ancestors-fn)`. Verified:
  `compiler/build-network` stores `:get-alphas-fn` in the rulebase map itself
  (compiler.clj:2035) and `create-get-alphas-fn` attaches
  `^{:fact-type-fn … :ancestors-fn …}` metadata (compiler.clj:1945) — the
  *wrapped* ancestors-fn, identical to what runtime insertion uses
  (compiler.clj:1935: `(conj (wrapped-ancestors-fn fact-type) fact-type)`).
  Works for both LocalSession and bare-rulebase inputs; fall back to
  `clojure.core/ancestors` when the metadata is absent.
* The filter admits a scan-resolved class `C` iff `C` or any
  `(ancestors-fn C)` matches an LHS fact type. Both sides are normalized for
  comparison (LHS `:type` values may be Classes, class-name symbols, or
  keywords; the scan produces fq class-name symbols). `ancestors-fn` must be
  called on the loaded `Class` object, not the symbol — extend
  `resolve-record-type` (or add a sibling resolver) to also hand back the
  resolved Class; it already loads the class to confirm the ctor.
  * **Caveat to document:** a custom `:fact-type-fn` that maps record
    instances to non-class types (e.g. keywords) means class-ctor scan tokens
    can never match those LHS types — same mismatch as today, unaffected by
    this change.
* `index/build-analysis-index` takes the new
  `:dynamic-type-fallback-resolution` mode plus the filter predicate. It wraps
  the memoized `resolve-record-type` with the filter **only for the resolver
  passed to `build-inserter-type-map` / the retractor map**. The same memoized
  resolver is shared with the precise boundary-arg path (`resolve-ctor-form`
  via `resolve-boundary-callsites`) — that path is argument-scoped and stays
  unfiltered.
* **`tap>`-based skip tracing (off by default).** When the filter rejects a
  resolved type, emit a `tap>` with the full context a consumer needs to
  understand what they are seeing:

  ```clojure
  (tap> {:event        :clara-rules/type-fallback-skipped
         :boundary     :insert              ; or :retract
         :mode         :rulebase-fact-types-only
         :inserter-var some.ns/helper       ; direct inserter var owning the subtree
         :skipped-type malli.core.Tag       ; the rejected fq class-name symbol
         :ctor-ns      malli.core           ; kondo usage :to
         :ctor-name    ->Tag                ; kondo usage :name
         :filename     "malli/core.cljc"    ; callsite coordinates when known
         :row          123 :col 45})
  ```

  No tap is registered by default, so this is inert unless a consumer calls
  `add-tap` — Clojure's tap machinery *is* the opt-in switch, no extra flag
  needed. Note the filter runs at index-build time, so taps fire per
  (inserter-var, type) pair rather than per rule. Spam is acceptable — this is
  a tracing aid only.

### Step 4 — Option plumbing (`analyze.clj`)

* `GenerateAnnotationsOptions` gains
  `(s/optional-key :dynamic-type-fallback-resolution)` with the enum schema
  above; docstring documents the three modes and the default.
* The mode is threaded to `build-analysis-index` (filter construction) and into
  the infer ctx (so `extract-insert-types` knows whether a fallback is
  permitted at all under `:none`).
* `analyze-session-rules` is untouched (it builds the kondo analysis, not
  annotations).

## Behavior changes to document

* Indirect (helper-hidden) inserts of fact types consumed by **no rule and no
  query** in the session were previously reported by the scan and are now
  dropped under the default mode. Such types have little value (nothing in the
  session reacts to them) and are difficult to extract reliably in general, so
  this is the intended trade — `:all-resolvable-fact-types` restores the old
  recall, and `add-tap` reveals exactly what was skipped. Types consumed
  through the type hierarchy (a subtype inserted, a supertype on the LHS)
  remain visible because the filter respects `:ancestors-fn`.
* Rules that previously got scan-only `:insert-types` with no provenance now
  either resolve through the boundary path (richer callsite records) or carry
  `:via {:source :record-ctor-scan}` markers.

## Tests (`server/test/clara/server/tools/graph/analyze_test.clj` + `rules/analyze_test_rules.clj`)

1. **Regression (the reported defect):** the reproduction sketch — registered
   `:fact-constructors` `->fact` plus an unrelated reachable `->Unrelated` —
   yields `:insert-types [:my/type]`, a `:resolution :full` callsite, and no
   `user.Unrelated` anywhere in the annotation.
2. **Per-inserter-var fallback:** a mixed rule — the rule's own
   `(insert! (->fact ...))` (owned by the constructor path) plus a call to a
   *separate* helper var that itself does `(insert! (mk-fact))`, where
   `mk-fact` indirectly returns `(->MyFact …)` and `MyFact` is on some
   production's LHS — resolves the `->fact` arg via the constructor path
   *and* falls back to `MyFact` for the helper's var; the fallback callsite
   carries `:via {:source :record-ctor-scan}`.
3. **Filter modes:** an indirect `->UnrelatedRecord` (on no LHS) — dropped
   under the default; present under `:all-resolvable-fact-types`; and under
   `:none` even an in-LHS indirect record ctor produces no fallback.
4. **Retract symmetry:** same coverage for `retract!` via
   `retractor-type-map`.
5. **Serialization round-trip:** a heuristic callsite survives
   `serialize-dynamic-callsite` with its `:via {:source …}` intact.
6. **Hierarchy:** with a session whose LHS matches a supertype (via `derive`
   or a Java superclass), an indirect scan hit on the *sub*type record ctor is
   admitted by the default filter.
7. **Skip tracing:** with a tap registered (`add-tap`), filtering out a type
   emits the documented `:clara-rules/type-fallback-skipped` event; with no
   tap registered nothing breaks (tap> is a no-op).

## Verification

```bash
cd server
make test             # full suite
make format-check     # cljfmt
make lint             # clj-kondo
make reflection-check
```

REPL checks via `clj-nrepl-eval` while implementing:

1. `(-> session eng/components :rulebase :get-alphas-fn meta :ancestors-fn)`
   returns the expected fn on a hand-built session, including with a custom
   `:ancestors-fn`/`:hierarchy` session option.
2. The reproduction sketch end-to-end: `generate-annotations-from-analysis`
   on a hand-built session, inspect the annotation EDN.
