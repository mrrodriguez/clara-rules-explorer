# Annotation Layering & Merging — Update Plan

Status: **proposal**. Nothing here is implemented yet. This is a greenfield redesign of the
annotation format and its merge semantics; it is not constrained by the current shapes and does
not preserve them.

Scope: `clara.server.tools.graph.annotations` (format + merge), with touch points in
`analyze` / `analyze.callsite` (callsite identity, resolution vocabulary),
`tools.graph.core` (annotation lookup during analysis), `graph.server` / `graph.main`
(loading layers), and `docs/rule-annotations.md`.

Related reading: [`rule-annotations.md`](rule-annotations.md) (the annotation contract as it
stands today), [`analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md) (how the detection
maps are produced).

---

## 1. The problem

`generate-annotations-from-analysis` resolves most `insert!`/`retract!` callsites
deterministically. It does not resolve all of them, and it is not supposed to: the resolution
chain deliberately stops where it would have to guess (helper-built values, macro-emitted
locals, literals, config-driven types — see `analyze.callsite`'s docstring). Those callsites are
captured honestly as unresolved, and the rule's dimension comes out `:none` or `:partial`.

A large share of that residue is trivially resolvable *by reading the source for ten seconds*.
That is work a person — or an LLM agent — can do and a static analyzer cannot.

There is nowhere durable to put the answer.

- Rule `:props` work, but require editing the rules repo, and cannot express per-callsite
  reasoning.
- A sidecar file works, but the analyzer's own output is *also* a sidecar file, and the natural
  workflow — regenerate after a code change — overwrites it.
- `merge-annotations` exists, but its semantics (§3) actively punish sparse hand-written layers:
  an overlay entry that mentions a rule but omits its detection map **deletes that rule's
  callsite audit trail**.

The practical consequence, in a project that built a curation workflow on the current API: to
curate *one* callsite you must copy the *entire* generated entry into the overlay, verbatim,
because anything you leave out is destroyed by the merge. A curated overlay for a 114-rule
session came out as 39 near-duplicated entries, one of which had an actual edit. That overlay is
unreviewable, which defeats the purpose of keeping it separate.

### 1.1 Motivating example

A rules project with a helper constructor:

```clojure
(ns acme.facts)

(defn make-fact [type m] (assoc m :fact/type type))
```

```clojure
(ns acme.pricing
  (:require [clara.rules :refer [defrule insert!]]
            [acme.facts :as f]))

(def ^:private tier->type
  {:gold :acme.pricing/gold-discount
   :std  :acme.pricing/std-discount})

(defrule discount-rule
  [Order (= ?tier tier) (= ?total total)]
  =>
  ;; the type is a runtime lookup — nothing static can name it
  (insert! (f/make-fact (tier->type ?tier) {:total ?total})))
```

`make-fact` is registered as a constructor of interest, so the callsite is *found*, but its
first argument is not a literal, so it is not *resolved*. A human reads `tier->type`, sees the
closed set, and knows the answer is `[:acme.pricing/gold-discount :acme.pricing/std-discount]`.
Two graph edges, unavailable to the analyzer, obvious to a reader. **That answer should be
written down once, in a reviewable place, and survive the next generation run.**

---

## 2. Requirements

1. **Regeneration is safe.** Re-running generation may clobber only generated artifacts. Curated
   knowledge lives in its own layer and is never written by the generator.
2. **The work list is computed.** Given the current merged state, a function returns what is
   still unresolved. Nothing has to be staged on disk beforehand and kept in sync.
3. **Layers are sparse.** A layer contains only what its author actually asserted. Omission means
   "no opinion". Deletion must be expressible, but only explicitly.
4. **Restating a full callsite set stays meaningful.** When a curator works through a rule's
   callsites, restating all of them — including the ones they could *not* resolve — is the honest
   record of the pass. Supported and encouraged; never *required* for correctness.
5. **N layers, caller-ordered.** Generated, machine-curated, human-curated, rule `:props`, and
   prior state worth keeping are all just layers.
6. **Merging is a pure function.** The caller decides whether, where, and in what form to
   persist. The library does not own a file layout.
7. **Conclusions are derived, not authored.** A curator supplies evidence; the library derives the
   rule-level types and the aggregate resolution. This is what keeps graph edges from drifting
   away from the evidence that justifies them.
8. **Provenance is first-class.** Which layer an assertion came from travels *with* the
   annotations, in the format itself — not as something a caller reconstructs at runtime.

---

## 3. What exists today, and what's wrong with it

All references: `server/src/clara/server/tools/graph/annotations.clj`.

**F1 — `merge-rule-fields` drops the detection maps unless the top layer restates them**
(`annotations.clj:81-87`). The merged map is built from `{}`;
`:clara-rules/dynamic-insert-types-detected` survives only via `(contains? val2 …)`. Omission
deletes. This is the single reason curated overlays must be verbose.

**F2 — `merge-rule-fields` silently drops every key it doesn't know about**
(`annotations.clj:68-87` is a closed `cond->` over six keys). A layer carrying `:acme/reviewed-by`
or any future annotation key loses it — but only for rules present in *both* layers, because
rules present only in the top layer are `assoc`'d raw (`annotations.clj:191`). Same input, two
behaviors depending on overlap.

**F3 — Detection maps merge as whole values**, so callsite-level curation is impossible. There is
no notion of "this callsite, in this rule, in this dimension".

**F4 — `:resolution` is a merge input.** Whatever the top layer says wins, even when it
contradicts the callsites in the very same map. Nothing recomputes it.

**F5 — `:clara-rules/no-output-types`: docs and code disagree.** `rule-annotations.md` says
"`true` if declared as `true` in either source"; `resolve-no-output-types`
(`annotations.clj:37-41`) is last-declared-wins. Last-declared-wins is the right semantic — a
curator must be able to say "no, this rule *does* produce facts" — so the doc is what changes.

**F6 — the two-source model is hardcoded.** `resolve-annotations` (`annotations.clj:209`) knows
exactly two sources, "props" and "sidecar", and reports `:annotation-sources` in those terms. It
is already an N-layer merge with N pinned at 2.

**F7 — `write-annotations!` binds `*print-meta*` true** (`annotations.clj:195-201`), so reader
metadata from synthesized analysis snippets is emitted onto symbols in the output —
`^{:row 1 :col 5 :end-row 1 :end-col 12} some.ns` in an artifact whose positions refer to a
snippet that does not exist on disk. Noise at best, misleading at worst. (`docs/todos.md` item 1.)

**F8 — provenance is a return value, not part of the data.** `resolve-annotations` computes
`:annotation-sources` per production, per request. Nothing persists which source an assertion
came from, so an artifact on disk cannot answer "who claimed this?".

---

## 4. Data model

Schemas are `schema.core`, matching `analyze.callsite`. Two naming conventions, applied
deliberately:

- **Rule annotation keys stay `:clara-rules/`-qualified.** They must coexist with arbitrary user
  keys inside a rule's `:props` map, which is a genuinely mixed-purpose map. One vocabulary for
  both places is worth more than shorter keys in the file.
- **Everything else is unqualified.** A layer map is only ever about a layer; a callsite entry is
  only ever about a callsite; a `merge-props` map is only ever keyed by annotation key. There is
  nothing to disambiguate, so qualification would be noise.

### 4.1 Layer — the annotation format

A layer *is* the artifact. Provenance is not something a caller wraps around a bare map at load
time; it is part of what gets written down (requirement 8, **F8**).

```clojure
(s/defschema LayerId (s/cond-pre s/Keyword s/Str))

(s/defschema Layer
  "One annotation source, in the form it is written and read.  `:annotations`
   is the payload; everything else describes where it came from and how it
   wants to be merged."
  {:id                            LayerId
   :annotations                   {RuleName RuleAnnotation}
   (s/optional-key :source)       s/Any      ; free-form provenance: a path string,
                                             ;   :rulebase-props, {:generated-from …}, …
   (s/optional-key :merge-props)  MergeProps ; layer-wide defaults (§5.1)
   (s/optional-key :notes)        s/Str})
```

`:source` is descriptive only — never interpreted, never resolved. A layer read from a file gets
its path; a layer built in memory gets whatever the caller finds useful, or nothing. **Layers are
plain values**: an in-memory layer is a first-class input everywhere a file-backed one is, and no
API requires an intermediate file.

```clojure
(ann/layer {:id :curated :annotations annos})            ; in memory, no file involved
(ann/read-layer "curated-annos.edn" {:id :curated})      ; :source defaults to the path
(ann/props-layer session)                                ; from the rulebase (§5.5)
```

`RuleName` is `s/Str` — normalized on the way in, as today.

### 4.2 Rule annotation

```clojure
(s/defschema RuleAnnotation
  "Open map: unknown keys are preserved through merges (fixes F2).  Every value
   is `s/maybe` because an explicit nil is a tombstone (§5.4) — distinct from the
   key being absent, which means 'no opinion'."
  {(s/optional-key :clara-rules/insert-types)                    (s/maybe [FactType])
   (s/optional-key :clara-rules/retract-types)                   (s/maybe [FactType])
   (s/optional-key :clara-rules/no-output-types)                 (s/maybe s/Bool)
   (s/optional-key :clara-rules/notes)                           (s/maybe s/Str)
   (s/optional-key :clara-rules/dynamic-insert-types-detected)   (s/maybe DetectionMap)
   (s/optional-key :clara-rules/dynamic-retract-types-detected)  (s/maybe DetectionMap)
   (s/optional-key :clara-rules/merge-props)                     MergeProps
   s/Any                                                         s/Any})
```

`FactType` is `s/Any`, unchanged: the analyzer is type-agnostic and token shape is the caller's
decision.

### 4.3 Resolution vocabulary

One three-valued vocabulary, used at both levels:

```clojure
(s/defschema Resolution (s/enum :none :partial :full))
```

| value | on a callsite | on a dimension |
|---|---|---|
| `:full` | every type this callsite can produce is known | every callsite is `:full` |
| `:partial` | some types are known; there may be more | mixed — some knowledge, not complete |
| `:none` | nothing is known | every callsite is `:none` |

This replaces the current `:resolved` / `:resolved-multi` / `:unresolved` statuses.
`:resolved-multi` is not a distinct kind of answer — it is `:full` with more than one type — and
collapsing it frees `:partial` to mean the thing that actually needed saying: *"I found two of
the types this callsite emits and I am not certain there aren't more."* The analyzer emits
`:full` and `:none` today; `:partial` becomes reachable mainly through curation, and through any
future resolver that can enumerate part of a set.

Dimension aggregation, replacing `callsite/resolution-status`:

```
no callsites          → the dimension is absent entirely
all :full             → :full
all :none             → :none
otherwise             → :partial
```

Quarantined callsites (§5.6) are excluded from the aggregate as well as from type derivation.

### 4.4 Callsite entry and identity

```clojure
(s/defschema CallsiteEntry
  {:callsite-id                              CallsiteId

   ;; ---- discovery: only the analyzer produces these ----
   (s/optional-key :source-str)              s/Str
   (s/optional-key :ns-name-sym)             s/Symbol
   (s/optional-key :filename)                s/Str
   (s/optional-key :constructor-sym)         s/Symbol
   (s/optional-key :via)                     ViaChain
   (s/optional-key :fact-type)               s/Any
   (s/optional-key :fact-type-spec)          {s/Keyword s/Any}

   ;; ---- conclusion: analyzer or curator ----
   :status                                   Resolution
   (s/optional-key :resolved-types)          [FactType]
   (s/optional-key :resolution-evidence)     ResolutionEvidence

   ;; ---- computed by the merge; never authored ----
   (s/optional-key :from-layer)              LayerId   ; who supplied the conclusion
   (s/optional-key :dangling?)               s/Bool})  ; §5.6
```

```clojure
(s/defschema ResolutionEvidence
  "Why a callsite's conclusion is what it is.  Open, so consumers can add their
   own keys; `:note` is the only one the library reads, and it reads it only to
   display it."
  {(s/optional-key :note) s/Str
   s/Any                  s/Any})
```

`:resolution-evidence` is **optional everywhere**. Generated callsites carry none — `:via` and
`:constructor-sym` already say how the analyzer got there. It exists for the case where the
justification is a sentence rather than a call chain, which is exactly when a human is involved.

A map rather than a bare string, because prose is the *first* thing anyone records, not the only
one: `:note` covers today, and a later `:kind`, `:location`, `:confidence`, or reviewer id slots
in without a format change or a migration.

#### Identity

Identity is only needed **within one rule and one dimension** — the rule name and dimension key
already address the callsites vector, and it typically holds one to three entries. Global
uniqueness is neither needed nor attempted.

```
acme.pricing:make-fact:a3f19c2b:0
└── ns ────┘ └─ ctor ┘ └ hash ┘ └ ordinal
```

- **`ns`** — `:ns-name-sym`. Namespace rather than filename: it is the same information (kondo
  reports one namespace per analyzed source, and synthesized sources derive their filename from
  the ns name at `analyze.clj:127-129`), but it is more legible and carries no
  relative-vs-absolute or path-separator questions. Two callsites in one rule genuinely can come
  from different namespaces — a constructor-path callsite records the *helper's* namespace, not
  the rule's — so this segment is load-bearing, not decoration.
- **`ctor`** — the short name of `:constructor-sym`, or `-` for a boundary-path callsite that has
  none. Legibility only; the hash covers the fully-qualified symbol.
- **`hash`** — first 8 hex of SHA-256 over `(pr-str [ns-name-sym constructor-sym source-str])`.
  Spelled out so it is reproducible by anything, not just this JVM.
- **`ordinal`** — 0-based index within the *duplicate group* (§ below).

What is deliberately **out** of the basis:

| excluded | why |
|---|---|
| `:status`, `:resolved-types` | precisely what curation changes; including them changes the id the moment someone answers it |
| `:via` | the callstack shifts when an unrelated intermediate helper is renamed — that must not invalidate a curated answer about this form |
| `:row` / `:col` | churn on every edit above them in the file, and are meaningless for synthesized sources (**F7**) |
| `:filename` | equivalent to `:ns-name-sym`, less legible (see above) |

Collisions are **detected at emission**, within the rule+dimension group, and resolved by
lengthening the hash. Ids are unique by construction, not by probability. The one place the
ns↔file equivalence could break — a namespace loaded from more than one file — degrades into
exactly this case and is handled the same way.

#### The ordinal

Two sibling callsites in the same rule and dimension can share namespace, constructor, and
source text — the same insert in two branches of a `cond`. The ordinal separates them, and it is
the index **within the duplicate group**, not within the callsites vector. That distinction is
the point: adding or deleting *other* callsites in the same rule does not renumber the group.
Most groups have one member, so most ordinals are `0` and never change.

**The ordinal is not stable under edits to the duplicate group itself.** Delete the first of two
identical callsites and the survivor becomes `:0`, inheriting curation written for the deleted
one. Three things bound that, and none of them is "it can't happen":

1. The exposure is narrow — textually identical siblings, same namespace, same rule, same
   dimension.
2. In that case the two forms *are the same source text*, so the correct answer is nearly always
   the same for both; a mis-attribution is usually benign rather than wrong.
3. It is detectable. `validate-layers` computes group sizes from the discovered callsites and
   warns whenever a curating layer references an id whose group has more than one member
   (`:ambiguous-callsite-reference`, §7.2). Review catches what the mechanism cannot guarantee.

A project unwilling to accept even that curates the dimension wholesale — `:replace` on the
detection key, or a tombstone plus authored types (§5.4) — and opts out of identity entirely.

#### Who computes ids

Only the layer that *discovered* the callsites derives ids; it has the full entries. A curating
layer does not derive anything — it **carries** the id, copied from the work-list report (§7.1).
Ids are derived on read for any entry that omits one, so hand-written layers need not compute
hashes by hand as long as they supply enough of the basis.

```clojure
(ann/callsite-id callsite)        ; ns:ctor:hash — no siblings needed, no ordinal
(ann/assign-callsite-ids callsites) ; the only place ordinals can be computed
```

### 4.5 Detection map

```clojure
(s/defschema DetectionMap
  {:callsites  [CallsiteEntry]
   :resolution Resolution})       ; derived (§4.3), never authored — fixes F4
```

### 4.6 Merged annotations

```clojure
(s/defschema MergedAnnotations
  "The output of `merge-layers`.  Carries the payload plus enough provenance to
   answer 'which layer claimed this?' without re-running the merge."
  {:annotations {RuleName RuleAnnotation}
   :layers      [{:id LayerId (s/optional-key :source) s/Any}]  ; precedence order, lowest first
   :provenance  {RuleName {s/Keyword Origin}}})                 ; keyed by annotation key

(s/defschema Origin
  "Where a merged value came from: one layer, several (for keys merged by
   union), or the derivation pass (§5.7) rather than any layer."
  (s/cond-pre (s/eq :derived) LayerId [LayerId]))
