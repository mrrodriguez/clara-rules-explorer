# Fact-type field usage

Status: **proposal**. Scope: static field-usage analysis of production LHS and
RHS forms, a fact-type-rooted data model, producer/consumer joins, and the
persistence/API changes needed to carry it. UI support is a later phase and is
only noted here.

## 1. Problem

The explorer currently records type-level producer/consumer relationships. It
knows that rule A produces fact type `T` and rule B consumes `T`, but it cannot
say whether B depends on the whole fact or only on specific fields.

Productions often expose more than that:

```clojure
;; Record accessor in a constraint
[Application (= ?app-id app-id)]

;; Keyword access through the whole fact
[::Found (= ?vehicle (:vehicle this))]

;; Destructured map fact
[ApplicationOutcome [{:keys [app-id status]}]
 (= ?app-id app-id)
 (= status :approved)]

;; Accumulator filter plus an opaque reduce over the matched facts
[?docs <- (acc/all) :from [GivenDocument (= ?app-id app-id)]]
```

The goal is not to infer a complete schema. It is to record the **observed view**
of a fact type: which fields productions read, which fields they appear to
produce, and where the evidence stops at `whole fact` or `unknown`.

## 2. Goals and non-goals

### Goals

- Extract field usage from LHS conditions in phase 1.
- Extract produced-field shapes from RHS construction/callsites in phase 2.
- Make the canonical data **fact-type-rooted**, so a type page is a direct read
  and a production page is a join.
- Keep `whole fact` and `unknown` as first-class outcomes.
- Preserve provenance: every observation names the production and the
  condition/callsite that produced it.
- Leave existing `:lhs`, production-index, and `:fact-types` payloads small by
  storing field usage separately.

### Non-goals

- This is not a schema validator, and it never claims a field is absent or
  impossible.
- Fields are not inherited across the type hierarchy. A field observed on
  `::Car` is not a field of ancestor `::Vehicle`.
- Arbitrary helper-function dataflow is out of scope. Unknown stays unknown.
- Java constructor argument-to-property mapping is not attempted.
- Phase 1 does not analyze RHS consumer access to bound facts.
- Phase 3 UI design is not part of this proposal.

## 3. What the rulebase already gives us

`clara.explorer.conditions` normalizes LHS conditions into a homogeneous shape
that preserves the raw fields needed here: `:type`, `:constraints`,
`:original-constraints`, `:args`, `:fact-binding`, `:result-binding`, and
accumulator `:from`.

Clara already has the record/bean field machinery the compiler uses:

- `clara.rules.compiler/get-fields`
- `clara.rules.compiler/field-name->accessors-used`

Verified against the current fixtures:

- `[Application (= ?app-id app-id)]` yields `{app-id .-app-id}`.
- `[ApplicationOutcome [{:keys [app-id status]}] ...]` appears as
  `:args [{:keys [app-id status]}]`.
- `[::Found (= ?vehicle (:vehicle this))]` has no compiler accessor info, but
  the explicit `(:vehicle this)` is directly visible in the constraint form.
- Accumulators retain their `:from` condition, whose constraints are filter
  fields, while the reduce itself remains opaque.

For phase 2, `clara.explorer.analyze.callsite` already traces boundary-call
arguments through local initializers, reads constructor forms, and supports
`:fact-constructors` / `:callsite-resolver-fn` hooks.

## 4. Data model

### 4.1 Type-rooted, not production-rooted

The canonical value should be keyed by fact type:

```clojure
:fact-field-usage
{":loan/applicant"
 {:closed? false
  :known-fields []
  :consumed
  {":app-id" [{:production "my.rules/score-applicant"
               :condition-path [0]
               :polarity :positive
               :access #{:keyword-get}
               :confidence :exact}]}
  :produced
  {":status" [{:production "my.rules/approve-applicant"
               :callsite-id "my.rules:map->ApplicationOutcome:ab12cd34:0"
               :confidence :exact}]}
  :whole
  {:consumed [{:production "my.rules/audit-applicant"
               :condition-path [1]
               :reason :unconstrained-match}]
   :produced []}
  :unknown
  {:consumed [{:production "my.rules/accumulate-documents"
               :condition-path [2 0]
               :reason :accumulator-reduce}]
   :produced [{:production "my.rules/build-outcome"
               :callsite-id "my.rules:-:deadbeef:0"
               :reason :opaque-construction}]}}}
```

The type keys match `:fact-types` keys, so a fact-type view is a join on the
same name. The new value should be a **separate top-level analysis key** rather
than being added to each `:fact-types` entry:

