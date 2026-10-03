# Diff two units, production by production

Status: **proposal**. Scope: `explorer/` — `clara.explorer.utils` canonicalization, a new
babashka-loaded `clara.explorer.artifacts.diff`, an `annotations_report.bb` subcommand, and
`docs/persisted-artifacts.md`. No artifact-shape change. Part A changes artifact *values* once, for
units with the name shape it covers.

Two parts, landing in order: **A** makes regeneration with no source change yield equal values for
one more class of generated names; **B** is the diff, which depends on A to stay quiet when nothing
changed.

## 1. Problem

A registry holds several units of one ruleset: the mainline unit, a variant at other coordinates,
a variant generated from a feature branch. The question a reviewer asks of a branch is
production-level: which rules and queries were added or removed, which changed and how (what they
match, what they insert, their RHS), which fact types appeared or disappeared, and which
producer→consumer edges were gained or lost.

Nothing answers that today:

- `federate/diff` compares two federation indexes at **unit** granularity: unit edges, per-type
  producer/consumer *units*, entry points, orphans. It needs a JVM and a built index, and it can't
  say which production changed.
- `annotations_report.bb` answers one unit at a time. Comparing two means running `types`,
  `producers`, `consumers` on each and diffing the text by hand, which only covers the names someone
  thought to ask about.

Every input a production-level diff needs is already on disk in each unit's
`merged-rulebase-analysis/` and annotation layers, readable by babashka.

**Precondition.** A diff is only as useful as regeneration is stable: two units generated from the
same source must compare equal, or every diff carries noise. Reader gensyms are canonicalized
(`clara.explorer.utils/canonicalize-gensyms`). One class of generated name is not, and Part A
covers it.

## 2. Part A — canonicalize digest-suffixed names

### 2.1 The shape

A library macro can mint a local by appending a hex digest of its own input to a symbol. A
data-var macro that defines a var and generates a rule inserting it is the typical case:

```clojure
(defmacro defthing [sym & body]
  (let [local (symbol (str sym (md5-hex (pr-str body))))]
    `(do
       (def ~sym (do ~@body))
       (r/defrule ~(symbol (str sym "-rule"))
         ~'=>
         (let [~local (var ~sym)]
           (r/insert! ~local))))))
```

The digest hashes the *printed* body. When the body contains anything whose printed form depends on
the process (most often `#(…)`, which prints its reader gensyms with JVM-wide counter numbers), the
digest differs from one process to the next with no source change:

```clojure
;; one run
(let [monthly-income5f0c1a7e9b2d4c3a8e6f1b0d9c7a2e4f (var monthly-income)] …)
;; the next
(let [monthly-income0b9e8d7c6a5f4e3d2c1b0a9f8e7d6c5b (var monthly-income)] …)
```

The minted name reaches persisted text at the same two points reader gensyms do:

- `:rhs-form` in `production-details.edn` (Clara's stored form, printed by
  `clara.explorer.serialize`);
- for a unit analyzed from a restored session, a callsite's `:source-str`, because `analyze.synth`
  prints the stored forms as source for clj-kondo. `:source-str` feeds
  `clara.explorer.annotations.callsite/callsite-id`, so the **callsite id churns** between builds of
  the same source, and a curated resolution written against it stops matching.

The root fix belongs in the macro (hash something process-independent). The explorer can't rely
on every library doing that, so it canonicalizes the shape the same way it does reader gensyms.

### 2.2 Detection

An md5 hex digest is always **32 characters**: 128 bits, 4 bits per hex digit. Digest libraries
left-pad with zeros to the full width (`BigInteger.toString(16)` alone would drop leading zeros, so
they pad explicitly), and print lowercase. So the shape is fixed-width and needs no guess about
where the name ends:

> An **unqualified** symbol whose name is longer than 32 characters and whose **last 32 characters**
> are all `[0-9a-f]`. The prefix is everything before them.

Anchoring on the last 32 characters matters. Matching a 32-hex run from the left splits the name in
the wrong place whenever the symbol itself ends in a hex letter: `pay-rate` + digest would match
starting at the `e` of `rate`. Taking exactly the last 32 leaves the prefix whole.

Left alone:

- **qualified symbols.** A qualified symbol names a var, not a local, and a host that stamps
  digests into namespace names does so deliberately;