```

`:provenance` records, per rule and per annotation key, where the value came from. Per-callsite
provenance lives on the entry as `:from-layer` (§4.4) rather than being duplicated here.

`(ann/annotations merged)` unwraps to the bare `{RuleName RuleAnnotation}` map for consumers that
do not care — `rulebase-analysis`, the API handlers.

### 4.7 Merge strategy declarations

```clojure
(s/defschema MergeProps
  "Per-key merge strategy overrides.  Keys are the unqualified names of
   annotation keys."
  {(s/optional-key :insert-types)                    (s/enum :union :replace)
   (s/optional-key :retract-types)                   (s/enum :union :replace)
   (s/optional-key :notes)                           (s/enum :replace :append)
   (s/optional-key :dynamic-insert-types-detected)   (s/enum :deep :replace)
   (s/optional-key :dynamic-retract-types-detected)  (s/enum :deep :replace)})
```

---

## 5. Merge semantics

```clojure
(ann/merge-layers layers)
(ann/merge-layers layers opts)
```

`layers` is ordered **lowest precedence first**; the fold is left-to-right, so the rightmost
layer wins a conflict. A typical ordering:

```clojure
[(ann/props-layer session)                          ; authored on the rules themselves
 (ann/layer {:id :generated :annotations auto})     ; generate-annotations-from-analysis
 (ann/read-layer "curated.edn" {:id :curated})      ; machine-assisted curation
 (ann/read-layer "reviewed.edn" {:id :reviewed})]   ; final human word
