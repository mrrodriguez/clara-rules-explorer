# Review 1 — Enhanced LHS Analysis (Accumulator Details & Bindings)

Companion to [`roadmap-enhanced-lhs-ana.md`](./roadmap-enhanced-lhs-ana.md) and
[`enhanced-lhs-ana-plan.md`](./enhanced-lhs-ana-plan.md). Reviews the work
shipped on `more-rulebase-ana-info` (commits `3a21a75`..`dd7372e`) against the
plan, the roadmap, and the
[Clojure engineering standards](../../.agents/skills/clojure-engineering/SKILL.md).

Primary focus: `server/src/clara/server/tools/graph/conditions.clj` and its
consumers (`core.clj`, `serialize.clj`, `graph/api.clj`,
`ui/.../LhsCondition.svelte`, `ui/.../api.ts`).

---

## 1. Verdict

The core approach is sound and the implementation is mostly faithful to the
compiler. Option A (`accumulator-info`) and leaf-level Option B
(`analyze-lhs-bindings` + `augment-lhs`) are wired correctly through
`core/production-summary` → `serialize/serialize-lhs`, and the end-to-end
serialized output validates against `api/LhsCondition`.

I verified (live REPL against the `CLARA_HOME` checkout, port 51332):

- `make test` → **250 tests / 1651 assertions, 0 failures / 0 errors**.
- `make format-check` → clean; `make lint` → 0 errors / 0 warnings.
- `conditions.clj` loads under `*warn-on-reflection*` with no warnings.
- `analyze-lhs-bindings` output for fact / accumulator / `:test` / simple
  `:not` / `:exists` / `:or` conditions matches `clara.rules.compiler`'s
  `sort-conditions` + `condition-to-node` bookkeeping.
- A serialized rule detail (`collect-doc-meta`) passes `s/validate` against
  `api/LhsCondition`, carrying both accumulator info and leaf binding info.

There is **one high-severity robustness defect** (an infinite loop on
unsatisfiable input), and a cluster of medium/low issues around compound
negations, non-determinism, and the UI/schema treatment of the new binding
keys. None block the current happy path (a compiler-validated rulebase), but
the infinite loop in particular should be fixed before `analyze-lhs-bindings`
is treated as a general-purpose public API.

---

## 2. What was reviewed

| File | Notes |
| --- | --- |
| `server/src/clara/server/tools/graph/conditions.clj` | Primary focus — all analysis passes |
| `server/src/clara/server/tools/graph/core.clj` | `production-summary` now runs `augment-lhs` |
| `server/src/clara/server/tools/graph/serialize.clj` | `serialize-accumulator` + `serialize-node` hook |
| `server/src/clara/server/graph/api.clj` | `AccumulatorInfo`, `LhsCondition` change |
| `server/test/clara/server/tools/graph/conditions_test.clj` | New tests |
| `server/test/clara/server/tools/graph/serialize_test.clj` | Updated accumulator case |
| `ui/src/lib/types/api.ts` | `AccumulatorInfo`, `accumulator?` retype |
| `ui/src/lib/components/rulebase/LhsCondition.svelte` | `form` + `Initial Value` badge |
| `$CLARA_HOME/src/main/clojure/clara/rules/compiler.clj` | Ground truth for the reimplementation |

---

## 3. Correctness findings

### C1 — `sort-tagged` drops the compiler's unsatisfiable guard → infinite loop (HIGH)

`conditions.clj:87-109` (`sort-tagged`) reimplements `com/sort-conditions`
but omits the `(when (empty? newly-satisfied) (throw ...))` guard
(`compiler.clj:820-838`).

When no remaining condition is satisfiable, the reimplementation does:

```clojure
(recur (into sorted newly) updated still)
```

with `newly` empty, `updated` unchanged, and `still` = `remaining`. That is a
non-terminating loop — no progress, no error.

