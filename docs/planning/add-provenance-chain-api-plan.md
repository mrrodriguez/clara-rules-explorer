# Add `provenance-chain` to the HTTP callsite API — Plan

Status: **Implemented.** Server (serialization + tests) and UI (render `provenance-chain`, delete `viaChain.*`) are done; docs updated.

Related: [`analyze-callsite-provenance-fixes-plan.md`](./analyze-callsite-provenance-fixes-plan.md)
(and its problem statement), which introduced the raw `:via` shape this plan
builds on.

Scope: server HTTP serialization (`serialize.clj`, `graph/api.clj`,
`graph/core.clj`) and the UI's callsite provenance rendering. The analyzer's
internal `:via` and the annotation-layer content are **unchanged**.

---

## 1. Summary

The UI currently composes the "Provenance chain" display from the raw `:via`
fields (`rule-to-boundary-path`, `boundary-var-name-sym`,
`boundary-to-constructor-path`) in `ui/src/lib/components/rulebase/viaChain.ts`
(`buildViaEntries`). That composition is ambiguous when `rule-to-boundary-path`
is absent — a boundary call written directly in the rule's own RHS. In that
case the rule *is* `boundary-in-var`, so it only surfaces as the head of
`boundary-to-constructor-path`, and the UI renders it labelled `caller` *after*
the boundary — reading as if `insert!` called the rule.

The server has the piece of context the UI never had: the rule's own name (the
production `:name` at serialization time). This plan moves chain composition to
the HTTP serialization layer, which anchors the chain with the known rule and
emits a flat, display-ready `provenance-chain` array. The UI renders that array
directly and stops parsing `:via`.

**`:via` stays on the HTTP API** — other API consumers depend on it.

## 2. Design decisions

1. **Additive, not a replacement.** Keep `:via` as the raw analyzer form; add
   `provenance-chain` as the display form. The analyzer and annotation layers
   are untouched — in particular, no change to *when* `rule-to-boundary-path`
   is emitted.
2. **Composition lives in `serialize.clj`.** It is the single choke point that
   already stringifies `:via`, and — via `core.clj` — has the production `:name`
   in scope.
3. **The rule is always the chain head.** It is synthesized from the known
   `(str p-name)`, never read from `rule-to-boundary-path[0]`, so the
   direct-RHS case needs no special handling.
4. **Uniform slicing around the shared join var.** Both paths are inclusive of
   `boundary-in-var`; the chain skips each path's head — the rule head (replaced
   by the synthesized rule) and the ctor-path head (`boundary-in-var`, already
   the rule-side tail).
5. **Traced chains only.** `provenance-chain` is emitted only when `:via`
   carries `:boundary-in-var` (the traced-chain marker). Heuristic
   `:record-ctor-scan` entries are left alone — no chain; `:via {:source
   :record-ctor-scan}` is preserved.
6. **Entry shape.** `{:label "rule"|"caller"|"boundary"|"constructor", :sym
   "fq.var/name"}` — exactly the shape the UI already renders, so the Svelte
   template is a near drop-in.
7. **UI deletes its composition code.** `viaChain.ts` + `viaChain.test.ts` are
   removed; the chain-composition unit tests migrate to `serialize_test.clj`.

## 3. The composition

```clojure
(defn- chain-entry
  "Builds one `provenance-chain` entry from a raw var symbol."
  [label var-sym]
  {:label label :sym (str var-sym)})

(defn- provenance-chain
  "Composes the display-ready provenance chain for a traced callsite `:via`.

   `rule-name` is the production's own fq name (already a string); the rule is
   always the chain head.  The analyzer's `:via` paths supply the rest:

     rule-to-boundary-path        [rule … boundary-in-var]
     boundary-var-name-sym        clara.rules/insert!
     boundary-to-constructor-path [boundary-in-var … constructor]

   Both paths are inclusive of the shared `boundary-in-var`, so each path's
   head is skipped: the rule head is replaced by the synthesized `rule-name`
   (the rule-side path is absent when the boundary call sits in the rule's own
   RHS), and the ctor-path head is `boundary-in-var`, already emitted as the
   rule-side tail.

   Returns nil for non-traced `:via` (heuristic `:record-ctor-scan` entries
   carry no `:boundary-in-var`), so those callsites emit no chain."
  [rule-name {:keys [boundary-var-name-sym
                     rule-to-boundary-path
                     boundary-to-constructor-path] :as via}]
  (when (:boundary-in-var via)
    (let [rule-hops (mapv :var-name-sym (rest (or rule-to-boundary-path [])))
          ctor-hops (rest (or boundary-to-constructor-path []))
          ctor-count (count ctor-hops)]
      (cond-> [(chain-entry :rule rule-name)]
        (seq rule-hops)
        (into (map (partial chain-entry :caller)) rule-hops)

        boundary-var-name-sym
        (conj (chain-entry :boundary boundary-var-name-sym))

        (seq ctor-hops)
        (into (map-indexed (fn [i {:keys [var-name-sym]}]
                             (chain-entry (if (= i (dec ctor-count)) :constructor :caller)
                                          var-name-sym)))
              ctor-hops)))))
```