```

Nothing in the library privileges those ids. Rule `:props` being just another layer is what
retires the hardcoded two-source model (**F6**).

**Layer ids must be distinct.** `merge-layers` throws on a repeated `:id` rather than
disambiguating positionally: with two layers named the same, `:provenance` and `:from-layer` both
become ambiguous, and the merged artifact would name a source that does not identify anything.
Two files playing the same role in one fold are two layers and need two ids — `:curated-core` and
`:curated-pricing`, not `:curated` twice.

### 5.1 Where a merge strategy comes from

Highest wins:

1. `:clara-rules/merge-props` on the **rule entry** in the upper layer — a per-rule override.
2. `:merge-props` on the **upper layer** itself — the layer's default for every rule it touches.
3. The built-in default for that key (§5.2).

A layer that wants `:replace` semantics throughout declares it once at the layer level instead of
repeating it per rule. `merge-props` is a directive consumed by the merge step; it is not emitted
into the merged output.

### 5.2 Per key

Upper layer `b` over accumulated `a`:

| Key | Default | Available |
|---|---|---|
| `:clara-rules/insert-types` / `:retract-types` | union, `a` first | `:replace` |
| `:clara-rules/no-output-types` | last declared wins (**F5**) | — |
| `:clara-rules/notes` | last declared wins | `:append` (newline-joined) |
| `:clara-rules/dynamic-*-types-detected` | **deep merge by callsite id** | `:replace` |
| `:clara-rules/merge-props` | consumed as a directive; not emitted | — |
| anything else | last declared wins, **preserved** (fixes **F2**) | — |

A layer that omits a key expresses no opinion and leaves `a`'s value intact — including the
detection maps (fixes **F1**).

### 5.3 Deep merge of a detection map

- Callsites are keyed by `:callsite-id`. The union of ids is kept, in `a`'s order, with `b`-only
  entries appended.
- For an id in both, `b`'s declared fields win field-by-field. A curator writing
  `{:callsite-id "…" :status :full :resolved-types [...] :resolution-evidence {:note "…"}}` keeps the
  analyzer's `:source-str`, `:constructor-sym`, and `:via` without restating them, and the merged
  entry records `:from-layer` for the conclusion.
- `:resolution` is not merged. It is recomputed from the merged callsites (§4.3, fixes **F4**).

Requirement 4 falls out for free: restating every callsite of a dimension is just a merge where
`b` declares all the ids. It costs nothing and reads as a complete record of the pass —
recommended, not required.

### 5.4 Deleting, overruling, and short-circuit curation

Sparse merging needs a way to say "remove this", distinct from saying nothing. **An explicit `nil`
is a tombstone, on any key.**

```clojure
{"acme.pricing/discount-rule"
 #:clara-rules{:dynamic-insert-types-detected nil}}   ; erase; absent would mean "no opinion"
```

A tombstone is unconditional — it needs no `:merge-props`, since there is no strategy to pick
between. A later layer may re-establish the key; erasure is not permanent, it is just this
layer's word.

Together with `:replace`, that covers overruling a value a lower layer got wrong — including one
authored in the rules source itself. Given a rule whose `:props` declare a mistaken type:

```clojure
;; substitute: the type is Y, not X
#:clara-rules{:insert-types [:acme.pricing/gold-discount]
              :merge-props  {:insert-types :replace}}

