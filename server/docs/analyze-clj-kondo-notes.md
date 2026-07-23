# clj-kondo interaction notes for `analyze.clj`

This document captures the mechanics of how `clara.server.tools.graph.analyze`
interacts with clj-kondo, why it uses `with-in-str` + `:lint ["-"]` +
`:filename`, and how the session-based analysis pipeline
(`analyze-session-rules`) synthesizes sources and prunes hook output.

## The core pattern

```clojure
(defn- analyze-source-code [source-code resource-path config-dir]
  (with-in-str source-code
    (kondo/run!
     (cond-> {:lint ["-"]
              :lang :clj
              :filename resource-path
              :config {:analysis {:namespace-definitions true
                                  :var-definitions true
                                  :var-usages true
                                  :java-class-usages true
                                  :locals true
                                  :local-usages true}}}
       config-dir (assoc :config-dir config-dir)))))
```

## Why `with-in-str`?

This is the **public API** path for feeding arbitrary source text to clj-kondo.

`kondo/run!` accepts `:lint` — a seqable of files, directories, classpaths, or the
string `"-"` (stdin). There is no `:source` key on the public `run!` options map.

Internally, when clj-kondo processes a `:lint` entry:

```clojure
;; clj_kondo/impl/core.clj — process-file
(= "-" path)
(schedule ctx {:filename (or filename-fallback "<stdin>")
               :source (slurp *in*)    ;; reads source from stdin
               :lang ...})
```

So `with-in-str` binds `*in*` → clj-kondo calls `(slurp *in*)` → gets the source
string. The `schedule` function (also internal) does accept `:source` in its map,
and `analyze-input` (in `clj_kondo.impl.analyzer`) takes an `input` string
parameter directly — but neither is part of the public API surface. The
`with-in-str` dance is the supported route to reach those internals through
`run!`.

## Why `:filename` set to a resource path?

Two purposes:

### 1. Language detection

clj-kondo calls `lang-from-file` on `:filename` to determine the parse language.
The extension matters:

| Extension | Lang |
|-----------|------|
| `.clj`    | `:clj` |
| `.cljc`   | `:cljc` |

The resource path (e.g. `clara/server/tools/graph/rules/loan_doc_rules.clj`)
carries the correct extension even when the source string was synthesized by
`analyze-session-rules` rather than slurped from a classpath resource.

### 2. Source attribution in analysis output

Every var-usage, namespace-definition, and finding in clj-kondo's output
carries `:filename`. Downstream code in `analyze.clj` / `analyze.rhs` uses
this to map back from analysis results to the original source:

- Callsite extraction uses `(:filename usage)` (plus `:row`/`:end-row`) as a
  key into the source loader to read the text of dynamic constructor
  callsites.
- `build-source-loader` checks the synthesized `::combined-sources` by
  namespace symbol first, then falls back to the classpath resource.
- **Locals tracing** (`analyze.rhs/find-local-binding`) joins
  `:local-usages` to `:locals` bindings by `:id` *and* `:filename` — kondo's
  local `:id` counters restart per analyzed namespace, so the id alone is not
  unique in a merged analysis.

The filename is the join key between the analysis output and the source text.

## How flexible is the public API?

**Fully flexible for ad-hoc source analysis.** The `with-in-str` + `:lint ["-"]`
+ `:filename` pattern works with:

- **Source code from any origin** — classpath resources, synthesized strings,
  dynamically generated forms, REPL evaluations.
- **No backing file on disk required** — the `:filename` string does not need
  to correspond to an existing file. clj-kondo never checks whether the file
  exists when the lint path is `"-"`.
- **Arbitrary `:filename` values** — the only constraint is that it should end
  in `.clj` or `.cljc` for correct language detection, and be unique/meaningful
  enough for downstream attribution.

## The session-based pipeline

`analyze-session-rules` builds the analysis for a Clara session's rules. The
session rulebase is the source of truth for which rules exist (so macro-emitted
rules are included); clj-kondo does all Clojure syntax analysis.

### Synthesized sources (`:ns-source-map`, `::combined-sources`)

For each rule-owning namespace, `synthesize-ns-source` produces a *combined
source*: the real classpath source (or a reconstructed `ns` form when the
namespace only exists via `eval`, with any `:refer-clojure :exclude`/`:rename`,
imports, etc. reflected) followed by one synthetic snippet per production:

```clojure
(defn __clara_explorer_rule_0__ [] <the production's :rhs form>)
```

`build-analysis-from-namespaces` accepts `:ns-source-map` — a
`{ns-symbol source-string}` map — and analyzes those strings via the same
stdin pattern (`ns->resource-base` + `.clj` as filename). The synthesized
sources ride along on the merged analysis under `::combined-sources` so that
`generate-annotations-from-analysis`'s source loader reads them (not the raw
classpath source) when extracting callsite text.

### Prune-and-replace

The bundled clj-kondo config contains the *verbatim* clara-rules hook imports
(`config.edn` from clara-rules' `.clj-kondo` directory), which rewrite
`defrule`/`defquery` into hook output we do not want to consume for rule
structure (the rulebase already tells us the rules). Since the combined source
puts the real source *before* the snippets, `prune-and-rename-analysis`:

1. Drops var-definitions/usages attributed to known session production vars
   (`:prune-vars`) in the *source region* (rows at or before the snippet
   offset).
2. Renames the snippet defs (`__clara_explorer_rule_N__`) to their production
   names, making the snippet region the authoritative source of RHS usages.

The snippet tags carry no semantic content — they are ordinal placeholders
that never leak into output.

### Locals for callsite resolution

`:locals true :local-usages true` are enabled so that `analyze.rhs` can trace
a local symbol argument at an `insert!`/`retract!` callsite (e.g. a macro
gensym) to its binding's init form: the `:local-usages` entry at the arg's
position shares an `:id` with the `:locals` binding in the same file, and the
init form is read from the source text immediately after the binding symbol.
The chain then restarts on the traced form (constructor checks, then the
caller's `:callsite-resolver-fn`).

Kondo's local `:id` values are **not deterministic across runs** — they are
per-run counters. They are only ever used for linkage *within* a single
analysis map, never persisted or compared across runs.

## Source lookup for dynamic callsite extraction

`build-source-loader` creates a `(fn [ns-sym filename] -> source-str)` that:

1. Checks the analysis map's `::combined-sources` by namespace symbol
   (synthesized sources from `analyze-session-rules`).
2. Falls back to `find-ns-resource` (classpath) by namespace symbol.

This layered lookup is why `:filename` is set to the resource path: it gives
the downstream source extraction code the best chance of finding the original
text.

## Summary

| Concern | Solution |
|---------|----------|
| Feed arbitrary source to clj-kondo | `with-in-str` + `:lint ["-"]` |
| Report correct language | `:filename` ending in `.clj` or `.cljc` |
| Attribute results to origin | `:filename` as join key into source loader |
| No backing file needed | `:filename` is just an identifier string |
| Session rules as source of truth | `synthesize-ns-source` + `:ns-source-map` |
| Hook output vs rule structure | prune-and-replace (source region vs snippet region) |
| Macro gensym / local args at callsites | `:locals` + `:local-usages` tracing (`analyze.rhs`) |
| Source-less (eval'd) namespaces | reconstructed `ns` form + synthesized snippets |