- `:fact-types` remains the hierarchy/usage index.
- Field usage gets its own access pattern and can grow independently.
- The fact-type list endpoint does not pay for field detail it does not show.

Production and dependency views are joins over this map. A production view is
the set of observations whose `:production` matches. A producer→consumer match
adds the field evidence from the two productions' observations.

Do **not** persist a duplicate production-rooted copy. If profiling shows the
join is too expensive, build an in-memory reverse index at analysis/rehydrate
time and never write it to disk.

### 4.2 Field identity

Fields need a kind-explicit identity, in the spirit of
`clara.explorer.serialize/resolve-type`:

```clojure
{:kind :keyword        ; :keyword | :string | :symbol | :record-field | :bean-property | :index
 :name ":app-id"       ; canonical, kind-explicit display name
 :path [":app-id"]}    ; full access path; the first element is the top-level field
```

- A top-level field is the first element of `:path`.
- `get-in` / nested destructuring records the full path, e.g.
  `[":owner" ":city"]`.
- Record basis fields use `:record-field`. For a closed record type, keyword
  access to a basis field should normalize to the same field identity as the
  record member.
- Map/keyword facts keep `:keyword`, `:string`, and `:symbol` distinct; those
  are different map keys.
- Tuple/vector facts use `:index` for positional access.

The exact canonicalization rules belong in one namespace and must be tested.

### 4.3 Observation semantics

Each observation carries:

- `:production` — fully-qualified production name string, in memory and on
  disk. This is the join key; API and production views expand it to a
  `clara.explorer.server.api/ProductionDep` when serving output. Keeping the
  canonical form as a name avoids a second reference representation that would
  need slimming and rehydration.
- `:condition-path` — the normalized LHS path (`[0]`, `[1 0]`, etc.) for LHS
  evidence.
- `:callsite-id` — the existing callsite id for RHS evidence.
- `:polarity` — `:positive`, `:negative` (`:not`), or `:existence` for LHS
  evidence.
- `:access` — `:record-accessor`, `:bean-property`, `:destructure`,
  `:keyword-get`, `:get`, `:get-in`, `:java-field`, etc.
- `:confidence` — `:exact`, `:partial`, or `:unknown`.
- `:reason` — for whole/unknown observations, e.g.
  `:unconstrained-match`, `:accumulator-reduce`, `:opaque-construction`.

`whole` means “we have a whole-fact dependency but no field-level evidence”. It
does **not** mean “all fields are read”. `unknown` means “we know a dependency
exists but the form is opaque”.

### 4.4 Open versus closed types

For record/bean/class types, include the known field universe:

```clojure
{:closed? true
 :known-fields [{:kind :record-field :name "app-id" :path ["app-id"]} ...]}
```

For keyword/map/tuple facts, `:closed?` is false and `:known-fields` is empty.
Even a closed type’s `known-fields` is class metadata, not proof that a producer
supplies every field or that a consumer requires all of them.

### 4.5 Hierarchy rule

Field aggregation is **exact type only**. Concrete example:

```clojure
(derive :a :x)
(derive :b :x)

;; In production my.rules/use-x
[:x [{:keys [v]}] (= v ?v)]
```

The condition is written on `:x`, so `:v` is recorded under `:x` only:

```clojure
:fact-field-usage
{":x"
 {:consumed
  {":v" [{:production "my.rules/use-x"
          :condition-path [0]
          :access #{:destructure}
          :confidence :exact}]}}}
```

It is **not** added to `:a` or `:b`. Type-level usage still follows the
hierarchy: because an `:a` or `:b` fact satisfies an `:x` condition, the
existing `used-by-rules` expansion lists `my.rules/use-x` under all three:

```clojure
:fact-types
{":x" {:used-by-rules [{:name "my.rules/use-x" ...}]}
 ":a" {:used-by-rules [{:name "my.rules/use-x" ...}]}
 ":b" {:used-by-rules [{:name "my.rules/use-x" ...}]}}
```

If another production inserts `:a` with fields `:v` and `:w`, the producer→
consumer match can carry the edge-specific field evidence (`:a` produces `:v`,
`:w`; the condition on `:x` consumes `:v`) without adding `:v` to `:a`’s
consumed-field model or `:w` to `:x`’s.

- A consumer condition on `T` contributes field observations to `T`.
- A producer inserting `S` contributes produced observations to `S`.
- The existing hierarchy still expands `used-by-*` and `inserted-by-*`, but
  field observations are not copied to ancestors or descendants.
