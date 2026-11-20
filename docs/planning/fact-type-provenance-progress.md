# Plan: fact type provenance (producer + consumer)

Status: **proposed (consolidated, ready to implement)**. This doc is the single
source of truth for the work: both plan-review rounds are folded in, including
the concrete test matrix in §6.1. Nothing else needs reading to start.

Replaces the POC on `clara-explorer-producer-hierarchy`. The POC's
`lhs-producers`, elisp tree builder, and `lsp-treemacs` view are throwaway. The
idea is kept; the implementation is not. Nothing below is committed to because
the POC wrote it.

## 1. What this is

A fact type's **provenance** is the reference tree around its facts:

- **producer provenance** — upstream: which productions insert or retract this
  type (or a subtype of it), and what those productions consume.
- **consumer provenance** — downstream: which productions consume this type (or
  a supertype of it), and what those productions insert or retract.

Both directions answer the same question in opposite directions, so they share
one operation and one node shape. The feature is not the **fact type
hierarchy**: `:ancestors` / `:descendants` and the subtype closures stay
exactly what they are. Provenance *uses* the hierarchy; it does not replace or
rename it.

The repo already uses `:provenance` for annotation/unit origin
(`artifacts/schema.clj`, the provenance manifest). Code and prose therefore say
**`fact-type-provenance`** whenever the two could be confused. UI titles may
say "Fact Type Provenance".

### 1.1 Terminology

| Term | Meaning | Never means |
| --- | --- | --- |
| fact type hierarchy | `derive` relationships, `:ancestors` / `:descendants` | the provenance tree |
| producer provenance | upstream references to a fact type | — |
| consumer provenance | downstream references to a fact type | — |
| provenance node | one `:fact-type`, `:production`, or `:message` node | a `TypeReference` |
| bridge type | the exact type that makes a production reach a node through the hierarchy | — |

Rename every "hierarchy" that means provenance: `clara-explorer-producer-hierarchy`,
`--lhs-producers`, `lhs-producers`, `LhsProducers*`, `hierarchy-*` tests, and
the elisp `clara-explorer--hierarchy-*` helpers. Keep "hierarchy" only in the
subtype sense.

## 2. The one contract

A pure, lazy, direction-aware operation in
`explorer/src/clara/explorer/server/navigate.clj`, answered by both transports.
The editor asks for one node's expansion; the backend does all graph work.

### 2.1 Input

```clojure
{:node {:kind :cursor
        :input {:production "ns/rule"    ; enclosing production or nil (for RHS callsite scoping)
                :side :lhs               ; structural side at cursor (:lhs | :rhs | absent)
                :caller-ns "ns"
                :token "DocumentCheck"}}
 :direction :producer                    ; :producer | :consumer
 :depth 0                                ; extra production levels to inline,
                                         ; or :full to expand with :cycle? + :seen?
 :ancestors []}                          ; production names strictly above the root
```

or, expanding a rendered production node:

```clojure
{:node {:kind :production :name "ns/rule"}
 :direction :producer
 :depth 0
 :ancestors []}                          ; production names strictly above the root
```

- **Validation and errors.** `:node :kind` is `:cursor` or `:production`
  (`:fact-type` is output-only and refused). `:direction` is required
  (`:producer` | `:consumer`). `:depth` is required: a non-negative integer or
  `:full`. `:ancestors` is required (a vector of production names, `[]` at a
  root); an entry naming no production is an error, not silence. **Shape**
  violations are the schema's job (the test fixture throws, the JVM shell turns
  an exception into `{:error "…"}`); what the schema cannot express — an unknown
  production name, an ancestor that names nothing, a negative depth, a token
  resolving to nothing — comes back as `{:error "…"}` from the operation itself.
- **Cursor token resolution (`resolve-cursor-fact-type`):** decoupled from
  `navigate`'s non-empty-target check, so a consumer or producer tree can root
  from any token — LHS (including inside `:not`, `:exists`, `:and`, `:or`, or
  accumulator `:from`), RHS, props, docstring, or outside a rule — and still
  succeeds with `:children []` when the resolved type has zero producers or
  zero consumers.
- **Scoped RHS callsite priority:** `:cursor :input` preserves the enclosing
  `:production` and structural `:side` from `clara-explorer--context`
  (`clara-explorer--edn-map` omits nil entries, so an absent side is simply an
  absent key). When `:production` is non-nil and structural `:side` is `:rhs`,
  token resolution checks that production's `:dynamic-insert-types-detected` /
  `:dynamic-retract-types-detected` callsites first before falling back to
  global resolution, avoiding cross-rule constructor collisions. Target lookup
  always uses the **global** closures (no self-exclusion).
- **`:input :side` is structural, never a direction.** The `:cursor :input` map
  reuses the `NavigateInput` shape, but `:side` there means "where the cursor
  sits in the form" (`:lhs` / `:rhs` / absent), not "which way to walk" — that
  is the top-level `:direction` and nothing else. `FactTypeProvenanceInput`'s
  docstring must say so, so the two meanings never blur.
- **Ghost and supertype roots are valid.** Any token resolving to a known fact
  type roots a tree, including hierarchy-only types (`java.lang.Object`,
  interfaces) whose producer set is large and mostly uninteresting. Nothing
  special-cases them; the tree shows what the closures say.