;; erase: there is no insert type here at all
#:clara-rules{:insert-types nil}
```

Either shape, in any layer above the props layer, wins — and derivation will not put the old value
back, because it reads the merged result rather than re-consulting the layers (§5.7). The one way
`X` returns is if a callsite independently resolves to it, which is a statement about the *code*,
not about the annotation, and is fixed by curating that callsite.

That makes the short-circuit case expressible too: a consumer who does not care about the callsite
machinery and simply wants to assert the answer writes

```clojure
{"acme.pricing/discount-rule"
 #:clara-rules{:insert-types [:acme.pricing/gold-discount :acme.pricing/std-discount]
               :merge-props  {:insert-types :replace}
               :dynamic-insert-types-detected nil}}
```

and the merged rule carries two types, no callsites, and no resolution — indistinguishable from a
rule whose author declared its types in `:props`. Which is the point: **a conclusion with no
evidence is a legitimate annotation shape, not a degenerate one.** Everything downstream
(`rulebase-analysis`, the dep-graph, the API) reads types; the detection maps are the audit trail
for how the analyzer got them, and a layer is entitled to say the audit trail is not worth
keeping.

`:type-derivation :from-callsites` (§5.7) does not fight this: with no callsites for a dimension,
there is nothing to derive from and the authored types stand.

### 5.5 Rule `:props` as a layer

`:props` are annotations authored on the rule form itself, read off the compiled production. They
carry conclusions and never callsites — the same shape §5.4 just described.

```clojure
(ann/props-layer session-or-rulebase)
;; => {:id :props
;;     :source :rulebase
;;     :annotations
;;     {"acme.pricing/discount-rule"
;;      #:clara-rules{:insert-types [:acme.pricing/gold-discount] :notes "tier pricing"}}}
```

**The whole `:props` map is copied — nothing is filtered.** A rule's props may hold keys that mean
nothing to the analyzer, and that is fine in both directions: annotations were always intended as
an *extension* of what a rule can declare in `:props`, and these files are worth more long-term if
they can carry more than type derivations. Unknown keys are preserved through every merge
(**F2**), reach consumers untouched, and break nothing — so there is no reason to decide, at the
layer boundary, which of an author's keys deserve to survive.

They participate in exactly the same fold as everything else, with two consequences worth stating
because they are the whole answer to "how do props fit":

- **Position is the caller's choice.** Placing the props layer first (the convention) makes
  source-authored types the base that generated and curated layers add to; a caller who wants
  source to be the final word places it last. There is no built-in precedence.
- **Props are the base, and are correctable.** Placed first, their types are what later layers
  add to by default. A layer that needs to overrule one does so the same way it would overrule
  any other layer — `:replace` to substitute, a tombstone to erase (§5.4) — so "props are
  generally right and we keep them" and "a mistake in props can be invalidated without editing
  the rules repo" are both true at once.
- **Derivation does not resurrect them.** Derivation reads the merged annotation, not the layers
  (§5.7), so a props type that a later layer replaced or erased stays gone. Under
  `:type-derivation :additive` a props type with no callsite backing simply survives; under
  `:from-callsites` it survives for any dimension with no detection map, and is dropped — visibly
  (§7.2) — for one that has a detection map that does not support it.

`resolve-annotations`' two-source model, its `:annotation-sources`, and its
`:resolved-annotation-data` all disappear into this: per-key sources are `:provenance` (§4.6), and
`tools.graph.core`'s per-production lookup (`core.clj:87,286`) becomes a read of the merged map.

### 5.6 Dangling references

Only the analyzer *discovers* callsites; every other layer *annotates* ones that already exist.
So the property is self-describing in the merged output and needs no comparison between layers:

> A merged callsite entry is **dangling** when it has no discovered form — no `:source-str`.

If a layer's id matched something, the merge filled in `:source-str`, `:ns-name-sym`,
`:constructor-sym`, and `:via` from the entry that had them. If it matched nothing, the merged
entry is exactly what that layer wrote — an id, a status, some types, maybe an evidence string —
and carries no discovered form. One map, inspected on its own.

This is not specific to curation. Any layer can dangle: a hand-written file with a typo'd id, a
layer written against an older revision of the rules, or a stale generated layer merged beneath a
newer one. The report names the layer responsible (§7.1); it does not assume which one it is.

It also distinguishes two things that would otherwise look identical:

- **dangling** — an assertion about a callsite nothing reports.
- **layer-introduced** — an assertion about a callsite the analyzer missed entirely, where the
  layer supplies `:source-str`/`:ns-name-sym` itself. Legitimate, and not dangling.

`:on-dangling` policy:

| value | behavior |
|---|---|
| `:quarantine` (default) | keep the entry, mark `:dangling? true`, exclude it from type derivation **and** from the dimension's resolution aggregate, report it. A stale assertion never silently produces a graph edge, and is never silently discarded |
| `:keep` | treat as an ordinary entry; its types count |
| `:drop` | remove it |

Quarantine is cheap to recover from, which is why it is the default: after a source edit the
report shows *both* the dangling entry (still carrying its evidence text) and the new unresolved
callsite with the new form, side by side. For that reason a curating layer should carry
`:source-str` as a redundant witness — one line, ignored by the merge whenever a discovered entry
supplies one, and the only context left when the entry dangles.

### 5.7 Type derivation

After merging, one pass derives rule-level conclusions from the merged evidence:

- each dimension's `:resolution` ← the aggregation in §4.3;
- resolved types are promoted into `:clara-rules/insert-types` / `:retract-types`, which is what
  makes a curated callsite produce a graph edge without anyone hand-writing a type.

Derivation reads **only the merged annotation** — never the individual layers. That matters: a
pass that went back and re-unioned each layer's authored types would silently undo any `:replace`
or tombstone the merge had honored, reinstating exactly the values a layer took precedence to
remove.

So there are two inputs per rule and dimension, and `:type-derivation` picks how they combine:

- **`A`** — the merged authored types, i.e. whatever `:clara-rules/insert-types` survived §5.2's
  union / `:replace` / tombstone rules.
- **`D`** — the types promoted from the merged detection map's non-quarantined callsites.

| value | rule-level types become |
|---|---|
| `:additive` (default) | `A ∪ D` |
| `:from-callsites` | `D` for a dimension that has a merged detection map; `A` for one that does not |

Both modes respect precedence, because both start from `A`, which is already the merge's answer.
They differ only in whether the callsite record is allowed to be *authoritative*:

- **Correcting a wrong authored type** works in either mode. `:replace` (or a tombstone) removes
  it from `A`; it only comes back if a callsite in `D` independently supports it — in which case
  it is not the annotation that is wrong, it is the callsite, and that is the next thing to fix.
- **Correcting a wrong callsite** needs `:from-callsites`. Under `:additive`, downgrading a
  callsite the analyzer resolved wrongly cannot remove the type it already contributed to `D`;
  under `:from-callsites` it can, because `D` *is* the answer.

The cost of `:from-callsites` is the mirror image: for a dimension that has a detection map, an
authored type with no callsite backing it — including one from `:props` — is dropped. That is the
mode's whole point, and it is why `:additive` is the default. `validate-layers` reports each drop
(`:derivation-dropped-authored-type`, §7.2) so it is a visible consequence rather than a silent
one.

`derive-conclusions` is exposed separately and is idempotent, so a caller can re-derive after
hand-assembling annotations without re-merging.

---

## 6. API surface

`clara.server.tools.graph.annotations`, after the rewrite. This is greenfield: `merge-annotations`,
`load-sidecar`, `write-annotations!`, and `resolve-annotations` are **removed**, not deprecated.

| fn | signature | notes |
|---|---|---|
| `layer` | `[m]` | construct + validate an in-memory `Layer` |
| `read-layer` | `[path] [path m]` | EDN → `Layer`; `:source` defaults to `path` |
| `write-layer!` | `[path layer]` | pretty EDN, **no `*print-meta*`** (**F7**) |
| `props-layer` | `[session-or-rulebase]` | the rule-`:props` layer (§5.5) |
| `merge-layers` | `[layers] [layers opts]` | the core; returns `MergedAnnotations` |
| `annotations` | `[merged]` | unwrap to the bare rule→annotation map |
| `provenance` | `[merged] [merged rule-name]` | who claimed what (§4.6) |
| `derive-conclusions` | `[annotations] [annotations opts]` | §5.7; idempotent |
| `callsite-id` | `[callsite]` | content hash, no ordinal (§4.4) |
| `assign-callsite-ids` | `[callsites]` | ids for one rule+dimension vector; ordinals need siblings |
| `unresolved-report` | `[merged]` | the work list (§7.1) |
| `validate-layers` | `[layers] [layers opts]` | pure lint (§7.2) |

`merge-layers` opts:

```clojure
{:type-derivation :additive          ; | :from-callsites   (§5.7)
 :on-dangling     :quarantine}       ; | :keep | :drop     (§5.6)
```

Deliberately **not** in the library: file naming, directory layout, when to persist. A caller who
wants a generated / curated / merged file trio builds it from these functions. That is policy, and
it differs per project.

Integration points:

- `server/start!` — `:annotations-file` becomes `:layers`, an ordered vector of paths or in-memory
  layers, folded through `merge-layers`. `POST /v1/annotations/reload` re-reads the file-backed
  ones.
- `graph.main --generate-analysis` — accept repeated layer arguments to merge *under* the freshly
  generated layer before running `rulebase-analysis`, so the CLI can produce a curation-aware
  analysis with no REPL.
- `tools.graph.core` — per-production annotation lookup reads the merged map plus `:provenance`
  (`core.clj:87,119,286`) instead of calling `resolve-annotations`.

---

## 7. Reporting and validation

### 7.1 The work list

```clojure
(ann/unresolved-report merged)
```

One argument, and it is the merged value — the report needs nothing to diff against, because
everything it reports is a property of the merged entries: a dimension's resolution, and whether
a callsite has a discovered form.

```clojure
{:summary {:rules 41 :callsites 60
           :by-resolution {:none 52 :partial 8}   ; callsite counts
           :dangling 1}

 :rules
 {"acme.pricing/discount-rule"
  {:insert {:resolution :none
            :callsites [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                         :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                         :ns-name-sym acme.pricing
                         :constructor-sym acme.facts/make-fact
                         :status :none}]}}}

 :dangling
 [{:rule "acme.pricing/legacy-rule"
   :dimension :insert
   :callsite-id "acme.pricing:make-fact:7d10e4aa:0"
   :from-layer :curated                              ; whichever layer wrote it
   ;; the layer's own words — all that survives when the form is gone
   :resolution-evidence {:note "…"}}]}
```

`:summary` counts rules and callsites, because those are the units of work. `:rules` is the
reading material for whoever does the resolving; their output is a new sparse layer, not an edit
to this.

### 7.2 Validation

```clojure
(ann/validate-layers layers)
(ann/validate-layers layers {:known-rule-names #{…}})
```

Pure. Returns `[{:severity :error|:warn :type … :layer … :rule … :dimension … :callsite-id …
:message …}]`.

| type | severity | meaning |
|---|---|---|
| `:unknown-rule` | error | a rule key matches no rule in the rulebase — a typo would otherwise merge in as a phantom entry |
| `:resolved-without-types` | error | `:status :full` or `:partial` with no `:resolved-types` |
| `:dangling-callsite` | warn | the merged entry has no discovered form (§5.6) |
| `:ambiguous-callsite-reference` | warn | the referenced id belongs to a duplicate group with more than one member, so its ordinal is positional (§4.4) |
| `:authored-derived-field` | warn | a layer hand-writes `:resolution`, `:from-layer`, or `:dangling?` — all derived; the value is ignored |
| `:derivation-dropped-authored-type` | warn | under `:from-callsites`, an authored type (often from `:props`) was dropped because the dimension's callsites do not support it (§5.7) |
| `:no-op-entry` | warn | the entry is identical to the merged state beneath it |

`:known-rule-names` is optional so validation works offline from artifacts alone; supply it from a
live session or an analysis file to enable `:unknown-rule`.

---

## 8. Worked example, end to end

Continuing §1.1.

**Generated layer**, rewritten on every run:

```clojure
{:id :generated
 :source {:generated-from "acme.pricing" :explorer-sha "…"}
 :annotations
 {"acme.pricing/discount-rule"
  #:clara-rules{:dynamic-insert-types-detected
                {:callsites [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                              :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                              :ns-name-sym acme.pricing
                              :filename "acme/pricing.clj"
                              :constructor-sym acme.facts/make-fact
                              :via {:boundary-var-name-sym clara.rules/insert!
                                    :callstack [{:var-name-sym acme.facts/make-fact}]}
                              :status :none}]
                 :resolution :none}}}}
```

**Step 1 — what is left.**

```clojure
(ann/unresolved-report (ann/merge-layers [generated]))
;; => …:callsites [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0" …}]…
```

**Step 2 — the curated layer.** This is the entire file:

```clojure
{:id :curated
 :source "curated-annos.edn"
 :annotations
 {"acme.pricing/discount-rule"
  #:clara-rules{:dynamic-insert-types-detected
                {:callsites
                 [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                   :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"
                   :status :full
                   :resolved-types [:acme.pricing/gold-discount :acme.pricing/std-discount]
                   :resolution-evidence
                   {:note "type comes from the closed map `tier->type` (acme/pricing.clj:8); both values enumerated"}}]}}}}
