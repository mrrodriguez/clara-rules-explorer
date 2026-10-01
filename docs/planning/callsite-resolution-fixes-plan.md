# Callsite resolution: record-ctor precedence and `::` keywords

Status: **resolved**. Both sections are implemented and tested; this file is
kept as the record of what the problem was and what changed.

## 1. Problem (historical)

Two ways the analysis reported the wrong fact types for an insert, where the
correct answer was available to it:

1. **A record constructor unrelated to the fact wins.** When a rule's insert path
   goes through a helper that also builds an unrelated record, that record's type
   could be credited as the insert type. A typical case is a validation helper
   that constructs a schema library's internal records (e.g. `malli.core/->Tag`)
   on its way to building the fact. The rule's `:insert-types` then read
   `[malli.core.Tag …]` rather than its real type.
2. **`::auto-resolved` keywords read in the wrong namespace.** `analyze.kondo`
   read callsite text with `read-string` (`read-boundary-args`, `read-ctor-form`,
   `read-init-form`) without binding `*ns*`. `::type` then resolved against
   whatever `*ns*` was at analysis time instead of the callsite's namespace, and
   `::alias/type` threw, so the argument was silently dropped. A host resolver
   receiving `:arg-form` saw the wrong keyword or no argument at all.

## 2. Record-ctor precedence — resolved

The defect was the heuristic record-ctor scan (`inserter-type-map` fallback)
outranking caller-driven resolution. The fix:

- `compute-heuristic-fallback-callsites` (`analyze.clj`) cedes a var's scan types
  when any of its boundary arguments was owned by the constructor-of-interest
  pass (`:owned-arg-idxs`) or resolved through the boundary chain
  (`:resolved-arg-idxs`, which includes `:callsite-resolver-fn` resolutions). The
  scan is per-inserter-var and every emitted callsite is labeled
  `:via {:source :record-ctor-scan}`.
- `->inserter-type-map` (`analyze/index.clj`) scopes what the scan may credit via
  `:dynamic-type-fallback-resolution` and the index type filter, and reports
  filtered types through `tap>`.

The candidate paths in the original §2.2 were pre-diagnosis hypotheses; the
reproduction pointed at the fallback scan. The ctor-chain-before-resolver
ordering is the intended design (constructors own their args; alias-discovered
callsites bypass the chain), and wrapper ownership is covered by span-set
expansion (`find-owning-boundary-arg` / `arg-span-set`).

## 3. `::` keywords — resolved

Every reader over callsite source text in `analyze.kondo` now runs with `*ns*`
bound to the callsite's namespace (see `read-string-in-ns`):

- `read-boundary-args` and `read-ctor-form` use the usage's `:from`;
  `read-init-form` uses its `ns-sym`.
- `init-form-span`'s `read-one-form-char-count` binds `*ns*` the same way, so a
  `::alias/type` in a locals *init form* still yields a span. Binding only the
  three `read-string` sites would have left locals tracing broken for those
  forms, because the span reader also resolves `::` at read time.
- When the namespace isn't loaded and the source text contains `::`, the read
  returns nil — the caller treats the argument as unresolved — rather than
  resolving `::type` against the wrong namespace. A missing namespace does not
  affect non-`::` forms, which read identically regardless of `*ns*`.

`analyze.synth` reads its own printed output, not user source, so it is
unchanged. The `*ns*` binding wraps the read and gensym canonicalization runs on
the result, so the two are independent and `:callsite-id` stays stable.

### 3.1 Babashka-only editor client

The pure-bb editor client (`bin/editor_client.bb`) resolves `::`/aliased tokens
to fully-qualified form **over the editor's repl-runtime before calling**, so its
own token reader is deliberately fq-in with no `*ns*` binding — bb loads no rule
namespaces. The `*ns*` binding in this change is scoped to the JVM analysis
pipeline (`analyze.kondo` readers), which runs only in the server JVM where rule
namespaces are live. The JVM navigate client (`server/client.clj` `read-token`)
already bound `*ns*` for `::` tokens and is unchanged.

## 4. Changes

| File | Change |
|---|---|
| `explorer/src/clara/explorer/analyze/kondo.clj` | `read-string-in-ns`; `*ns*` binding in `read-boundary-args`, `read-init-form`, `read-ctor-form`, and `read-one-form-char-count`/`init-form-span` |
| `explorer/src/clara/explorer/analyze.clj` | §2 fallback ceding (`compute-heuristic-fallback-callsites`) |
| `explorer/test/clara/explorer/test/rules/analyze_test_rules.clj` | `rule-insert-local-auto-resolved-keyword`, `rule-insert-aliased-auto-resolved-keyword`, `rule-insert-local-keyword-via-let`, `rule-insert-keyword-typed-ctor` |
| `explorer/test/clara/explorer/analyze_test.clj` | `test-auto-resolved-keywords-resolve-in-callsite-ns` |

## 5. Tests

- §2: `test-scan-does-not-displace-constructor-of-interest`,
  `test-heuristic-fallback-per-inserter-var`,
  `test-dynamic-type-fallback-resolution-modes`, `test-type-fallback-skipped-tap`.
- §3: `test-auto-resolved-keywords-resolve-in-callsite-ns` asserts the
  end-to-end behavior (resolver sees the fq keyword for `::local-doc` /
  `::laf/document-check`, a let-bound `::keyword` survives locals tracing, and a
  `::keyword` type argument to `->fact` resolves) *and* pins the raw-source path
  directly: `read-boundary-args` / `read-init-form` / `init-form-span` are called
  with literal `::` source text and a loaded `:from`, so the `*ns*` binding is
  what makes them resolve (the end-to-end rules go through synthesized RHS text,
  where clara already resolved the keywords). A `::keyword` under an unloaded
  namespace yields nil (unresolved), never a wrong keyword.

## 6. Consumer impact

- Layers regenerated against rules that hit either case may change
  `:insert-types` / `:retract-types`; those are corrections.
- Curated overlay entries that patched either case become redundant.
  `clara.explorer.annotations.report`'s lint shows where the overlay now agrees
  with the generated layer.
