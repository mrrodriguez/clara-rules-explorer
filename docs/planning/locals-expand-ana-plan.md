# Locals-Expand Analysis Plan

Ephemeral span-set expansion for RHS boundary-arg analysis: make the
boundary arg's transitive local closure visible to constructor ownership
without text substitution.

## 1. Problem

`trace-local-form` (`clara.server.tools.graph.analyze.callsite`) only
follows a bare-symbol boundary arg (`insert-all! f` → init of `f`). It
does not expand locals *inside* a larger arg form. Ownership
(`owning-arg` / `arg-reaches-ctor?`) therefore misses two shapes:

1. **Intermediate call in `let` init.** `(insert-all! looked-up-facts-1)`
   where the init is `(look-up-facts-1 x)` and that fn transitively calls
   a resolvable ctor (e.g. `->fact :something`). The traced form names
   the intermediate, but rule 2 only looks for intermediates among
   sibling usages enclosed in the boundary span — the init call site is
   outside it. Rule 3 also fails (init position ≠ ctor position).
   Result today: `:none`.
2. **Seq-combinator closure.** `(insert-all! fact-seq-all)` →
   `(concat fact-seq-a fact-seq-b)` → `for` bodies holding `->fact`.
   Traced head is `clojure.core/concat`: not a ctor, not on any ctor
   path, and no relevant sibling usage sits inside the boundary span.
   Result today: `:none`.

Kondo linkage (`:local-usages` → `:locals` by `:id`, disambiguated by
`:filename`) is the scope proof. Binder definitions are never usages,
so binder positions naturally never resolve.

## 2. Approach: ephemeral span-set expansion

Do NOT splice init text into the boundary form (fragile under macros,
reader conditionals, gensyms, formatting). Instead compute a **span
set** per `TracedArg`: the source spans whose var-usages count as
"mentioned by this boundary arg" =

`{boundary span} ∪ {transitively-reached init spans}`.

All linkage stays position-identity based; nothing is persisted
(`:source-str` keeps the original arg).

## 3. Steps

1. **Init-span reader** (new `defn-` in
   `clara.server.tools.graph.analyze.kondo`, next to `read-init-form`):
   given a `:locals` binding, return `[start-pos end-pos]` of its init
   form. Start exists today (`init-form-start`); end comes from reading
   one form through a char-counting pushback reader and mapping the
   consumed length over the known tail text to `[row col]` (pure string
   math, no new dependency). Fallback: `tools.reader` position
   tracking, only if already on classpath. Interop here is
   reflection-sensitive — confirm with `make reflection-check`, hint
   per clojure-engineering skill (class-prefixed interop, hints on
   `let` LHS).
2. **Position indexes** (extend
   `clara.server.tools.graph.analyze.index/build-analysis-index`):
   `var-usages-by-position` (`{[filename row col] → usage}`) plus a
   file-sorted usage list for range queries. `locals-by-id` /
   `local-usages-by-name` already exist.
3. **Fixpoint expansion** (new `defn-` in
   `clara.server.tools.graph.analyze.callsite`): per `TracedArg`, seed
   the region set with the boundary usage span; repeat: collect
   `:local-usages` + `:var-usages` with start-pos inside the region set
   → follow each local usage to its binding (`locals-by-id`) → add its
   init span (memoize per binding `:id`; visited-set + existing depth
   cap bound cycles). Kondo linkage drives everything, so shadowing,
   destructuring, and `for`/`fn` binders resolve correctly with no
   special-form walker: binder definitions have no usage entries, and
   inner bindings only expand if actually linked. The per-ctor path
   check keeps attribution honest.
4. **Generalize ownership** (`arg-reaches-ctor?`, same `defn-`) — the
   span set becomes the *single* ownership mechanism, **replacing** the
   three legacy routes rather than adding to them:
   - R1′ (ctor written in the span set) subsumes legacy R1 (inline
     ctor) and R3 (local bound directly to a ctor): the seed region is
     the boundary span, and a directly-bound ctor's init span enters the
     set via one linkage hop. Position identity is preserved — two
     identical `->fact` forms never cross-attribute, the guarantee the
     `rule-ctor-identical-forms` test pins.
   - R2′ (a span-set var-usage names a link on `intermediates`) subsumes
     legacy R2 (intermediate call inside the boundary span): the seed
     region contributes the boundary span's own var-usages, and reached
     init spans contribute `(insert-all! (mapv make-fact xs))` /
     `(insert-all! looked-up-facts-1)` intermediates.
   - Legacy R1/R2/R3 predicates, `usage-encloses?`, the `sibling-usages`
     plumbing, and the now-dead `TracedArg :traced-binding` field are all
     deleted. Per-boundary-arg granularity preserved: expansion is per
     `TracedArg`, so shape 1's two `insert-all!` calls attribute
     independently (facts-1 chain ≠ facts-2 chain).
5. **Boundary-chain upgrade** (same pass, small):
   `resolve-traced-arg` also consults record/Java ctor usages in the
   expanded region, so shape 2 with `->MyRecord` instead of `->fact`
   resolves at per-arg precision rather than via the weak subtree scan
   fallback.
6. **Precision guardrails**: unreached ctors still dropped (existing
   `test-constructor-only-counts-on-an-insert-path` semantics
   unchanged); nested-ctor over-promotion policy unchanged;
   alias-chain callsites still bypass auto-resolution.
7. **Unify the boundary pass's local tracing** (same span-identity
   mechanism, discovered during review): the generic chain
   `trace-local-form` previously resolved a bare local only one hop —
   `find-local-binding` searched the *boundary* span, so an intermediate
   local's usage (which sits in the previous binding's init, outside that
   span) was never found. Fix: `find-local-binding` takes an explicit
   span and `trace-local-form` threads the current span — the boundary
   span first, then each traced binding's init span from
   `init-form-span`. This makes `(let [a (->X) b a c b] (insert! c))`
   resolve in the generic (non-`:fact-constructors`) chain, and shares
   `usage->span` with `arg-span-set`.

## 4. Tests

New fixture rules in
`clara.server.tools.graph.rules.analyze-test-rules` mirroring both
shapes (one `->fact`-resolver spec each), asserting per-arg
attribution, `:full` resolution, no duplicates, and
`:callsite-resolver-fn` not consulted for owned args — following the
existing `rule-ctor-bound-to-local` /
`rule-ctor-local-plus-multiple-inserts` patterns in
`clara.server.tools.graph.analyze-test`.

Negative cases:

- Same-named local in a non-flowing branch must not attribute
  (position-identity) — `rule-ctor-locals-shadowed-local`.
- Uninserted `->fact` in an expanded-but-unreached init stays dropped —
  `rule-ctor-locals-unreached-stays-dropped`.

Generic-boundary multi-hop case (step 7):

- `rule-local-multi-hop-ctor` — a Java ctor bound, rebinding through two
  intermediate locals, then inserted; the boundary chain traces the whole
  chain to the ctor init (`DocumentCheck`), asserted in
  `test-dynamic-insert-types-detected`.

## 5. Verification

Run in `server/` (Makefile is authoritative; do not hand-compose
`clojure -M` aliases):

```bash
make test lint reflection-check format-check
```

## 6. Sequencing

Done: steps 1–4 (with ownership collapsed to the span set as the single
mechanism) for the `:fact-constructors` path, plus step 7 (multi-hop
generic boundary chain). Remaining follow-up: step 5 (record/Java
boundary-chain upgrade for the constructor-of-interest pass).