- A dep-graph match may show a producer of `S` and a consumer of ancestor `T`
  together with their respective field sets. That is edge evidence, not a claim
  about `T`’s field model.

### 4.6 Open, extensible data structures

Every new analysis structure is an **open map with named keys**. Do not use
positional tuples or closed records for analysis data. A later extension adds a
key; it does not change the shape existing readers already match.

```clojure
;; Good: every part is named, and a new key is additive.
{:production "my.rules/use-x"
 :condition-path [0]
 :access #{:destructure}
 :confidence :exact
 ;; later: :role :join
 }

;; Bad: positional, so adding or reordering a part breaks readers.
["my.rules/use-x" [0] :exact]
```

Vectors are allowed only as ordered collections of maps (observations, fields,
paths) or as value paths. They are never positional record types. Schemas for
these structures should remain open maps so a new observation key or type-entry
section is backward-compatible. This applies to every proposed structure: type
entries, field tokens, observations, the `:whole` / `:unknown` sections,
dependency `:match` maps, and any derived production index.

## 5. Phase 1 — LHS consumer field extraction

Implement a new namespace, e.g. `clara.explorer.fact-fields`, with one
normalized extraction pass over the normalized LHS.

### 5.1 Fact leaves

For each fact leaf, use `(or :original-constraints :constraints)` so fields
moved to join filters are not lost.

Extract:

1. **Record/bean accessors** via
   `clara.rules.compiler/field-name->accessors-used` on the condition type.
2. **Argument destructuring** from `:args`:
   - map destructuring `:keys`, `:strs`, `:syms`, explicit `{local :key}`,
     nested maps, `:as`, `:or`;
   - vector destructuring as positional fields;
   - a bare symbol as a whole-fact binding.
3. **Explicit whole-fact access** rooted at `this` or the condition’s
   `:fact-binding`:
   - `(:key this)`, `(:key ?fact)`
   - `(get this :key)`, `(get-in this [:a :b])`
   - `(contains? this :key)`, `(select-keys this [:a :b])`
   - simple `(-> this :a :b)` / `(some-> this :a)` forms if cheap to support.
4. **Whole-fact use** when a condition has no field evidence but still depends
   on the fact (`:reason :unconstrained-match`).

Do not attribute a keyword access to the fact unless the root is provably
`this`, the condition’s fact binding, or a binding tracked to that fact type.
Skip or mark unknown when a `let`/`fn` shadowing form makes the root ambiguous.

### 5.2 Tests and binding flow

Thread a binding→type map through the LHS walk:

- A fact `:fact-binding` records `?f -> T`.
- A `:test` condition that accesses `(:x ?f)` contributes a field observation
  to `T` with the test condition’s path.
- A `:test` that uses `?f` as a whole adds a whole-fact observation.

### 5.3 Groups, negation, and existence

- `:and` unions observations.
- `:or` unions branch observations; branch-local paths stay distinguishable.
- `:not` / `:exists` contribute observations with `:polarity :negative` /
  `:existence`. They are real reads, not positive requirements.
- Nothing inside negation/existence escapes as a binding, matching the existing
  binding walk.

### 5.4 Accumulators

For `:from`:

- Constraints contribute **filter fields** to the source fact type.
- Add an `:unknown` observation with `:reason :accumulator-reduce` for the
  source fact type: the accumulator function may inspect fields beyond the
  filter.
- `:result-binding` is an opaque result. Do not attribute its field accesses to
  the source fact type.

Concrete example:

```clojure
[?docs <- (acc/all) :from [GivenDocument (= ?app-id app-id)]]
```

This records `:app-id` as a consumed filter field on `GivenDocument`, plus an
unknown reduce observation on `GivenDocument`:

```clojure
:fact-field-usage
{":loan/given-document"
 {:consumed
  {":app-id" [{:production "my.rules/collect-docs"
               :condition-path [0 0]
               :access #{:record-accessor}
               :confidence :exact}]}
  :unknown
  {:consumed [{:production "my.rules/collect-docs"
               :condition-path [0 0]
               :reason :accumulator-reduce}]}}}
```

Field accesses on the accumulator result `?docs` do not contribute field
observations to `GivenDocument`.

Known accumulators such as `acc/all` or `acc/count` may get special handling
later. Phase 1 should default to unknown rather than guess.

## 6. Phase 2 — RHS producer field extraction

Extend the existing callsite analysis; do not create a second parser for RHS
forms.