- **names of exactly 32 hex characters** (no prefix): nothing says that is minted;
- **uppercase hex**, and other digest widths (40 for sha1, 64 for sha256). Keep the width set in one
  def so adding one is a one-line change; widths must be tried longest first, since a 64-hex suffix
  also ends in 32 hex characters.

**False positives.** A hand-written unqualified symbol ending in 32 lowercase hex characters would
be renamed. That's display-only: persisted forms are read, never evaluated. The rename is also
consistent within a form, so it can't merge two distinct symbols.

### 2.3 The canonical name

`<prefix>` followed by the ordinal as 32 zero-padded hex digits (`(format "%032x" ordinal)`), using
the ordinal counter the reader-gensym shapes already share:

```clojure
(let [monthly-income00000000000000000000000000000000 (var monthly-income)] …)
```

This keeps both invariants `canonicalize-gensyms` documents:

- **The canonical name still matches its shape**, so it can't collide with a symbol the walk
  leaves alone. Distinct minted names get distinct ordinals.
- **Idempotent.** A second pass sees the same names in the same first-appearance order and assigns
  the same ordinals.

The prefix survives, so the text still reads as the symbol it was minted from.

### 2.4 Where

- `reader-gensym-name` gains the fifth shape. Rename it `minted-name`, and `canonicalize-gensyms`
  `canonicalize-minted-names`, since neither covers only reader gensyms anymore. Update the call
  sites (`clara.explorer.serialize`, `clara.explorer.analyze.callsite`). Docstrings name both kinds.
- Nothing else moves: both persisted-text points already go through this one function.

### 2.5 Tests

- `utils_test`: the shape table — a prefix ending in a hex letter keeps its last letter; 31 hex
  characters don't match; a bare 32-hex name doesn't match; uppercase doesn't match; a qualified
  symbol doesn't match. Consistent renaming of binding and use, two distinct digests get distinct
  ordinals, a mix with reader gensyms shares one counter, idempotence, and identity (not merely
  equality) when nothing matches.
- `analyze_test`: alongside `test-reader-gensym-counter-independence`, a callsite whose argument is
  a digest-suffixed local yields the same `:source-str` and `:callsite-id` for two different digests
  of the same prefix.
- **Fixture.** Add a `def-digest-fact` macro to `clara.explorer.test.rules.helpers` that mints its
  local from a digest of its printed body, which includes a `#(…)`, and use it once in a rule
  namespace the checked-in example already builds. The golden test then pins the canonical name, and
  regenerating from a different process leaves the bytes unchanged.

### 2.6 Cleanup in the same area

The checked-in example already holds canonical reader gensyms (`resolved__0__auto__`), so two
pieces of `persisted-artifacts.md` and the golden test describe noise that no longer occurs:

- the "byte-level caveat" paragraph under §"The checked-in example";
- `regen-example-test/normalize-gensyms` and the normalization its docstring describes.

Remove both, so the golden test pins exact bytes. Confirm by running the golden test from a fresh
process and from a REPL with the normalization gone.

## 3. Part B — `diff` over two units

### 3.1 Surface

```sh
bb annotations_report.bb <before-dir> diff <after-dir> [--edn] [--rule NAME]
```

`before` then `after`, matching `federate/diff`. Any two unit-shaped directories work: two ruleset
units, a unit and its variant, or two composed units.

### 3.2 Namespace

`clara.explorer.artifacts.diff`, marked `:clara-rules-explorer/bb-loaded true`:

- `read-unit`: `dir` → the values the diff compares, read through `layout/part-files`,
  `layout/artifact-files`, and `layout/expand-merged-annotations`, the same definitions
  `annotations_report.bb` already uses;
- `diff`: a pure function of two `read-unit` values;
- `->text`: the compact rendering.

The script parses arguments and prints. JVM callers can use the same namespace.

### 3.3 Inputs

| File | Read for |
|---|---|
| `rules-inspect-manifest.edn` | provenance (`:source`, `:variant`, `:analysis-run :scope-fields`), scope (`:analysis-run :namespaces`) |
| `merged-rulebase-analysis/meta.edn` | shape: `:slim :dropped` |
| `production-index.edn` | which productions exist, rule vs query, `:ns`, `:lhs-types`, `:insert-types`, `:retract-types`, `:unit` |
| `production-conditions.edn` | `:lhs` |
| `production-details.edn` | `:rhs-form`, `:doc`, `:props` |
| `dep-graph.edn` | `:upstream` per production |
| `fact-types.edn` | which types exist, `:ancestors` |
| merged annotations (expanded) | `:resolution` per rule |