Traces against the three shapes:

| Case | `:via` | `provenance-chain` |
| --- | --- | --- |
| direct RHS + ctor | `{insert!, boundary-in-var=rule, ctor-path=[rule ctor]}` | `[rule, boundary, constructor]` |
| helper boundary, no ctor | `{insert!, boundary-in-var=helper, rule-path=[rule helper]}` | `[rule, caller, boundary]` |
| helper boundary + ctor | both paths | `[rule, caller, boundary, caller, constructor]` |

## 4. File checklist

### Server source

- `server/src/clara/server/tools/graph/serialize.clj`
  - Add `chain-entry` and `provenance-chain` (private helpers).
  - `serialize-dynamic-callsite`: gain a `rule-name` param; assoc
    `:provenance-chain (provenance-chain rule-name (:via callsite))` (the
    trailing `utils/remove-nil-vals` drops it when nil).  Compute from the
    **raw** `:via` (symbols), not the already-stringified one.
  - `serialize-dynamic-detection`: gain a `rule-name` param; pass it through to
    `serialize-dynamic-callsite`.

- `server/src/clara/server/tools/graph/core.clj`
  - `production-summary`: pass `(str p-name)` at the two
    `serialize/serialize-dynamic-detection` call sites (dynamic-inserts and
    dynamic-retracts).

- `server/src/clara/server/graph/api.clj`
  - Add `ProvenanceChainEntry` schema
    (`{:label (s/enum :rule :caller :boundary :constructor) :sym s/Str}`).
  - `DynamicCallsiteEntry`: add `(s/optional-key :provenance-chain)
    [ProvenanceChainEntry]`.

### Server tests

- `server/test/clara/server/tools/graph/serialize_test.clj`
  - Cover the three chain shapes (direct-RHS+ctor, helper-no-ctor,
    helper+ctor) and the no-chain heuristic (`:record-ctor-scan`) case.

### UI

- `ui/src/lib/types/api.ts`
  - Add `ProvenanceChainLabel` + `ProvenanceChainEntry`.
  - Add `'provenance-chain'?: ProvenanceChainEntry[]` to `DynamicCallsiteEntry`.
  - Keep `ViaChain` / `ViaEntry` — `:via` remains in the API.
- `ui/src/lib/components/rulebase/DynamicCallsiteList.svelte`
  - Render `site['provenance-chain']` directly; drop the `buildViaEntries`
    import and the `via`-derived entries.
- Delete `ui/src/lib/components/rulebase/viaChain.ts`.
- Delete `ui/src/lib/components/rulebase/viaChain.test.ts`.

### Docs

- `docs/explorer-graph-api.md`
  - Document `provenance-chain` as the display form; note `:via` is the raw
    analyzer form, kept for other consumers.

## 5. Execution order

1. Server change (`serialize.clj` → `core.clj` → `api.clj`).
2. Server tests; verify from `server/`.
3. UI change (`api.ts` → `DynamicCallsiteList.svelte` → delete `viaChain.*`).
4. Verify from `ui/`.
5. Docs.

## 6. Verification

```bash
# server
cd server && make format format-check lint reflection-check test

# ui
cd ui && make format check lint && make test
```

## 7. Consequences (accepted)

- `:via` is unchanged → no callsite-id churn, no rebase/merge impact, no
  annotation-layer regeneration.
- Heuristic `:record-ctor-scan` callsites carry no `provenance-chain`, so the
  UI no longer renders a (near-empty) chain block for them.  Accepted per
  "heuristic scans left alone."
- `provenance-chain` labels serialize as JSON strings (`:rule` → `"rule"`),
  matching the existing enum-keyword pattern (`status`, `resolution`).