For each boundary argument whose fact type resolves, infer produced fields from
the traced form:

- `->Record` positional arguments mapped to the record basis.
- `map->Record` with a literal map argument: keys present in the map.
- Map literals, including `(with-meta {...} {:type ...})`.
- Simple literal `assoc` / `merge` / `dissoc` / `select-keys` chains.
- Local initializers already traced by
  `clara.explorer.analyze.callsite/trace-local-form`.

Each inference has a confidence:

- `:exact` — record constructor or fully literal map.
- `:partial` — some keys statically known, some dynamic.
- `:unknown` — helper call, dynamic key construction, Java constructor mapping,
  or an unresolved fact type.

Attach produced fields to the callsite entry and resolve type-to-field
association only when the type was resolved for that callsite. A rule’s
`:clara-rules/insert-types` declaration alone should not be used to guess that a
nearby map literal belongs to that type.

Extend the existing hooks backwards-compatibly:

```clojure
;; :callsite-resolver-fn or :fact-constructors/:type-resolver-fn may return
{:resolved-types [T]
 :field-shapes {T {:fields [...] :confidence :exact}}}
```

Old resolvers that return only `:resolved-types` continue to work.

## 7. Joins, dependency matches, and API

### 7.1 Fact-type view

Join `:fact-types` and `:fact-field-usage` by type name. The detail response can
add:

```clojure
{:consumed-fields [...]
 :produced-fields [...]
 :whole-fact-consumers [...]
 :unknown-consumers [...]
 :closed? false
 :known-fields []}
```

The list response stays lightweight.

### 7.2 Production view

Derive a production view by selecting observations whose `:production` matches.
This is a pure join; it is not stored twice. If needed, cache the reverse index
per analysis value rather than persisting it.

For example, from the type-rooted observations above, a production view for
`my.rules/use-x` is:

```clojure
{:production "my.rules/use-x"
 :consumed
 {":x" {":v" [{:condition-path [0]
                 :access #{:destructure}
                 :confidence :exact}]}}
 :produced {}}
```

The observation is still stored once under `:x`; this map is the join result.


### 7.3 Dependency matches

Extend the existing `:match` entries in upstream/downstream production deps with
field evidence:

```clojure
{:producer-type TypeReference
 :consumer-type TypeReference
 :producer-fields [...]
 :consumer-fields [...]
 :overlap [...]
 :granularity :fields | :whole | :unknown | :mixed}
```

Concrete hierarchy example: a rule inserts `:a` with fields `:v` and `:w`, and
`my.rules/use-x` consumes `:x` with field `:v` from the example above. The match
can carry:

```clojure
{:producer-type {:name ":a" ...}
 :consumer-type {:name ":x" ...}
 :producer-fields [{:name ":v" ...} {:name ":w" ...}]
 :consumer-fields [{:name ":v" ...}]
 :overlap [{:name ":v" ...}]
 :granularity :fields}
```

That does not add `:v` to `:a`’s consumed-field view or `:w` to `:x`’s; it is
only evidence for this producer→consumer edge.

Matches still follow the existing type/hierarchy rules. A consumer field with
no observed producer is **informational only**; it is not an error, because the
view is open-world and producers may be outside the analyzed unit.

## 8. Persistence and artifact impact

Persist the canonical type-rooted value as a new top-level analysis key:

```clojure
:fact-field-usage {type-name entry ...}
```

Add it as a seventh part file, e.g. `fact-field-usage.edn`, rather than folding
it into `fact-types.edn`. This keeps the existing hierarchy file stable and
avoids making every fact-type read pay for field detail.

Required changes:

- `clara.explorer.artifacts.layout/part-files`
- `clara.explorer.artifacts.schema/AnalysisPartKey`
- `clara.explorer.artifacts.schema/AnalysisParts`
- `clara.explorer.artifacts.parts/placeable-top-level-keys`, `->parts`,
  `<-parts`
- `clara.explorer.artifacts.store` file lists / round-trip tests
- `clara.explorer.artifacts.shared.compose` — union observations per type
- `clara.explorer.artifacts.shared.registry/narrow-analysis` — filter
  observations by the narrowed production namespaces; drop types left empty if
  that keeps the file smaller
- `clara.explorer.artifacts.shared.rehydrate` — rebuild any derived production
  index over the persisted name-based observations
- `clara.explorer.artifacts.diff` — decide whether field changes are part of
  the first persistence phase or a follow-up; at minimum, document the choice