```

No `:constructor-sym`, no `:via`, no `:insert-types`, no `:resolution` — none of those are the
curator's to assert. `:source-str` is restated only as the witness that keeps the entry legible if
it later dangles (§5.6).

**Step 3 — merge.**

```clojure
(def merged
  (ann/merge-layers [(ann/props-layer session) generated curated]
                    {:type-derivation :from-callsites}))
```

The full merged shape — every field this design produces, in one place:

```clojure
{:layers [{:id :props     :source :rulebase}
          {:id :generated :source {:generated-from "acme.pricing" :explorer-sha "…"}}
          {:id :curated   :source "curated-annos.edn"}]

 :provenance
 {"acme.pricing/discount-rule"
  {:clara-rules/insert-types                  :derived
   :clara-rules/notes                         :props
   :clara-rules/dynamic-insert-types-detected [:generated :curated]}}

 :annotations
 {"acme.pricing/discount-rule"
  #:clara-rules{:insert-types [:acme.pricing/gold-discount :acme.pricing/std-discount] ; derived
                :notes "tier pricing"                                                  ; :props
                :dynamic-insert-types-detected
                {:callsites
                 [{:callsite-id "acme.pricing:make-fact:a3f19c2b:0"
                   :source-str "(f/make-fact (tier->type ?tier) {:total ?total})"      ; :generated
                   :ns-name-sym acme.pricing                                           ; :generated
                   :filename "acme/pricing.clj"                                        ; :generated
                   :constructor-sym acme.facts/make-fact                               ; :generated
                   :via {:boundary-var-name-sym clara.rules/insert!
                         :callstack [{:var-name-sym acme.facts/make-fact}]}            ; :generated
                   :status :full                                                       ; :curated
                   :resolved-types [:acme.pricing/gold-discount
                                    :acme.pricing/std-discount]                        ; :curated
                   :resolution-evidence {:note "type comes from the closed map …"}      ; :curated
                   :from-layer :curated}]
                 :resolution :full}}}}                                                 ; derived