- **`:depth` and `:seen?`:**
  - For a `:cursor` root (returns a `:fact-type` node), `:depth 0` returns its
    immediate `:production` children with their `:children` omitted (except leaf
    productions, which always carry `:children []`).
  - For a `:production` root (`TAB` expansion), `:depth 0` always expands that
    root production's `:fact-type` children and one layer of their `:production`
    children (passing `depth = 0` to those child productions). So `:depth n`
    shows `n + 1` production levels below the requested root, for both root
    kinds.
  - `:depth n` (`n > 0`) inlines `n` additional production levels; `:depth :full`
    expands until either a production repeats on the current `:ancestors` path
    (`:cycle? true`) or was already expanded earlier in the same response
    (`:seen? true`). `:seen?` bounds `:full` to $O(\text{productions} \times \text{fan-in})$
    across diamond DAGs while keeping `:cycle?` strictly for path cycles. There
    is no node budget: `:seen?` is the bound.
  - **`:ancestors` at a `:production` root:** the backend checks
    `(contains? (set ancestors) P)` for the root `P` itself, then expands `P`'s
    `:fact-type` children with `(conj (set ancestors) P)`, so a direct
    self-loop (`P → T → P`) is marked `:cycle? true` on the child production.

### 2.2 Output

```clojure
{:node {:kind :fact-type
        :type "com.example.DocumentCheck"     ; full serialized fact-type name
        :type-short "DocumentCheck"           ; display form; editors never derive it
        :external? false                       ; true when no producer has :via :insert
        :vias [:insert]                        ; consumer direction only: how the parent
                                               ; production's RHS produces this type
        :children [<production node> ...]}}    ; [] = no references in this direction

{:node {:kind :production
        :name "ns/app-has-all-required-docs"
        :ns "ns"
        :type "rule"                           ; "rule" | "query"
        :unit "loan-app-ruleset"               ; absent for live single sessions
        :via :insert | :retract | :consume     ; edge kind from parent type (absent at root)
        :vias [:insert :retract]               ; when a producer both inserts & retracts
        :matches [{:name "com.example.DocumentCheck" :short "DocumentCheck"}]
                                               ; bridge type(s) from the parent type
                                               ; (absent at root), name-sorted
        :bridge? false                         ; :matches differs from the parent type
        :outputs :unknown                      ; present when :unlinked-rule
        :source {...}
        :cycle? false
        :seen? false                           ; true when already expanded in this response
        :children [<fact-type node> ...]}}     ; [] = leaf; absent = unexpanded, cycle, or seen

{:node {:kind :message :text "..."}}
```

- `:children` absent = expandable (or stopped by `:cycle?` / `:seen?`); `[]` =
  known empty leaf, populated eagerly even at `:depth 0` for queries,
  `:no-output-types` rules, `:unlinked-rule` rules, and zero-LHS rules so the UI
  never wastes a `TAB` round-trip on a leaf.
- `:cycle?` and `:seen?` are always present (booleans) on `:production` nodes;
  `:via`, `:matches`, and `:bridge?` are not (they need a parent fact type).
- `:external?` appears only on **producer-direction** `:fact-type` nodes, at any
  depth: `true` when no producer has `:via :insert` (so a type with only a
  retracting producer is still external). It is derived from producer vias,
  never from `TypeReference` `:known` — `:known` is unreliable on composed
  selections until the §2.4 fix lands. `…loan_app_facts.Application` is the
  honest example: `:known true`, yet nothing inserts it, so it is external.
- `:vias` on a consumer `:fact-type` child records `[:insert]`, `[:retract]`, or
  `[:insert :retract]`. On a producer `:production` child that both inserts and
  retracts (subtypes of) parent type `T`, the production is deduplicated into
  one node with `:via :retract` and `:vias [:insert :retract]`, and `:matches`
  unions both its matching insert and retract types. `:vias` is absent at a
  root, and on producer-direction LHS `:fact-type` children (there the edge
  means "consumes").
- **Display names are contract data, not editor logic.** `:type-short` on a
  fact-type node and `:short` on each `:matches` entry are the backend's short
  forms of those names; `:bridge?` is the backend's answer to "does this edge
  reach the parent type through the hierarchy". Fact-type names have several
  kinds (class/record, keyword, string, tuple, map) and only the class-like ones
  shorten, so deriving this per editor would duplicate a fact-type rule in
  Emacs, Neovim, and anything else. No editor computes or shortens a fact-type
  name.
- `:outputs :unknown` is present exactly when the production carries
  `:unlinked-rule` — the one "RHS effects exist but none resolved" signal that
  survives slimming. Its `:children` is then `[]`, and the renderer labels it
  `(outputs unknown)` rather than `(no outputs)`: nothing was resolved to show,
  but the node is not known to be a sink. (`:resolution` on the dynamic
  detections is richer, but `compose` drops `:dynamic-*-detected` from slim
  analyses, so using it would make bb and registry-mode disagree with live
  sessions. Not in v1.)
- `:polarity` is **not** part of v1: a production node does not say whether it
  matches the parent type positively, negated, or through an accumulator. See
  §2.3 and §10.
- `:unit` arrives on the `ProductionDep` itself: `shared.rehydrate/->production-ref`
  copies it from the referred rule/query, and `dep->target` / the provenance node
  builders copy it through. It is present whenever the selection was composed
  (a single-unit registry selection still composes, so it carries `:unit`) and
  absent for a live session with no unit identity.