### 3.4 What it reports

- **`:before` / `:after`:** each side's source sha, branch, working tree, `:variant`, and scope
  fields, so the output says what was compared.
- **Shape skew is refused**, as `registry/assert-compatible!` refuses it for a federation: when
  `:slim :dropped` differs, the two units don't hold the same keys and every comparison would be
  suspect. The refusal names both shapes.
- **`:scope`:** namespaces present on only one side, from `:analysis-run :namespaces` (falling back
  to the productions' `:ns`). Productions in those namespaces are listed here, **not** as added or
  removed. Two units built at different coordinates often differ in scope, and that must not read
  as a code change.
- **`:productions`:** `:added`, `:removed`, and `:changed {name #{tag}}`. Tags:

  | Tag | Meaning |
  |---|---|
  | `:kind` | rule ↔ query |
  | `:lhs-types` | the matched type set changed |
  | `:lhs` | same types, different conditions: polarity, constraints, bindings, accumulator |
  | `:insert-types` / `:retract-types` | the produced or retracted type set changed |
  | `:rhs` | `:rhs-form` text changed |
  | `:doc` / `:props` | as named |
  | `:resolution` | the rule's annotation `:resolution` changed |
  | `:unit` | composed units only: the production is attributed to a different unit |

- **`:fact-types`:** added, removed, and `:ancestors` changed.
- **`:edges`:** dep-graph `[upstream downstream]` pairs gained and lost. An edge whose endpoint
  sits in a scope-only namespace is reported under `:scope` with that namespace's productions.

A diff of a unit against itself is empty in every key, as with `federate/diff`.

### 3.5 Text form

Read by people and by agents, so bytes per answer matter and the output scales with the change, not
with the unit:

- two provenance lines, then one count line per section;
- then each non-empty section, one line per name, `changed` lines carrying their tags;
- **no changes** prints the provenance lines and `no differences`.

`--rule NAME` (substring matching, as `rule` does) prints that production's before and after for
each changed field, including both `:rhs-form` texts. `--edn` prints the `diff` value.

### 3.6 Composed units

A composed unit is unit-shaped and its productions carry `:unit`, so the same command diffs two
composed scopes. Composing the same scope twice, once with a branch's unit swapped in, then diffing,
gives the branch's effect across every unit at production level: the rules in other units that
gained or lost an upstream edge, and through which types. `federate/diff` stays the unit-level
summary of the same question, and the two read well together.

### 3.7 What it doesn't answer

- **Renames.** A renamed production shows as one removed and one added.
- **Meaning.** An `:rhs` tag says the text changed, not what the change does at runtime.
  Behavioral equivalence still needs a fired session.
- **Names that differ by build.** Names are compared verbatim. A host whose compiled namespaces
  carry per-build stamps gets no useful diff between two sessions restored from different builds.

### 3.8 Tests

- `diff_test`: pure tests over edited `read-unit` values, one per tag, plus scope
  separation (a namespace present on one side lands under `:scope`, not `:added`), edge gain and
  loss, fact-type changes, the shape-skew refusal, and empty-against-itself.
- **Against the checked-in example:**
  - `loan-disposition-ruleset` against `_variants/loan-disposition-ruleset/ref=feature%2Fnew-tax`,
    the same session at a variant address: no differences, with both provenance lines;
  - `loan-app-ruleset` against `composed/loan-app-plus-disposition`: the disposition namespace
    under `:scope`, and `:unit` attribution on the composed side.
- `bb_report_test`: the subcommand end to end, text and `--edn`, and `--rule`.

### 3.9 Docs

`persisted-artifacts.md` §"Querying it offline": add the `diff` line to the subcommand block, and
a short paragraph on what it reads, the scope separation, the shape-skew refusal, and §3.7.

## 4. Order

1. Part A (§2.1–2.5), then §2.6. Regenerate the checked-in example; the diff is exactly the
   fixture's new rule plus nothing else.
2. Part B, which assumes A: without it, units holding digest-suffixed locals report `:rhs`
   changes on every comparison.
