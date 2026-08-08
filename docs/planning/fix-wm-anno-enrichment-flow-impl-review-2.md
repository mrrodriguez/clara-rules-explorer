# Implementation Review 2 — WM Annotation Enrichment Flow

**Scope:** commits `4743f0d` ("impl 1") and `162e0c3` ("no need for canon
anno str fn") against `fix-wm-anno-enrichment-flow-plan-final.md`.
**Verified:** `make test` 196 tests / 1375 assertions green; format-check
and lint clean.
**Supersedes:** `fix-wm-anno-enrichment-flow-impl-review-2-draft.md` (kept
for discussion history only — do not action from it).

Completed work is tracked in the progress doc and not repeated here. This
document lists only what still needs to happen, plus the type-representation
contract that came out of the review discussion and must be preserved in
project docs.

---

## Action items (ordered)

### A1. Amend plan-final to match the implemented comparison design

Plan-final §4, principle 2, and test case 6 still mandate
`canonical-type-str` (6 references), which was deliberately deleted. Record
the decision in the plan:

- `serialize/resolve-type` is the comparison canonicalizer (with the rule's
  namespace). Rationale: it is kind-explicit — keyword `:thing` and string
  `"thing"` never conflate, and both are legitimate fact types under a
  pluggable `fact-type-fn`. Class ↔ class-name-symbol convergence already
  works via ns-aware resolution or the fully-qualified fallback.
- D7 (resolver unification) is obsolete as planned: the direction was
  "serialize reimplemented on a resolver in annotations"; the implemented
  direction is the reverse (annotations compares with
  `serialize/resolve-type`) and is accepted. Reword or drop D7.

### A2. Resolve D1 / D2 explicitly — Phase 2 was marked complete without them

The progress doc's "Deferred to Phase 2" list deferred these _to_ Phase 2;
the Phase 2 section then closed without them and they appear in no
"Remaining" list. Either implement or amend the plan to descope — but keep
them tracked until one of those happens:

- **D1 (plan §1b): memory layer as delta.** server.clj still enriches
  against `{}` and appends a full memory layer; `analyze/->memory-layer` is
  unused. Correctness-relevant consequence: `rule-delta`'s
  `:clara-rules/no-output-types` tombstone clearing never runs, so a rule
  statically asserted as no-output that observably inserted at runtime
  keeps the tombstone — a sink-misclassification risk. If D1 is descoped,
  the demo re-scrape must specifically check sink classification of the
  dynamic-insert rules.
- **D2 (plan §1a): per-file source layers** (`build-static-layers` rename,
  per-file `ann.merge/->layer` provenance, single props fold).

### A3. Use the ns-aware canonicalizer for merge dedupe (merge-semantics defect)

Merge dedupe uses the namespace-less `ann/type-str` at four sites
(`merge-type-vec` — merge.clj:218-219; `derive-rule-annotation` —
merge.clj:404,410). "Cheap" is not a justification for incorrect merge
semantics, and the namespace is not actually missing: merging operates per
rule-name key, so `(ann/fq-name->namespace rule-name)` is available at
every call site (`derive-conclusions`:449 and `derive-with-provenance`:458
have `rule-name` directly in scope; `merge-type-vec`'s callers sit one
level below the rule-keyed merge). Current incorrect behaviors:

1. Unqualified props symbol never dedupes against its Class
   (`'ApplicationOutcome` → `"ApplicationOutcome"` vs Class →
   `"my.ns.ApplicationOutcome"`) — duplicate representations of one type
   in merged `:insert-types`.
2. Kind conflation: keyword `:thing` and string `"thing"` collapse to one
   entry — distinct fact types silently merged.
3. Vector types canonicalize via `str`, losing inner kind tags
   (`[:a "x"]` ≡ `[:a x]`).

Fix: thread rule-ns into both functions and dedupe with `(partial
serialize/resolve-type rule-ns)` — the same canonicalizer the comparison
sites use. `type-str` then has no remaining callers; delete it (this
retires the docstring overclaim previously tracked separately). Add
merge-level tests: unqualified symbol vs Class dedupe; keyword vs string
distinction.

### A4. Store raw objects in `:fact-instance-derived-types` (representation fix)

Currently the two producers in analyze.clj (~line 885 and ~line 961)
display-format the values via `resolve-fn` before storing — the only place
in the pipeline where annotation data is demoted to strings. The same code
path keeps `:insert-types` raw ("never demoted to name strings") while
demoting derived-types ten lines later. Hazards: a keyword `:x` becomes
the string `":x"` (kind survives only as text convention); the value can
never be compared against raw types (it quote-mismatches under
`resolve-type`); EDN persistence would round-trip keywords as strings.

The strings exist only for JSON display convenience. Fix:

- Store the raw objects (sorted by `resolve-fn`, not mapped — the
  `:insert-types` pattern).
- Format at the JSON boundary instead: the rule-detail serializer already
  transforms callsite `:resolved-types` (raw symbols) into `{name, id,
  known}` TypeReference objects — extend the same transform to
  `fact-instance-derived-types` so the emitted JSON is unchanged (no UI
  impact).
- Update `api.clj` `DynamicDetectionInfo` (`[s/Str]` → boundary shape),
  and the analyze_test/annotations_test assertions that expect strings.
- EDN: nothing persists memory-enriched annotations today; when a writer
  exists it applies the contract's Class→symbol mapping, and derived-types
  then match `insert-types` format.

### A5. Add the two missing tests

- **`:reuse` reload regression** (the B1 fix from review 1 is unpinned):
  swap with `{:enrichment :reuse}` so the spec is stored, call
  `reload-annotations!`, assert the annotations are unchanged.
- **File-backed reload** (plan test case 4): swap with a spec whose
  `:source` is a temp EDN file, modify the file, reload, assert the change
  is re-read. The implemented reload test covers auto-detect specs only.

### A6. Fix the progress doc's verification counts

Claims "197 tests, 1383 assertions"; actual is **196 / 1375**.

### A7. Write the type-representation contract into project docs

`server/docs/rule-annotations.md` currently documents
`:clara-rules/insert-types` as "vector of symbols" — incomplete. Add the
contract below (or a link to it) there, since sidecar authors need it.

### A8. Ensure this is an expected test WARN output not a problem now introduced

`WARN: serialize-type-ref received a nil-resolving type token: nil`
(serialize.clj:84) appears in the test run. Asserted pre-existing
(serialize.clj was untouched by this work) but never confirmed against the
pre-change suite. Confirm and ticket separately.

---

## Type-representation contract for `*-types` annotation values

Definitive statement of how fact types are represented in
`:clara-rules/insert-types`, `:clara-rules/retract-types`, and related
`*-types` keys, and how they are compared and merged. **A7 is the action
item to land this in project docs.**

### In EDN (sidecar files, persisted annotations)

Only EDN literals — a fact type keeps its literal form:

| In-memory fact type | EDN representation                                       |
| ------------------- | -------------------------------------------------------- |
| Class instance      | **symbol** (class-name symbol, qualified or unqualified) |
| String              | string                                                   |
| Keyword             | keyword                                                  |
| Vector              | vector                                                   |
| Symbol (non-class)  | symbol                                                   |

The Class→symbol mapping is the single workaround: Classes have no EDN
literal. Rule: if a type has no EDN literal form, it does not go in a
sidecar. Evidence: the generated layer's EDN output uses fully-qualified
class symbols and keywords; rule `:props` use symbols, keywords, and
vectors. String literals for class names are not a supported convention.

### In memory

Compared keys (`:insert-types`, `:retract-types`) carry **raw fact-type-fn
objects** end-to-end: EDN literals load unchanged (symbol/keyword/string/
vector); session enrichment contributes raw Classes/keywords/vectors. This
is the form `ancestors-fn` and the graph analysis consume.

All annotation values — compared keys **and** detection-map metadata —
carry raw objects. Display formatting happens only at the serialization
boundaries (JSON out, EDN write). **Current exception:**
`:fact-instance-derived-types` is stored display-formatted (strings) —
tracked as action item A4.

### Comparison (delta, novelty detection, enrichment coverage)

`serialize/resolve-type` with the rule's namespace:

- Kind-explicit: Class → `.getName`; keyword → keeps colon; string →
  `pr-str`-quoted; unresolved symbol → `symbol[...]` wrapper; vector →
  `pr-str` (inner kinds preserved). Distinct kinds never conflate.
- Class ↔ class-name-symbol converges two ways: ns-aware import
  resolution, or the fallback that prints a fully-qualified symbol as its
  name (which equals `.getName`).

### Merge dedupe

Same canonicalizer as comparison: `serialize/resolve-type` with the rule's
namespace, which the merge site derives from the rule-name key. There is
**one** canonicalization for comparison and dedupe alike; display
formatting at the serialization boundary is the same function again.

**Current state:** merge dedupe uses the namespace-less `ann/type-str`,
whose semantics are incorrect in three ways (unqualified symbol ↔ Class
non-convergence, keyword/string conflation, vector inner-kind loss) —
tracked as defect A3, which unifies the two sites on `resolve-type` and
deletes `type-str`.

---

## After these

Run the remaining verification from the progress doc: demo re-scrape
(`make demo-setup && make demo-run`, `pnpm run scrape:demo`), checking
that WM-derived types appear (`AuditTrail`, `ComplianceReview`,
`compliance-review-result`), that the diff adds rather than removes, and —
per A2 — the sink classification of dynamic-insert rules.