- **Production display names stay editor-side.** A `:production` node carries
  `:name` (fq `ns/var`) and `:ns`; the unqualified var name is the suffix after
  `ns/`, so a renderer derives it mechanically and the contract carries no
  separate key. (If one is ever added it is `:unqualified-name` — never
  `:name-short`, since Clojure's `name` already means the unqualified part.)
  Contrast `:type-short`: fact-type names come in several kinds and only the
  class-like ones shorten, so that rule is non-trivial and belongs in the
  backend, where both transports and every editor share it.
- **Ordering is deterministic** and every exact-vector assertion depends on it:
  the `:production` children of a `:fact-type` node follow the closure's
  name-sorted production refs; a producer production's `:fact-type` children are
  its `:lhs-types` in declaration order (already `distinct` in
  `clara.explorer.conditions/extract-lhs-fact-types`); a consumer production's
  children are `:insert-types` then the remaining `:retract-types`, both in
  declaration order; `:matches` is name-sorted; `:vias` is `[:insert]`, `[:retract]`,
  or `[:insert :retract]`.
- Errors (`no fact type found`, `no production named …`) come back as
  `{:error "…"}`, the same shape `navigate` uses.

### 2.3 Direction and `:not` negation semantics

The rehydrated closures already encode the hierarchy and LHS condition polarity
for provenance:

- **producer:** `inserted-by-rules[T]` / `retracted-by-rules[T]` include P iff P
  inserts/retracts `T` or a **descendant** of `T` (ancestor closure). A
  production node's children are its `:lhs-types` in declaration order.
- **consumer:** `used-by-rules[T]` / `used-by-queries[T]` include P iff P's LHS
  contains `T` or an **ancestor** of `T` (descendant closure). A production
  node's children are its `:insert-types` **and** `:retract-types`, merged by
  type in declaration order (`insert-types` then remaining `retract-types`),
  with `:vias` recording `[:insert]`, `[:retract]`, or `[:insert :retract]`.
- **`:not` negation nodes (and `:exists` / accumulator `:from`) are consumers:**
  A rule whose LHS has `[:not [T ...]]` (such as `app-outcome-denied?` and
  `app-outcome-pending?` on `ApplicationOutcome`) depends on `T` in working
  memory and **is** a consumer of `T` for provenance purposes; symmetrically,
  `T` is an upstream LHS fact-type child of that rule in the producer tree.
  Cursor extraction on `[:not [T ...]]` — including when point is on the `:not`
  operator or wrapper brackets — resolves to `T`.
- **Polarity is not labelled in v1.** The same rules that match `T` inside
  `[:not …]` are drawn exactly like positive consumers, and an accumulator
  `:from [T]` is drawn like a plain condition. This is a deliberate, documented
  limitation (§10), not an oversight: labelling it would mean reading the
  persisted `production-conditions.edn` part, which `bin/editor-client.bb`
  currently does not (it reads only `:index`, `:fact-types`, `:dep-graph`, and
  `:meta`), so it would break bb/JVM parity until that I/O grows too.

Leaves in either direction:
- A query (`:type "query"`) has no RHS, so its `:children` are `[]` (`(query)`).
- A rule with `:no-output-types true` has `:children []` (`(no outputs)`).
- A rule with `:unlinked-rule` has `:children []` **and** `:outputs :unknown`
  (`(outputs unknown)`); it is not known to be a sink.
- A rule with an empty `:lhs-types` is a leaf in the producer direction.
- A retract-only production has retracted-type children and is not a leaf.

Deliberate choice: use the **global** closures, not the scoped `navigate`
self-exclusion, and let `:cycle?` + `:seen?` stop repetition. See §2.3.1.

### 2.3.1 Why global closures, not scoped self-exclusion

`navigate` drops the production the cursor is inside from the target set. That
is right for a jump (answering "you" is not useful), but wrong for a tree.

`app-outcome-denied?` both consumes and produces `ApplicationOutcome`: its LHS
has `[:not [ApplicationOutcome ...]]`, its RHS inserts `ApplicationOutcome`.

- **Producer tree, scoped:** opened on `ApplicationOutcome` from inside that
  rule, the rule is removed from its own targets, so the tree never shows that
  it also produces the type. The self-loop is invisible.
- **Producer tree, global + `:cycle?`:** opened on `ApplicationOutcome` (`:cursor`
  root with `:ancestors []`), `app-outcome-denied?` appears at Level 1 as a
  producer of `ApplicationOutcome` — expandable, because nothing is on the path
  yet; expanding `app-outcome-denied?` reveals its LHS `ApplicationOutcome`
  child, under which `app-outcome-denied?` appears with `:cycle? true`
  (`↻ cycle`) and is not expandable. (When `{:kind :production
  :name ".../app-outcome-denied?"}` is expanded directly as the root, the cycle
  marker appears immediately under its `ApplicationOutcome` child, per §2.1.)

The consumer direction makes the same point the other way. Opening a consumer
tree on `ApplicationOutcome` from inside `app-outcome-approved-args-demo`,
which consumes it, shows that rule as the first downstream consumer at Level 1
(not excluded, and not prematurely marked as a cycle at Level 1 because
`:ancestors` at a `:cursor` root is `[]`).

Cross-rule cycles (A consumes T and inserts U; B consumes U and inserts T) are
not stopped by scoped exclusion either, because it only drops the one
production being expanded. `:cycle?` is needed regardless. So the tree uses
the global closure and one cycle termination rule, plus `:seen?` to avoid
re-expanding shared DAG subtrees in a single response.

### 2.4 Composed `:unknown-fact-types` fix (`shared/compose.clj`)

In `clara.explorer.artifacts.shared.compose/->composed-slim`, `:unknown-fact-types`
currently takes the union of the units' `:unknown-fact-types` sets without
removing types that are directly referenced (`:lhs-types`, `:insert-types`,
`:retract-types`) by productions in the composed rulebase. As a result, a
cross-unit bridge type like `ApplicationOutcome` (known in `loan-app-ruleset`,
hierarchy-only in `loan-disposition-ruleset`) or `:loan-app/application-outcome`
(known in `loan-disposition-ruleset`, ancestor-only in `loan-app-ruleset`) was
left in `:unknown-fact-types` and rehydrated with `:known false`. The checked-in
`_compose` fixture currently lists both in its `:slim :unknown-fact-types`.

Fix `->composed-slim` to subtract the composed productions' directly-referenced
type names from the unioned set. That means passing the already-merged `:rules`
and `:queries` into `->composed-slim` in
`->composed-analysis-from-selection` and deriving the names the same way
`compose_test.clj`'s `->direct-type-names` already does. Then regenerate the
checked-in `_compose` fixture (`make regen-artifacts`) and pin `:known true` on
both bridge types in `compose_test.clj`.

### 2.5 Backend surface

| File | Change |
| --- | --- |
| `explorer/src/clara/explorer/artifacts/shared/compose.clj` | `->composed-slim` takes the merged rules/queries and subtracts the productions' direct types from the unioned `:unknown-fact-types` |
| `explorer/src/clara/explorer/artifacts/shared/rehydrate.clj` | propagate `:unit` onto `ProductionDep` in `->production-ref` |
| `explorer/src/clara/explorer/server/api.clj` | `(s/optional-key :unit) s/Str` on `ProductionDep`, so rehydrated dep vectors stay schema-valid |
| `explorer/src/clara/explorer/server/schema.clj` | `:unit` optional on `NavigateTarget`; delete `LhsProducers*`; add `FactTypeProvenanceInput` (`:depth` as `(s/cond-pre s/Int (s/eq :full))`; the `:cursor :input` is a `NavigateInput` with `:side` omitted when there is no structural side), recursive `FactTypeProvenanceNode` (`s/recursive` over its own var, since schema 1.4.1 does not resolve a bare self-reference; includes `:type-short`, `:matches` entries as `{:name :short}`, and `:bridge?`), `FactTypeProvenanceResult/Response` |
| `explorer/src/clara/explorer/server/navigate.clj` | delete `lhs-producers`; add `fact-type-provenance`, `resolve-cursor-fact-type`, a `type-name->short-name` helper (class-like names only; keywords/strings/tuples/maps stay whole), and their direction-aware `->*` helpers; attach `:unit` in `dep->target` |
| `explorer/src/clara/explorer/server/client.clj` | replace `lhs-producers` with `fact-type-provenance` (same system/cache/log shell as `navigate`) |
| `explorer/bin/editor-client.bb` | replace `--lhs-producers` with `--provenance` |
| `explorer/test/.../server/navigate_test.clj` | **new** — pure unit tests over hand-built rehydrated analyses (N1–N13) |
| `explorer/test/.../server/registry_server_test.clj` | registry-mode composed tests (R1–R7) over the existing `->registry-config` two-unit fixture |
| `explorer/test/.../server/client_test.clj` | live-session tests (L1–L5); the POC's `test-lhs-producers` is replaced |
| `explorer/test/.../artifacts/compose_test.clj` | pin `:known true` on the cross-unit bridge types |
| `explorer/test/.../artifacts/editor_client_bb_test.clj` | bb/JVM parity for `--provenance` (B1–B3) |

`navigate_test.clj` is the seam for the hard cases: build tiny slim analyses by
hand (a few `:rules`, `:fact-types` with `:ancestors`), push them through
`clara.explorer.artifacts.shared.rehydrate/rehydrate-analysis` (the real closure
code), and pass a stub `NavigateRuntime`. Fast, deterministic, and it runs under
`st/validate-schemas`. Adding real fixture rules for diamonds, cross-type
cycles, insert+retract, and unlinked outputs would bloat `rules-annos` and shift
every existing count.

Helpers stay small and follow repo naming conventions (`->` prefix for
builders): `resolve-cursor-fact-type`, `type-name->short-name`,
`->producer-targets`, `->consumer-targets`, `->bridge-types`,
`->provenance-fact-type-node`, `->provenance-production-node`,
`->provenance-production-children`. Mutual recursion via `declare`, not one
monolithic expander.

## 3. The editor stays thin

This is the rule that keeps a Neovim client (and any future client) a renderer,
not a second implementation:

- **The editor may:** gather structural context (enclosing production, side,
  token text), resolve aliases/`::` to fq, choose the transport, build the
  cursor input, call the op, cache returned children, render labels from the
  contract's fields, and dispatch `RET` / toggle.
- **The editor may not:** read `:inserted-by-rules`, `:used-by-rules`,
  `:descendants`, `:ancestors`, `:dep-graph`, or `:lhs-types`; compute
  closures; dedupe targets; decide which productions match a type; detect
  cycles; compute bridge types; derive short display names from fact-type
  names (the contract carries `:type-short`, `:matches`' `:short`, and
  `:bridge?`). Deriving a production's unqualified name from `:ns` is fine — it
  is prefix removal on two contract fields, not a fact-type rule.

If a change makes the editor inspect analysis data, it belongs in
`navigate.clj`. The contract is plain EDN over both transports; no
Emacs-specific field may enter it.

Neovim seam, no code in this branch: a future `:ClaraExplorerShowProvenance`
only needs to build the same cursor input, shell to `--provenance`, and render
`:fact-type` / `:production` / `:message` nodes. No Clojure change should be
needed to add it. The single-file Emacs choice (§5) is a packaging decision for
people trying the checkout by hand; the layering inside the file must still
match the backend contract so a future split is mechanical.

## 4. "via T" for hierarchy bridges

Every child production node carries `:matches` (name-sorted vector of
`{:name <full> :short <short>}`) and `:bridge?`, computed in shared code:

```
producer: (P's insert-types when :insert, ∪ P's retract-types when :retract)
          ∩ ({T} ∪ descendants(T))
consumer: P's lhs-types
          ∩ ({T} ∪ ancestors(T))
```

`:bridge?` is true exactly when `:matches` is not `[{:name T …}]` — that is,
when the edge reaches the parent type through the hierarchy rather than
directly. The backend decides it because it already holds both sides; an
editor only renders `via <shorts>` when `:bridge?` is true, joining the
`:short` forms. No name comparison or shortening happens in the editor.
`:matches` is a distinct key alongside `:type` because `NavigateTarget:type` is
`"rule"`/`"query"`, not a fact type.

Only class-like names shorten (`java.lang.Object` → `Object`); keywords,
strings, tuples (`[:loan/status "verified"]`), and maps stay whole, because
only the class form has a disposable package prefix. That rule lives in
`navigate.clj` once, beside the node builders.

Two cases this must show on the composed `loan-app-ruleset` +
`loan-disposition-ruleset` registry:

- producer node `:loan-app/application-outcome` → `app-outcome-approved?`
  inserts `...ApplicationOutcome`, so the node reads **via ApplicationOutcome**;
- consumer node `...ApplicationOutcome` → `notice-approved-app` matches the
  keyword supertype, so the node reads **via :loan-app/application-outcome**.

Both of these only exist on a **composed** selection. `client_test`'s
`loan-app-session` does not load `loan-outcome-notices`, so they are covered by
the registry-mode tests (R1–R2, R7) and the bb parity test (B1), never by a
live-session test. Live-session tests can only assert that `:unit` is *absent*.

## 5. Emacs: single file, built-in tree

Per the decision to keep one loadable file until packaging exists, all Emacs
work stays in `editor/emacs/clara-explorer.el`. No new file, no
`lsp-treemacs`, no new package. The file grows; that is accepted for now.

- Update `clara-explorer--wrapper-inner-type-bounds` so when point is on a
  logical wrapper head (`:not`, `:exists`, `:and`, `:or`) before the first child
  vector, it falls back to the first child condition's type bounds rather than
  returning `nil`.
- Finish the POC's `--query-at-point` split as `clara-explorer--prepare-query`:
  resolves transport, context, and token, returning `(:input :ctx :transport
  :conn)` **without** sending an operation. Token resolution may itself hit the
  REPL; what it must not do is call `navigate` / `fact-type-provenance`.
  The `"queries have no RHS"` guard moves out of the shared path and into the
  consumer navigate command (`clara-explorer--navigate` when direction is
  consumer); provenance allows any token inside a `defquery`.
- Add `clara-explorer--op-result QUERY OP &optional INPUT`: `navigate` → bb no
  flag / nREPL `client/navigate`; `fact-type-provenance` → bb `--provenance` /
  nREPL `client/fact-type-provenance`. `clara-explorer--edn-value` learns to
  print keyword-keyed plists as nested maps, so a provenance input's `:node` /
  `:input` maps serialize for both wires without a second printer.
- Two autoloaded commands sharing one implementation:
  `clara-explorer-show-producer-provenance` and
  `clara-explorer-show-consumer-provenance`.
- View: `tabulated-list-mode` (built-in, zero packages).
  - buffer-locals: direction, root node, query, `expanded-set`, and `child-cache`
    keyed by the **full tree path** (alternating fact-type and production names,
    so the same production under two sibling fact types toggles independently);
  - any returned `:production` node that already carries non-empty `:children`
    (from `:depth > 0` or `:depth :full`) seeds `child-cache` and `expanded-set`
    on receipt;
  - root: `{:node {:kind :cursor :input <prepared>} :direction D :depth <custom>
    :ancestors []}` — one backend call, no separate navigate;
  - `TAB` on a production: fetch `{:node {:kind :production :name …}
    :direction D :depth 0 :ancestors <production names on path minus self>}`,
    cache, re-render; `:cycle?`, `:message`, `:outputs :unknown`, and empty-leaf
    (`:children []`) nodes do not toggle; `:seen?` nodes can be expanded on
    demand at `:depth 0`;
  - query errors (`{:error …}` or process/eval failure) render as `:message`
    rows so a failed expansion does not break the buffer — for the root call
    and for a `TAB` expansion alike;
  - `RET` on a production: existing `clara-explorer--goto` with the node's
    target fields;
  - labels: `:type-short` + dim `:type`, rule name + dim ns, `@unit`,
    `(retract)` on producer edges, an insert/retract aside for consumer
    children, `↻ cycle`, `↑ shown above` (for `:seen?`), `(external)` (for
    `:external? true`), `(query)`, `(no outputs)`, `(outputs unknown)`,
    and `via <:matches :short>` when `:bridge?`;
  - depth defcustom with three choices: `0`, an integer, or `full` (passes
    `:depth :full`);
  - `g` re-runs the root from the stored query, `q` buries;
  - keep the side-window defcustom.

Delete the POC's `declare-function lsp-treemacs-render`, the runtime `require`,
its defcustoms, node builders, and all `hierarchy-*` tests.

## 6. Work breakdown, in dependency order

| # | Step | Where | Done when |
| --- | --- | --- | --- |
| 1 | Rename/delete POC vocabulary; no behavior change | backend + editor + docs | `git grep` shows no provenance-as-hierarchy names |
| 2 | Fix composed `:unknown-fact-types`; attach `:unit` on `ProductionDep` / `NavigateTarget` | `shared/compose.clj`, `shared/rehydrate.clj`, `api.clj`, `schema.clj`, `navigate.clj` | `compose_test` pins `:known true` on both bridge types; R7 green |
| 3 | Direction-aware `fact-type-provenance` with `:cycle?`, `:seen?`, `:not` consumer support; delete `lhs-producers` | `navigate.clj`, `navigate_test.clj` | N1–N13 green |
| 4 | Schemas for input/node/response | `schema.clj` | N1–N13 pass under `st/validate-schemas` |
| 5 | JVM wrapper + live & registry tests | `client.clj`, `client_test.clj`, `registry_server_test.clj` | R1–R7 and L1–L5 green |
| 6 | bb `--provenance` + parity test | `editor-client.bb`, `editor_client_bb_test.clj` | B1–B3 green |
| 7 | Emacs `:not` wrapper fallback, `--prepare-query` + generic op runner | `clara-explorer.el`, `clara-explorer-test.el` | E1, E7, E8 green (existing navigate tests untouched) |
| 8 | Emacs provenance view, both commands | `clara-explorer.el`, `clara-explorer-test.el` | E2–E6 green |
| 9 | Docs | `docs/explorer-editor-navigation*.md`, `editor/AGENTS.md`, bb header | terminology consistent everywhere; §2.3 polarity limitation and the bootstrap/sandbox note recorded |
| 10 | Full verification | both projects | commands in §8 green |

### 6.1 Test matrix

This is the authoritative "done when" for steps 3–8. Expected values come from
the checked-in two-unit composition (`loan-app-ruleset` +
`loan-disposition-ruleset`). `la/` = `clara.explorer.test.rules.loan-app-rules/`,
`ldr/` = `…loan-doc-rules/`, `AO` = `…loan_app_rules.ApplicationOutcome`,
`KW` = `:loan-app/application-outcome`. Below, `:matches [X]` means
`(mapv :name (:matches node))` = `[X]` — the node's `:matches` itself is a
vector of `{:name … :short …}` maps, and each fact-type node also carries
`:type-short`.

#### N — pure `navigate_test.clj` (hand-built analyses through `shared.rehydrate`)

| # | Case | Input | Must assert |
| --- | --- | --- | --- |
| N1 | Direct producer | `P` inserts `T`; cursor on `T`, producer | one `:production` child `P`, `:via :insert`, `:matches [T]`, `:bridge? false` |
| N2 | Subtype bridge (producer) | `S ⊂ T`; `P` inserts `S` | `P` under `T` with `:matches [S]`, `:bridge? true`, `:short` on the match |
| N3 | Supertype bridge (consumer) | `S ⊂ T`; `C` consumes `T`; root `S` | `C` under `S` with `:matches [T]`, `:bridge? true` |
| N4 | Insert + retract one type | `P` inserts `T` and retracts `T` | `P` appears **once** under `T`; `:via :retract`, `:vias [:insert :retract]`; `:matches` unions both sides, not just the retract side |
| N5 | Self-loop | `P` consumes and inserts `T` | `P` at level 1 is expandable; expanding it lists `P` under its `T` child with `:cycle? true` and no `:children` |
| N6 | Cross-type cycle | `A: T→U`, `B: U→T` | `:depth :full` terminates; the second `A` is `:cycle? true` |
| N7 | Diamond / `:full` growth | `A→{B,C}→D` repeated k levels | node count stays linear in k; repeats carry `:seen? true` and `:cycle? false` |
| N8 | Depth semantics | chain of 3 productions | levels *below the requested root*: `:depth 0` / `1` / `:full` give 1 / 2 / 3, for both `:cursor` and `:production` roots |
| N9 | Leaf kinds | a query; a `:no-output-types` rule; an `:unlinked-rule` rule; a zero-LHS rule | `[]` + `:type "query"`; `[]` + no-outputs; `[]` + `:outputs :unknown` (not plain leaf); `[]` — all present **at `:depth 0`** |
| N10 | External vs retract-only producer | `T` with only a retracting producer | `:external? true` on the `T` node although it has one child |
| N11 | Ordering | several producers/consumers, both directions | exact vectors match §2.2: name-sorted production children, declaration-order `:fact-type` children, name-sorted `:matches`, `:vias` order |
| N12 | Errors | unknown production; empty token; `:depth -1`; `:ancestors ["nope/nope"]`; plus `:direction :sideways` / `:node :kind :fact-type` as shape cases | `{:error …}` from the operation for what the schema cannot express; `s/check` rejects the shape cases |
| N13 | Scoped RHS callsites | two rules share a constructor token but resolve it to different types | global root = first name-sorted global type; scoped inside a rule = that rule's type |

There is no `:polarity` test: v1 emits none (§2.3).

#### R — JVM registry mode (`registry_server_test.clj`; `server/start-system! {:registry …}` + `client/fact-type-provenance`)

| # | Root | Expected |
| --- | --- | --- |
| R1 | producer, cursor `KW` | children `la/app-outcome-approved?`, `la/app-outcome-denied?`, `la/app-outcome-pending?`; each `:via :insert`, `:matches [AO]`, `:bridge? true`, `:unit "loan-app-ruleset"` |
| R2 | consumer, cursor `AO` | includes `notice-approved-app` / `notice-denied-app` with `:matches [KW]`, `:bridge? true`, `:unit "loan-disposition-ruleset"`; includes `la/app-outcome-denied?` and `la/app-outcome-pending?` (the `:not`-as-consumer decision); `la/find-app-outcome` has `:type "query"` and `:children []`; `la/app-outcome-approved-args-demo` has `:children []` (no outputs) |
| R3 | producer, cursor `…loan_app_facts.Application` | **no error**; `:children []`; `:external? true` |
| R4 | consumer, cursor `…loan_app_facts.DocumentCheck` | includes `ldr/dynamic-retract-stale-notice`, whose `…StaleDocumentNotice` child has `:vias [:retract]` (retract-only is not a leaf) |
| R5 | producer, `{:kind :production :name "la/app-outcome-denied?"}` `:ancestors []` | its `AO` child lists `la/app-outcome-denied?` `:cycle? true`; `approved?` / `pending?` present and expandable |
| R6 | consumer, cursor `AO` from **inside** `la/app-outcome-approved-args-demo` | that rule appears as a consumer and is not hidden (the §2.3.1 claim) |
| R7 | `navigate` regression | ordinary navigate targets carry `:unit` in registry mode |

#### L — JVM live session (`client_test.clj`)

| # | Case | Expected |
| --- | --- | --- |
| L1 | R1/R5 equivalents over `loan-app-session` | same names, **no `:unit` key** |
| L2 | Scoped RHS resolution (live) | a cursor on an RHS ctor token inside production `P` roots at `P`'s declared type, proving the scoped path works live; the divergent-callsite precedence itself is N13, because no fixture rule resolves one token to two types |
| L3 | Cursor inside a `defquery` | consumer provenance works (no "queries have no RHS") |
| L4 | `:source :var? true` | production nodes carry var metadata like `navigate` targets |
| L5 | No system registered / internal exception | `{:error …}`, same as `client/navigate` |

#### B — bb parity (`editor_client_bb_test.clj`)

| # | Case | Expected |
| --- | --- | --- |
| B1 | R1–R5 over the same selection via `--provenance` | `=` to the JVM registry answer after stripping `:source` (bb is always `:var? false`) |
| B2 | Variant unit | variant answers match mainline, as the existing navigate variant test does |
| B3 | `:full` guard | the R2 root at `:depth :full` finishes, and its node count equals the JVM count |

#### E — Emacs (`clara-explorer-test.el`)

| # | Case | Expected |
| --- | --- | --- |
| E1 | `--prepare-query` | sends no op; returns `:input` carrying `:production` + structural `:side`; works inside a `defquery` |
| E2 | Render from recorded EDN | given a stored R2-shaped response, the rows/labels are right: `via :loan-app/application-outcome` from `:matches`/`:bridge?`, `:type-short` beside dim `:type`, `@loan-disposition-ruleset`, `(query)`, `(external)`, `↻ cycle` — no fact-type name shortening in the elisp |
| E3 | Sibling key collision | the same production under two fact types toggles independently |
| E4 | Pre-expanded depth | a `:depth 1` response renders expanded with no extra fetch |
| E5 | `TAB` request shape | sends `:ancestors` = path production names minus self; sends nothing for cycle, `:seen?`, `:message`, `:outputs :unknown`, or empty-leaf rows |
| E6 | Errors | `{:error …}` and bb non-zero exit render as message rows, for the root and for an expansion |
| E7 | Transports | provenance op → `--provenance` on bb and `client/fact-type-provenance` on nREPL (the POC's `hierarchy-lhs-producers-transports`, renamed) |
| E8 | Navigate unchanged | the existing `navigate-*` tests pass untouched after step 7 |

Step 7 additionally pins the wrapper-head fallback: point on `:not`/`:and` or on
the wrapper's opening bracket resolves to the first child condition's type
(`lhs-type-not-wrapper-head-…`, `…-bracket-…`, `lhs-type-and-wrapper-head-…`).

## 7. Progress log

Checkpoints, not a diary. Update in place as work lands.

- [x] Audit the POC and separate the idea from its implementation
- [x] Decide terminology: fact type provenance, with producer/consumer directions
- [x] Decide both directions ship; consumer semantics validated from the
      rehydrated closures
- [x] Decide no `lsp-treemacs`; built-in `tabulated-list-mode`
- [x] Decide single-file Emacs implementation for now
- [x] Decide `:matches` drives `via <type>` only when it differs from the node
- [x] Decide short fact-type display names are contract data, not editor logic:
      `:type-short` on fact-type nodes, `{:name :short}` entries in `:matches`,
      and `:bridge?` for the `via` decision; the elisp shortener is deleted
- [x] Decide consumer children are inserts **and** retracts, distinguished by
      `:vias`; a query has no children, a retract-only production is not a leaf
- [x] Decide default depth `0`, with `:full` bounded by `:cycle?` + `:seen?`
- [x] Decide global closures + `:cycle?` + `:seen?`; example in §2.3.1
- [x] Decide `:not` negation nodes are treated as consumers (and cursor on `:not` resolves inner type)
- [x] Decide to fix composed `:unknown-fact-types` defect in `shared/compose.clj` as part of this work
- [x] No Neovim in this branch; contract and editor layering keep it a renderer
- [x] Fold both review rounds into this doc: `:seen?`, `:unit` plumbing, cursor
      resolution, root `:production` semantics, producer dedupe, leaf kinds,
      Emacs path keys, and the concrete test matrix (§6.1). This doc now stands
      alone; the review docs are superseded.
- [x] 1 Rename/delete POC vocabulary — `lhs-producers` (navigate, schema, client,
      bb `--lhs-producers`, its tests) and the elisp treemacs view are gone;
      only subtype-sense "hierarchy" names remain
- [x] 2 Fix composed `:unknown-fact-types` + `:unit` on `ProductionDep` /
      `NavigateTarget` — `->composed-slim` now subtracts directly-referenced
      types; `_compose` `meta.edn` regenerated (manifest churn reverted, only the
      slim block kept); `compose_test` pins both bridge types `:known true` and
      dep `:unit`; registry-mode `navigate` carries `:unit`, live mode omits it
- [x] 3 Shared `fact-type-provenance` (both directions, bridge, `:cycle?`, `:seen?`, depth) —
      `resolve-cursor-fact-type` + `type-name->short-name` + direction-aware builders in
      `navigate.clj`; `navigate_test.clj` pins N1–N12 (hand-built analyses through the real
      rehydrate closures)
- [x] 4 Provenance schemas — `FactTypeProvenanceInput` / recursive node / result+response in
      `schema.clj`, N1–N12 run under `st/validate-schemas`
- [x] 5 JVM `client/fact-type-provenance` (live + registry tests) — same system/cache/log
      shell as `navigate`; R1–R6 + the existing R7 regression in `registry_server_test.clj`
      (composed two-unit selection, class tokens resolved via loaded fixture namespaces), and
      L1–L5 in `client_test.clj`
- [x] 6 bb `--provenance` + parity test — `--provenance '<selection>' '<input>'` answers
      `navigate/fact-type-provenance`; B1 compares the five registry-mode trees to the JVM answer
      (sources stripped), B2 compares a variant unit to its mainline, B3 pins the `:full` node
      count against the JVM and the process finishing
- [x] 7 Emacs `:not` wrapper fallback + `--prepare-query` + op runner refactor —
      `--prepare-query` gathers context/transport/token and sends nothing (the query
      guard now lives in the consumer navigate command); `--op-result` +
      `--op-code`/`--bb-provenance` dispatch both ops; `--edn-value` prints nested
      plists as maps; wrapper heads and brackets resolve the first child
- [ ] 8 Emacs provenance view, both commands, built-in tree
- [ ] 9 Docs + bb header + `editor/AGENTS.md`
- [ ] 10 Full verification

## 8. Verification

```bash
cd explorer
make test
make lint
make reflection-check
make format-check
make bb-smoke-test

cd ../editor/emacs
make test-tier1
make test
make check-elisp
```

Then a direct smoke check of the new contract over the composed two-unit
registry — producer root on `app-outcome-denied?` (the rule that self-loops
through `ApplicationOutcome`):

```bash
bb explorer/bin/editor-client.bb --provenance \
  '{:root "explorer/test-resources/rules-annos" :units [{:repo "loan-app-ruleset"} {:repo "loan-disposition-ruleset"}]}' \
  '{:node {:kind :production :name "clara.explorer.test.rules.loan-app-rules/app-outcome-denied?"}
    :direction :producer :depth 0 :ancestors []}'
```

Expect: a root `:production` node with `:unit "loan-app-ruleset"` and **no**
`:matches` / `:via` / `:bridge?` (no parent type); its `ApplicationOutcome`
fact-type child carries a `:production` child `…/app-outcome-denied?` with
`:cycle? true`; the `app-outcome-approved?` / `app-outcome-pending?` siblings
are expandable, with `:matches` naming the record (`:bridge? true`).

And the consumer `:cursor` form at `:depth :full`:

```bash
bb explorer/bin/editor-client.bb --provenance \
  '{:root "explorer/test-resources/rules-annos" :units [{:repo "loan-app-ruleset"} {:repo "loan-disposition-ruleset"}]}' \
  '{:node {:kind :cursor
           :input {:caller-ns "clara.explorer.test.rules.loan-app-rules"
                   :token "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"}}
    :direction :consumer :depth :full :ancestors []}'
```

Expect: `…/notice-approved-app` and `…/notice-denied-app` with
`:matches` naming `:loan-app/application-outcome` (`:bridge? true`), the
fact-type nodes carrying `:type-short`, and `:unit "loan-disposition-ruleset"`;
`la/find-app-outcome` as a `(query)` leaf; `:seen?` markers where the DAG
reconverges.

The `:depth :full` run is also the size guard: it must finish well inside the
bb process budget, and B3 pins its node count so a regression that reintroduces
path-only cycle detection shows up as a count blow-up rather than a timeout.

If `make bb-smoke-test` fails because `bin/bootstrap.bb` cannot read
`~/.clojure/.cpcache` (a sandbox restriction, not a code defect), say so and run
the CLI against an explicit classpath instead; record the workaround in
`editor/AGENTS.md` as part of step 9.

## 9. Decisions (all resolved)

- Umbrella name: `fact-type-provenance`.
- Unbounded depth spelling: `:depth :full`, bounded by `:cycle?` (same path) and
  `:seen?` (already expanded earlier in the same response). No node budget.
- Input is strict: `:depth` required (integer ≥ 0, or `:full`), `:ancestors`
  required, `:node :kind` limited to `:cursor` / `:production`.
- Consumer-child presentation: one `:fact-type` node per type with `:vias`.
- Producer-child presentation: one `:production` node per production, `:via
  :retract` when it both inserts and retracts, `:vias` keeping both.
- Consumer leaves: query `[]` (`(query)`), `:no-output-types` rule `[]`
  (`(no outputs)`), `:unlinked-rule` `[]` + `:outputs :unknown`
  (`(outputs unknown)`), retract-only has retracted-type children.
- External fact type: `:external? true` when no producer has `:via :insert`,
  derived from vias and never from `:known`.
- `:not`/`:exists`/accumulator matches: treated as consumers (and as upstream
  LHS children in the producer tree); cursor on a logical wrapper resolves the
  inner type. v1 emits no `:polarity` label and documents that limitation.
- Composed `:unknown-fact-types` defect: fixed in `shared/compose.clj` in Step 2.
- `:matches` shown only when `:bridge?` is true, which the backend decides;
  short names ride in the contract (`:type-short`, `:matches` `:short`), never
  derived in an editor. Production unqualified names are the exception: they
  are mechanical prefix removal from `:ns`, so v1 keeps that in the editors.
- Ghost / hierarchy-only types are valid roots; nothing special-cases them.

## 10. Deferred

- **Neovim renderer** — the contract is ready; add it as a renderer only.
- **LSP integration** — explicitly out of scope for this work.
- **REPL-free bb token resolution** — the enabling change for running without
  CIDER/Conjure at all. Not needed for this feature; it remains a separate
  follow-up.
- **`:unqualified-name` on navigate targets and production nodes** — additive
  and response-only: `ProductionDep`s are rebuilt from name strings on
  rehydrate and never persisted (`dep-graph.edn` holds fq-name sets,
  `production-index.edn` holds production summaries, and slim drops the
  fact-type usage vectors). The editors derive the value from `:ns` today, so
  v1 leaves it there; adopt the field when the second renderer is touched.
- **`:name` semantic cleanup** — on production shapes `:name` holds the
  *qualified* name while Clojure's `name` is the unqualified part, and `:type`
  is the production kind. Renaming (`:name` → `:fq-name`, `:name` for the local
  part, `:type` → `:kind`) touches persisted artifacts (`production-index.edn`,
  `dep-graph.edn` keys), the HTTP graph API, the UI types, both editors, and
  `route-id` inputs — its own change, with a migration path.
- **`:polarity` labels** (`:positive` / `:negated` / `:exists` / `:accumulated`)
  on production nodes — §2.3 ships without them. Adding them means reading the
  persisted `production-conditions.edn` part in `bin/editor-client.bb`, which is
  its own change with its own parity test.