Today this cannot be triggered through `->rulebase-analysis`, because the
only production LHS that reaches it has already been compiled (and therefore
already passed the compiler's own guard). But `analyze-lhs-bindings` is a
**public** function whose docstring says it works on "a production's raw LHS"
— any caller passing a malformed / not-yet-compiled LHS hangs the process
instead of getting an `ex-info`.

**Suggested change:** mirror the compiler's guard exactly:

```clojure
(when (empty? newly)
  (let [unsatisfiable (set/difference
                       (apply set/union (map (comp :unbound :classified) still))
                       bound)]
    (throw (ex-info "Using variable that is not previously bound"
                    {:unbound-variables unsatisfiable}))))
```

and add a test that asserts the throw (mirroring the compiler's message
semantics). If you intentionally want a softer contract than the compiler,
at minimum throw a descriptive `ex-info` rather than looping.

---

### C2 — Compound negation (`[:not [:and …]]` / `[:not [:or …]]`) silently drops binding info AND diverges from the compiled network (MEDIUM)

Two distinct problems, both live in the compound-negation path:

1. **Silent drop.** For `[:not [:and a b]]`, `disjunction-branches` DNF-expands
   into two branches `[:not a]` and `[:not b]`, both attached at the same
   `attach-path` (`[i 0]`, the negation's single child slot). `path-index`
   (`conditions.clj:243`) builds `{path summary}` via `into`, so the second
   record overwrites the first. Worse, `[i 0]` is the `[:and …]` **vector**,
   and `walk-augment` only merges into **maps** (`conditions.clj:258-276`) —
   so nothing lands at all. Verified in the REPL: `augment-lhs` on
   `[:not [:and …]]` returns the group unchanged with no binding info on any
   nested leaf.

2. **Divergence from the compiler.** The compiler does *not* keep compound
   negations in place: `get-complex-negation` (`compiler.clj:974`) replaces
   `[:not [:and/:or/:not …]]` with `[:not {:type NegationResult …}]` and
   generates a helper production. The analyzer does not replicate this, so
   its `:used-bindings` / `:join-bindings` / `:new-bindings` for these
   conditions describe a network shape that never existed.

Simple `[:not leaf]` works correctly and is covered by the tests; compound
negation is the gap. The plan defers complex `:not`, but the current behavior
is *silent*, which is the problematic part.

**Suggested change (near-term, defer-safe):** treat compound negation the same
as `:or` / `:exists` — detect it in `attach-path` and return `nil` so it is
explicitly *not* augmented, then add a test pinning that the group is left
untouched:

```clojure
(defn- compound-negation? [condition]
  (and (#{:not 'not} (first condition))
       (sequential? (second condition))
       (#{:and :or :not} (first (second condition)))))
```

Also consider asserting uniqueness in `path-index` (throw or `log/warn` when
two records share an `attach-path`) so future silent overwrites become loud.

Long-term (only if compound negation is ever in scope): replicate
`get-complex-negation`'s NegationResult substitution so the binding walk
matches the compiled network.

---

### C3 — `:exists` expansion is non-deterministic (LOW)

`expand-exists` (`conditions.clj:128`) mirrors the compiler's gensym:

```clojure
:result-binding (keyword (gensym "?__gen__"))
```

The compiler does the same, so this is *faithful* — but
`analyze-lhs-bindings` is public and returns its records directly, so two
calls on the same `:exists` LHS yield different `:condition` / `:result-binding`
values. It never leaks into the augmented output (`:exists` has `attach-path`
nil), so this is only a determinism/testability concern for direct callers.

**Suggested change:** if determinism of the public return value matters, derive
a stable placeholder from the origin path (e.g. `(keyword (str "?__exists__"
(pr-str origin)))`) instead of `gensym`. Otherwise, document that `:exists`
records contain a fresh gensym and are not part of the stable contract.

---

### C4 — `accumulator-info` re-evaluates per occurrence, and the `:prod-ns nil`
message is slightly misleading (LOW)

- `enrich-accumulators` (`conditions.clj:60`) prewalks the LHS and calls
  `accumulator-info` once per accumulator map. The same accumulator form
  repeated across rules is re-`eval`'d each time — the roadmap's open question
  #1 (caching). No action required now, but confirm it is still an accepted
  cost before large-rulebase use.
- `accumulator-info` throws "production namespace not loaded" when
  `(some-> prod-ns find-ns)` is nil. For a production whose ns simply could
  not be derived (`prod-ns` nil), the message is wrong ("not derivable" ≠
  "not loaded"). Minor; consider distinguishing `(nil? prod-ns)` from
  `(not (find-ns prod-ns))`.

---

## 4. Coding-standards findings (Clojure engineering)

### S1 — Path construction is duplicated and fragile (MEDIUM)

The origin/attach/merge contract is split across `flatten-tagged` (top-level
`[i]`, `:and` children `[i j]`), `attach-path` (`:not` child `(conj origin 0)`),
and `walk-augment` (vector `rest` re-indexed with `map-indexed`). Each encodes
indexing assumptions independently; drift between them fails silently (C2 is
exactly this failure mode). There is no assertion that a non-nil `attach-path`
actually lands on a map.

**Suggested change:** extract a single `child-paths` helper shared by the three
sites, or add a post-`augment-lhs` debug assertion (e.g. a `:debug` path that
checks every `attach-path` in `path-index` resolved to a map during
`walk-augment`). Even a comment cross-referencing the three functions would
help future maintainers.

### S2 — Two names for one value: squash `:join-bindings` → `:binding-keys` at the boundary (LOW)

Both names come from clara-rules itself for the **same** set:

- `clara.rules.compiler/condition-to-node` emits `:join-bindings`
  (`compiler.clj:924`) — the transient compiler-description key.
- `clara.rules.compiler/compile-node` destructures that same value and passes
  it positionally into `eng/->RootJoinNode` / `->HashJoinNode` /
  `->AccumulateNode`, whose record field is `:binding-keys`
  (`engine.clj:559/612/1027`). So clara renames `:join-bindings` →
  `:binding-keys` at its own compiler→engine boundary; the value is
  byte-identical (`set/intersection cond-bindings parent-bindings`).

Our code inherited both names by accident: `conjunction-binding` copies
`condition-to-node`'s output verbatim (`:join-bindings`), then `path-index`
renames it to `:binding-keys` for the serialized/augmented output so it
correlates with the engine node field (`nodes/->nodes`). There is **no
functional reason** to keep both in our public API — the rename only has a
weak rationale (the observable name is `:binding-keys`), which justifies
**one** name, not two.

**Suggested change:** touch the compiler's key exactly once, in
`conjunction-binding`, and use `:binding-keys` everywhere downstream:

```clojure
(cond-> {:condition conjunction
         :origin origin
         :attach-path attach-path
         :used-bindings (:used-bindings node)
         :binding-keys (:join-bindings node)   ; compiler key read once
         :new-bindings (:new-bindings node)
         :ancestor-bindings ancestor-bindings
         :all-bindings all-bindings}
  ...)
```

Then drop `:join-bindings` from the record entirely, have `path-index`
destructure `:binding-keys` directly (no rename), and update the one
`conditions_test` assertion that reads `(:join-bindings r)`. Keep
**`:binding-keys`** (not `:join-bindings`): it is the engine's persisted field
name, it is already what the serialized output and roadmap use, and the
compiler's name is a transient implementation detail. Do not confuse this with
`:join-filter-join-bindings`, which is a distinct value and keeps its own name.

### S3 — Standards conformance (PASS)

No violations found in `conditions.clj`:

- No reflection warnings (`*warn-on-reflection* true` load was clean).
- `clj-kondo --lint` → 0 errors / 0 warnings.
- `cljfmt check` → formatted correctly.
- Transducer/`into` usage (`path-index`, `flatten-tagged`) is idiomatic.
- `sort-bindings` uses `(vec (sort-by name bindings))` — deterministic.
- Function decomposition into small `defn-` helpers is good; names are clear
  (`analyze-tagged`, `conjunction-binding`, `walk-augment`, `enrich-accumulators`).

---

## 5. Consumer-side findings

### U1 — Binding info needs a consolidated wire shape + a dedicated UI element (MEDIUM)

Binding info currently rides flat on each leaf map — `:used-bindings`,
`:binding-keys`, `:new-bindings`, `:ancestor-bindings`, `:all-bindings` — and is
rendered by `LhsCondition.svelte`'s generic `Object.entries` fallback as raw
JSON rows (e.g. `used-bindings  ["?app-id"]`). That is accidental noise, and it
surfaces two structural problems: (1) five untyped catch-all keys on the wire,
two of which are cumulative and O(n²); (2) no dedicated UI treatment, so the
group order comes from Clojure map key order rather than a chosen order.

Agreed direction: bindings are worth showing, but only a small, cheap,
per-condition subset, in a dedicated collapsible element — not the JSON-blob
fallback.

**Which groups to keep.** All are sets in-memory, sorted vectors on the wire:

| Group | Meaning | Keep? |
| --- | --- | --- |
| `used-bindings` | every variable the condition references (constraints + `fact-binding` + join-filter vars) | **yes** |
| `binding-keys` | variables already bound *upstream* that this condition joins on (its input join keys) | **yes** |
| `new-bindings` | variables this condition's constraints introduce for the first time (excludes `fact-binding`/`result-binding`) | **yes** |
| `ancestor-bindings` | cumulative bindings available *before* the condition | **no** — O(n²), low reading value |
| `all-bindings` | cumulative bindings available *after* the condition | **no** — redundant |

On `binding-keys`: it is the condition's *input* join key — which upstream
bindings it matches against — not something the condition produces. That is why
it is non-empty on a join/accumulator leaf even when `new-bindings` is `[]`. It
is the other half of the "new vs joined" split that motivated this analysis
(plan §3.6–3.7), so keep it next to `new-bindings` rather than dropping it.

**Wire shape.** Nest the three kept groups under a single `:bindings` key so
the generic renderer can ignore it wholesale and the UI can show/hide one
object:

```json
"bindings": {
  "used-bindings": ["?app-id"],
  "binding-keys":  ["?app-id"],
  "new-bindings":  []
}
```

`augment-lhs`/`path-index` then stop emitting the five flat keys and emit the
single `:bindings` map (keeping only the three groups). Dropping
`ancestor-bindings` / `all-bindings` also fixes the O(n²) payload concern.

**Determinism.** Each group's vector is already sorted server-side
(`sort-bindings` → `sort-by name`), so per-group order is stable. The *group*
order must be fixed by the UI element (hard-coded: used → new → binding-keys,
or whatever display order you prefer), not derived from `Object.entries` /
`Object.keys` — Clojure map key order is not a logical order.

**UI.** In `LhsCondition.svelte`, add `:bindings` to `ignoredKeys` (kills the
JSON-blob fallback) and render a collapsible "Show bindings" affordance — the
same pattern as the existing "Show expression" — listing the groups in fixed
order as small labels/chips (one per binding). It is a list of named binding
groups, not a code block.

**API/TS.** Type the nested shape explicitly instead of the `s/Keyword s/Any`
catch-all (roadmap item #3):

```clojure
;; api.clj — serialized layer
(s/optional-key :bindings)
{:used-bindings [s/Keyword]
 :binding-keys  [s/Keyword]
 :new-bindings  [s/Keyword]}
```

```ts
// api.ts
bindings?: {
  'used-bindings': string[];
  'binding-keys': string[];
  'new-bindings': string[];
};
```

(Pre-JSON the values are keywords, so `[s/Keyword]` is correct at the
`handle-get-rule` validation point; the UI sees strings after JSON encoding.)

### U2 — No internal (pre-serialization) schema exists for the rulebase analysis (LOW/MEDIUM)

`api/LhsCondition` / `AccumulatorInfo` correctly model the *serialized* layer
(`:form s/Str` is right there — `serialize/serialize-accumulator` has already
rendered it by the time a handler validates). But there is **no** schema for
the in-memory representation: `core.clj`, `conditions.clj`, `serialize.clj`,
`nodes.clj`, and `fact-types.clj` are all unschematized. The repo's
`s/defschema` uses live only at boundaries (`api.clj`, `client.clj`/
`server.clj` inputs, `analyze/*`, `annotations/*`). This change added new
internal structures — raw-form accumulator info, per-leaf binding maps,
origin-tagged records — and there was no internal schema to update.

The internal and serialized shapes differ at exactly the points this change
touches:

| Field | Internal (in-memory) | Serialized (API) |
| --- | --- | --- |
| accumulator `:form` | raw form (list/symbol) | `s/Str` |
| `:used-bindings` / `:binding-keys` / … | **sets** of keywords | sorted **vectors** |
| `:type` | raw `Class`/symbol/keyword | `TypeReference` map |
| `:origin` / `:attach-path` / `:condition` | present on `analyze-lhs-bindings` records | absent (merged away) |

**Suggested change:** add small co-located internal schemas (following the
repo's `s/defschema`-at-the-boundary convention), deliberately raw-shaped and
named to signal the layer:

```clojure
;; conditions.clj — in-memory (pre-serialization) shapes
(s/defschema AccumulatorInfo
  {:form s/Any              ; raw form, not the API's rendered string
   :some-initial-value? s/Bool})

(s/defschema LhsBindingRecord
  {:condition s/Any
   :origin [s/Int]
   :attach-path (s/maybe [s/Int])
   :used-bindings #{s/Keyword}
   :binding-keys #{s/Keyword}
   :new-bindings #{s/Keyword}
   :ancestor-bindings #{s/Keyword}
   :all-bindings #{s/Keyword}
   (s/optional-key :result-binding) s/Keyword
   (s/optional-key :fact-binding) s/Keyword
   (s/optional-key :join-filter-join-bindings) #{s/Keyword}})
```

`ancestor-bindings` / `all-bindings` stay in the internal record (the walk
needs them for propagation) but are dropped at the serialization boundary —
see U1 for the wire subset.

Two caveats:

- **Library.** The repo uses Prismatic Schema (no Malli dependency). Use
  `schema.core` for consistency; introducing Malli is a separate project
  decision, not part of this fix.
- **Cost.** Do not `s/validate` the full analysis map on every build (3k+
  rules). Validate the small leaf-level shapes at their construction boundary,
  and gate any whole-map validation behind dev/test instrumentation.

---

## 6. Documentation staleness

- **Test count.** Roadmap "Verified" says `make test` → 251 tests / 1651
  assertions. Current: **250 tests / 1651 assertions**. Plan §6 still says
  249/1635. Update both.
- **Roadmap "Current state" item 1** says "`:or` / `:exists` group leaves are
  left unaugmented" — accurate, but should also call out the compound-negation
  silent-drop caveat (C2) so it is not mistaken for a supported path.
- The plan §3.7 caveat ("`:or` / `:exists` / complex `:not` need the same
  expansion the compiler does") is the correct statement; the implementation
  should degrade explicitly (C2) rather than silently.

---

## 7. Suggested changes — prioritized

1. **(HIGH)** Add the unsatisfiable guard to `sort-tagged` (C1) + a throw test.
2. **(MEDIUM)** Make compound negation defer explicitly in `attach-path`
   (C2), and assert/warn on duplicate `attach-path`s in `path-index`.
3. **(MEDIUM)** Consolidate binding info: emit one nested `:bindings` map
   (`used-bindings` / `binding-keys` / `new-bindings`), drop
   `ancestor-bindings` / `all-bindings`, and add the collapsible
   "Show bindings" UI element (U1).
4. **(LOW)** Deterministic `:exists` placeholder (C3).
5. **(LOW)** Add the internal (pre-serialization) schemas (U2).
6. **(LOW)** Extract shared path-construction helper (S1) and squash
   `:join-bindings` → `:binding-keys` in `conjunction-binding` (S2).
7. **(LOW)** Refresh test counts in roadmap/plan (D1).