```

Two graph edges now exist, each traceable to the sentence that justifies it and to the layer that
wrote it.

**Step 4 — regenerate.** An unrelated rule changes and generation re-runs. The generated layer is
rewritten; the curated layer is not touched by anything; the merge reproduces the state above. If
instead the `make-fact` call itself changed, its hash changes: the curated entry is quarantined
and reported, and the new form appears in the work list — the opposite of a stale conclusion
silently surviving.

---

## 9. Migration

There is no compatibility layer. Existing generated artifacts are regenerated; existing
hand-written sidecars are rewritten into the `Layer` shape by wrapping them in
`{:id … :annotations …}` and, if they carry detection maps, re-running
`assign-callsite-ids` + `derive-conclusions`. `docs/rule-annotations.md`'s "Sources of
Annotations" and "Annotation Merging" sections are rewritten against §4–§5, including the **F5**
correction.

## 10. Phasing — executable checklist

Status legend: `[ ]` pending · `[x]` done · `[~]` in progress. Every phase ends with a
**gate** step; do not check a phase complete unless its gate passes. Verification commands
are run from `server/` unless noted. If work stops mid-phase, the last unchecked step is the
resume point.

New API is built **alongside** the old during phases 1–5 (old tests keep passing); the old
functions are removed in phase 6, which is also where pre-existing tests and the
`test-resources/.../loan-doc-rules-annotations.edn` fixture are rewritten.

### Phase 1 — Format + merge core

`Layer`, `RuleAnnotation`, `MergedAnnotations` schemas; `merge-layers` with deep callsite
merge, tombstones, key preservation, layer-level and rule-level `merge-props`;
`write-layer!` without `*print-meta*`. Pure data — unit tests only.

- [x] 1.1 Schemas (`schema.core`, matching `analyze.callsite`): `LayerId`, `Layer`,
  `RuleAnnotation`, `Resolution`, `CallsiteEntry`, `ResolutionEvidence`, `DetectionMap`,
  `MergedAnnotations`, `Origin`, `MergeProps` (§4).
- [x] 1.2 `layer` / `read-layer` / `write-layer!` (§6). `read-layer` normalizes rule-name keys
  to strings and defaults `:source` to the path; `write-layer!` does **not** bind
  `*print-meta*` (**F7**).
- [x] 1.3 Per-rule merge: omission = no opinion (**F1**), unknown keys preserved for
  overlapping and non-overlapping rules (**F2**), explicit `nil` tombstones (§5.4),
  `merge-props` precedence rule-level → layer-level → default (§5.1), per-key defaults (§5.2).
- [x] 1.4 Deep detection-map merge keyed by `:callsite-id`: field-level win, id-keyed union,
  `a`-order then `b`-appended, `:from-layer` on the conclusion, `:resolution` recomputed via
  the §4.3 aggregation, never merged (**F4**). (Id derivation for entries that omit one is
  phase 3; phase 1 tests supply ids in the fixtures.)
- [x] 1.5 `merge-layers` fold: lowest precedence first, throws on repeated layer `:id`,
  builds `:layers` listing and per-rule/per-key `:provenance` (§4.6); `annotations` and
  `provenance` accessors.
- [x] 1.6 Unit tests (new `annotations_merge_test.clj` or similar — do not touch the legacy
  `annotations_test.clj` yet): **F1**, **F2**, tombstones on every mergeable key, short-circuit
  shape, callsite deep merge (field win / union / ordering / `:from-layer`), `:resolution`
  recompute (**F4**), aggregation across `:none`/`:partial`/`:full` combos, `merge-props`
  precedence, three-layer precedence + fold associativity, duplicate `:id` throw, round-trip
  `write-layer!` → `read-layer` fixed point with no reader metadata (**F7**).
- [x] 1.7 **Gate:** `make test format lint reflection-check` green.

### Phase 2 — Resolution vocabulary

Collapse `:resolved`/`:resolved-multi`/`:unresolved` to `:none`/`:partial`/`:full` in
`analyze.callsite` and the serialize/API layers; new aggregation.

- [x] 2.1 `analyze.callsite`: `:status` becomes `(s/enum :none :partial :full)`; emission sites
  (`callsite.clj` ~:232, ~:338) map empty→`:none`, resolved→`:full`; `resolution-status`
  becomes the §4.3 aggregate (moved to or shared with `annotations`).
- [x] 2.2 Sweep other emission/consumption sites: `analyze.clj` (:154–155, `extract-*` types),
  `serialize.clj` (:148–153), `graph/api.clj` (:74–81), `tools.graph.core`.
- [x] 2.3 Update `analyze_test.clj` and any other tests asserting the old statuses.
- [x] 2.4 **Gate:** `make test format lint reflection-check` green; no `:resolved`,
  `:resolved-multi`, or `:unresolved` literals remain outside historical docs
  (`grep -rn "resolved-multi\|:unresolved" src test`).

### Phase 3 — Callsite identity

`callsite-id`, `assign-callsite-ids`, emission on write, dangling policy (§4.4, §5.6).

- [x] 3.1 `callsite-id`: `ns:ctor:hash8` — SHA-256 over `(pr-str [ns-name-sym constructor-sym
  source-str])`, first 8 hex; `ctor` is the short name or `-`.
- [x] 3.2 `assign-callsite-ids` for one rule+dimension vector: duplicate-group ordinals;
  collision detection within the group with hash lengthening.
- [x] 3.3 Ids derived on read (`read-layer`) and on generation
  (`generate-annotations-from-analysis`) for any entry that omits one.
- [x] 3.4 Dangling policy in `merge-layers`: `:on-dangling` `:quarantine` (default) / `:keep` /
  `:drop`; quarantined entries get `:dangling? true` and are excluded from type derivation and
  the resolution aggregate; layer-introduced entries carrying their own `:source-str` are not
  dangling.
- [x] 3.5 Unit tests: id stability under unrelated edits, id change on callsite-form edit,
  distinct ordinals for duplicate siblings, no renumbering when unrelated callsites are
  added/removed, quarantine semantics, layer-introduced entries.
- [x] 3.6 **Gate:** `make test format lint reflection-check` green.

### Phase 4 — Reporting + validation

- [x] 4.1 `unresolved-report` (§7.1): `:summary` (`:rules`, `:callsites`, `:by-resolution`,
  `:dangling`), `:rules` reading material, `:dangling` entries with `:from-layer` and
  `:resolution-evidence`.
- [x] 4.2 `validate-layers` (§7.2): six problem classes, optional `:known-rule-names`.
  (`:derivation-dropped-authored-type` needs derivation — implemented and tested in phase 5.)
  Note: `:authored-derived-field` on a detection-map `:resolution` fires only for
  non-discovering layers — the analyzer legitimately writes the `:resolution` it derived.
- [x] 4.3 Unit tests: report counts and shape; each validation class fires (and only when it
  should).
- [x] 4.4 **Gate:** `make test format lint reflection-check` green.

### Phase 5 — Derivation

- [x] 5.1 `derive-conclusions` (§5.7): recompute dimension `:resolution`; promote callsite
  types into `:clara-rules/insert-types` / `:retract-types`; `:additive` (default) vs
  `:from-callsites`; reads only the merged annotation; idempotent; exposed separately; provenance
  records `:derived` for promoted keys.
- [x] 5.2 Wire `:type-derivation` opt into `merge-layers`.
- [x] 5.3 Unit tests: both modes, downgrade case, props-survival clause (§5.5), no resurrection
  of replaced/tombstoned types (§5.7), idempotence, plus the
  `:derivation-dropped-authored-type` validation class (carried over from 4.2).
- [x] 5.4 **Gate:** `make test format lint reflection-check` green.

### Phase 6 — Integration

`props-layer` and the retirement of `resolve-annotations`; `server/start!` `:layers`;
`--generate-analysis` layer arguments; `tools.graph.core` lookup; docs. **This is the
breaking-change phase for pre-existing tests and fixtures.**

- [x] 6.1 `props-layer` (§5.5): whole `:props` map copied, no filtering; unit test that it
  composes at any fold position and carries non-`:clara-rules/` keys through untouched.
- [x] 6.2 Remove `merge-annotations`, `load-sidecar`, `write-annotations!`,
  `resolve-annotations`; rewrite `annotations_test.clj` against the new API (keep the
  normalization contract tests — `normalize-rule-name` / `normalize-annotations` /
  `get-annotation` stay).
- [x] 6.3 `tools.graph.core` (:87, :119, :286): per-production lookup reads the merged map +
  `:provenance`; `analyze.clj` (:921) same.
- [x] 6.4 `graph.server` `start!`: `:annotations-file` → `:layers` (ordered vector of paths or
  in-memory layers); `POST /v1/annotations/reload` re-reads file-backed layers.
- [x] 6.5 `graph.main --generate-analysis`: accept repeated layer arguments merged **under**
  the freshly generated layer; generated layer is written with ids and the new format.
- [x] 6.6 Regenerate `test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn`
  as a `Layer` (`{:id :generated :source … :annotations …}`) with `:callsite-id`s, new
  statuses, and derived conclusions; update `core_test.clj`, `api_test.clj`, `smoke_test.clj`,
  `source_sink_test.clj` to load it via `read-layer`.
- [x] 6.7 Docs: rewrote `docs/rule-annotations.md` "Sources of Annotations" and "Annotation
  Merging" against §4–§5, including the **F5** correction (last-declared-wins for
  `:no-output-types`); updated the detection-map example (`:callsite-id`, new statuses).
  **Follow-up (not done — file is out of scope for this session):**
  `../docs/explorer-graph-api.md` still documents the old contract (`:annotations-file`,
  `:annotation-sources`, `"resolved"`/`"resolved-multi"`/`"unresolved"` statuses, sidecar
  reload). It needs the same vocabulary swap: `:layers`, `:provenance`,
  `"full"`/`"partial"`/`"none"`, merged-annotations reload.
- [x] 6.8 **Gate:** `make test format lint reflection-check` green; `cd ../ui && pnpm run check`
  if the API contract changed.

### Phase 7 — Layer rebasing

- [x] 7.1 `rebase-layer`: remap callsite ids across a known old→new namespace mapping.
- [x] 7.2 Unit tests: rename mapping re-bases ids and re-hashes; unmapped namespaces pass
  through; ordinal groups preserved.
- [x] 7.3 **Gate:** `make test format lint reflection-check` green.

Phases 1–5 are self-contained and testable against fixture maps: no session, no rulebase, no
classpath.

## 11. Open questions

None blocking. The three that were open — the shape of `:resolution-evidence` (§4.4, a map),
which `:props` keys reach the props layer (§5.5, all of them), and duplicate layer ids (§5,
rejected) — are decided above.

Deferred by design, not undecided:

- **`rebase-layer`** — phase 7 (§10). Moving or renaming a namespace dangles every curated
  callsite in it, which is correct but tedious for a bulk rename.
- **Richer `:resolution-evidence`.** The map is open, so `:kind`, `:location`, `:confidence`, or a
  reviewer id can be added the day a consumer wants to group or filter on them. No format change
  required, which is why the decision could be deferred rather than guessed.

## 12. Testing

Pure-data unit tests over fixture layers — no session required:

- a sparse layer preserves the lower layer's detection map (**F1**);
- unknown keys survive for overlapping *and* non-overlapping rules (**F2**);
- an explicit `nil` erases where absence does not, on every mergeable key (§5.4), and the
  short-circuit shape yields types with no callsites;
- a mistaken `:props` type is overruled by `:replace` in a higher layer and by a tombstone, in
  **both** derivation modes — derivation must not resurrect it (§5.7);
- callsite deep merge: field-level win, id-keyed union, ordering, `:from-layer`;
- `:resolution` is recomputed and a contradictory authored value is ignored (**F4**);
- resolution aggregation across all `:none`/`:partial`/`:full` combinations (§4.3);
- `merge-props` precedence: rule-level over layer-level over default (§5.1);
- three-layer precedence, and associativity of the fold;
- id stability: an unrelated edit elsewhere in the namespace leaves ids unchanged; editing the
  callsite's own form changes its id; duplicate siblings get distinct ordinals, and
  adding/removing an unrelated callsite in the same rule does not renumber the group;
- dangling quarantine contributes no types and no resolution and is reported, while a
  layer-introduced callsite supplying its own `:source-str` is neither;
- `:additive` vs `:from-callsites`, including the downgrade case and the props-survival clause
  (§5.5);
- `props-layer` composes at any position in the ordering, and carries non-`:clara-rules/` props
  keys through the merge untouched (§5.5);
- `merge-layers` throws on a repeated layer `:id` (§5);
- `unresolved-report` counts and shape; `validate-layers` on each problem class;
- round-trip: `write-layer!` → `read-layer` → `merge-layers` is a fixed point, with no reader
  metadata in the output (**F7**).