- `clara.explorer.artifacts.digest` — optionally add type/field observation
  counts
- `explorer/docs/persisted-artifacts.md` and
  `explorer/docs/internal-analysis-models.md`

`slim` keeps the new key. It is not a reverse relationship that can be derived
from a direction slim already keeps. `:fact-field-usage` should be written once,
in the type-rooted direction, and all production/dep views derived from it.

Composition merges field usage by unioning type entries and observations. Each
observation names its production, so a narrowed unit can be filtered correctly.
A composed recomputation of the dep-graph does not need to recompute field
usage, but the composed production view will naturally include only observations
whose productions survived the merge.

## 9. Phases

### Phase 1 — LHS field usage and the type-rooted model

- Add `clara.explorer.fact-fields` extraction for fact leaves, tests, groups,
  negation/existence, and accumulator filters.
- Add `:fact-field-usage` to the analysis map.
- Add fact-type and production joins, plus field evidence on dependency
  `:match` entries.
- Add the new artifact part and composition/rehydration/narrowing support.
- Add API schemas/types for field observations.
- No RHS producer inference yet.

### Phase 2 — RHS producer field usage

- Extend callsite field-shape inference for constructors, map literals, simple
  `assoc`/`merge` chains, and traced locals.
- Extend callsite resolver hooks with optional field shapes.
- Populate `:produced` observations and producer-side dependency evidence.
- Keep unknown/partial confidence visible.

### Phase 3 — UI support

Note only: once the backend shape is stable, add UI for field views on fact-type
and production detail pages. This proposal does not design that work.

## 10. Tests and verification

Extraction tests should cover:

- record accessors and bean properties;
- `:args` map/vector destructuring, nested destructuring, `:as`, and renames;
- `(:key this)`, `(:key ?fact)`, `get`, `get-in`, `contains?`, `select-keys`;
- `:test` constraints referencing an earlier fact binding;
- `:and` / `:or` / `:not` / `:exists` paths and polarity;
- accumulator filter fields versus opaque reduce;
- `:original-constraints` preserving fields moved to join filters;
- whole-fact and unknown fallbacks.

Data-model tests should cover:

- exact-type aggregation and no field propagation across the hierarchy;
- production joins;
- open versus closed types;
- deterministic field identity and ordering;
- deduplication of repeated observations.

Persistence tests should cover:

- `parts/->parts` / `<-parts` round trip with the new part;
- slim keeps `:fact-field-usage`;
- rehydrate rebuilds the derived production index from the name-based
  observations;
- compose unions and narrows observations correctly;
- golden artifact regeneration is byte-identical on a second run;
- diff behavior matches the chosen scope.

Verification from `explorer/`:

```bash
make test
make lint
make reflection-check
make format-check
make regen-artifacts
```

The last command should leave `git status` clean on a second run.

## 11. Risks and open questions

- **Field identity** — the record-member versus keyword-access normalization
  needs one explicit rule and tests, especially for records where both work.
- **Condition paths** — path stability depends on the normalized LHS shape.
  They are evidence locations, not public identifiers.
- **Field data size** — measure on the perf fixture before deciding whether the
  type-rooted file needs any further splitting.
- **Production-view performance** — a full join per request may be acceptable;
  an in-memory reverse index is the fallback, never a second persisted shape.
- **Whole-fact semantics** — phase 1 can mark whole/unknown at the condition
  level. Detecting later whole-fact binding flow through tests and RHS is a
  refinement.
- **Accumulator result bindings** — keep opaque until there is a reason to
  special-case known accumulators.
- **RHS consumer access** — reading fields from RHS uses of bound facts remains
  out of scope for both phases unless added later.
- **Diff** — field-usage changes may be useful but are not required for the
  first backend phase; decide before artifact regeneration.
- **UI** — deferred to phase 3 by design.

## 12. Acceptance

Phase 1 is acceptable when:

- LHS field observations appear under `:fact-field-usage` for the loan fixtures;
- a fact-type detail can show consumed/produced/whole/unknown fields without
  reading production-rooted field copies;
- a production view can join to the same observations;
- dependency matches carry field evidence;
- records/beans expose a closed field universe, while keyword/map types remain
  open;
- fields do not propagate across the hierarchy;
- the new artifact part round-trips, composes, narrows, and rehydrates;
- all verification commands above are green.

Phase 2 is acceptable when the same holds for produced fields inferred from
standard RHS constructor/map forms, with opaque helpers and Java constructor
argument mapping explicitly remaining unknown.
