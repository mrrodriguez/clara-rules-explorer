# clj-kondo interaction notes for `clara.explorer.analyze`

This document captures the mechanics of how `clara.explorer.analyze` feeds
source text to clj-kondo and maps kondo's position-based output back onto that
text. For the pass-level concepts that consume the analysis, see
[`analyze-pipeline-concepts.md`](analyze-pipeline-concepts.md); for the
annotation output, see [`rule-annotations.md`](rule-annotations.md).

## The high-level concept

clj-kondo is a file/linter-oriented tool: its public API has no "analyze this
string" option. The analyzer works around that by binding source text to stdin
and handing kondo a *pretend* `:filename`, then uses that same filename plus
kondo's row/col spans to read argument forms back out of the source. One
minimal rule shows the whole loop:

```clojure
;; Synthesized source handed to clj-kondo (namespace `my.rules`, not on disk):
(defn __clara_explorer_rule_0__ []
  (let [fact (->LoanDecision :approved 5000)]
    (clara.rules/insert! fact)))
```

```text
        SOURCE STRING (synthesized — no file on disk)
 ┌──────────────────────────────────────────────────────┐
 │ (ns my.rules …)                                      │
 │ (defn __clara_explorer_rule_0__ []                   │
 │   (let [fact (->LoanDecision :approved 5000)]        │
 │     (clara.rules/insert! fact)))   ← the callsite    │
 └──────────────────────┬───────────────────────────────┘
                        │  with-in-str  (binds *in*)
                        ▼
              clj-kondo (clj-kondo.core/run!)
              :lint ["-"]  :lang :clj  :filename "my/rules.clj"
                        │  analysis map
                        ▼
 ┌──────────────────────────────────────────────────────┐
 │ {:var-usages [{:from      my.rules                    │
 │                :to        clara.rules                 │
 │                :name      insert!                     │
 │                :filename  "my/rules.clj"              │
 │                :row 4 :col 7 :end-row 4 :end-col 14}]}│
 └──────────────────────┬───────────────────────────────┘
                        │  :filename + :row/:col → join key
                        ▼
 ┌──────────────────────────────────────────────────────┐
 │ clara.explorer.analyze.kondo/read-boundary-args       │
 │   reads the usage span → args [fact]                  │
 │ clara.explorer.analyze.callsite/trace-local-form      │
 │   traces fact → (->LoanDecision :approved 5000)       │
 └──────────────────────────────────────────────────────┘
```

Positions above are illustrative; the real values come from kondo's spans.

## The core pattern

`clara.explorer.analyze/analyze-source-code` is the single funnel every source
string passes through:

```clojure
(defn- analyze-source-code [source-code resource-path config-dir]
  (with-in-str source-code
    (kondo-core/run!
     (cond-> {:lint ["-"]
              :lang :clj
              :filename resource-path
              :config {:analysis {:var-definitions true
                                  :var-usages true
                                  :java-class-usages true
                                  :locals true
                                  :local-usages true}}}
       config-dir (assoc :config-dir config-dir)))))
```

## Why `with-in-str`?

`clj-kondo.core/run!` accepts `:lint` — a seqable of files, directories,
classpaths, or the string `"-"` (stdin). There is no `:source` option on the
public `run!` map. Internally, when kondo processes a `"-"` lint entry it
schedules the file with `:source` read from stdin:

```clojure
;; clj_kondo.impl.core, when a :lint entry is "-":
(schedule ctx {:filename (or filename-fallback "<stdin>")
               :source   (slurp *in*)})
```

So `with-in-str` binding `*in*` is the supported route to reach that path
through the public API. (Internal `schedule` accepts `:source`, and the
analyzer's `analyze-input` takes a string directly, but neither is public.)

## Why `:filename` is a resource path

### Language detection

clj-kondo derives the parse language from `:filename`. The extension matters:

| Extension | Lang   |
|-----------|--------|
| `.clj`    | `:clj`  |
| `.cljc`   | `:cljc` |

The resource path (via `clara.explorer.analyze/ns->resource-base`) carries the
right extension even when the source was synthesized rather than slurped from
the classpath.

### Source attribution

Every `:var-usage`, `:locals` entry, and finding in kondo's output carries
`:filename`. That key — plus `:row`/`:col` — is how the analyzer maps a result
back to the text it analyzed: `clara.explorer.analyze/->source-loader` resolves
a namespace to its synthesized or classpath source, and
`clara.explorer.analyze.kondo/read-boundary-args` slices a usage's span out of
it. Local bindings are joined to their usages by `:id` *and* `:filename`
(`clara.explorer.analyze.callsite/find-unique-local-binding`), because kondo's
local `:id` counters restart per analyzed namespace.

## What the public API does — and doesn't — require

- **No backing file needed.** When `:lint` is `"-"`, `:filename` is just an
  identifier; clj-kondo never checks that it exists.
- **Any origin works.** Classpath resources, synthesized strings, dynamically
  generated forms, REPL evaluations all flow through the same pattern.
- **Only constraint:** `:filename` should end in `.clj`/`.cljc` for correct
  language detection and be unique enough to attribute results downstream.

## Where the pipeline pieces live

The mechanics above are the only parts not already described by the impl's own
docstrings. The rest of the pipeline is documented at the symbols themselves:

| Concern | See |
|---|---|
| Synthesized source + `:offset`/`:tag->production` | `clara.explorer.analyze.synth/synthesize-ns-source` |
| Reconstructed `ns` form (no classpath source) | `clara.explorer.analyze.synth/reconstruct-ns-source` |
| Prune-and-replace of hook output vs snippets | `clara.explorer.analyze/prune-and-rename-analysis` |
| Session pipeline (4 steps, options) | `clara.explorer.analyze/->rule-source-analysis` |
| `:ns-source-map`/`:config-dir`/`:cache-atom` options | `clara.explorer.analyze/->rule-source-analysis-from-namespaces` |
| Source lookup for callsite text | `clara.explorer.analyze/->source-loader` |
| Read args at a kondo usage span | `clara.explorer.analyze.kondo/read-boundary-args` |
| Bracket scan (binding classification) | `clara.explorer.analyze.kondo/scan-brackets-between` |
| Resolution chain overview | `clara.explorer.analyze.callsite` (ns docstring) |
| Local binding classification | `clara.explorer.analyze.callsite/classify-binding` |
| Local → init-form tracing | `clara.explorer.analyze.callsite/trace-local-form` |
| Span-set expansion (ctor ownership) | `clara.explorer.analyze.callsite/arg-span-set` |
| `:refer :all` callee misattribution fix | `clara.explorer.analyze.refer/re-attribute-refer-all-usages` |

Two kondo behaviours are worth keeping in mind because they are not visible at
any single symbol:

- **Local `:id` values are per-run counters** — never persisted or compared
  across runs; linkage is always within one analysis map.
- **`:refer :all` cold-cache misattribution** — with no warm
  `.clj-kondo/.cache` for the referred libraries, kondo attributes every bare
  name used from several refer-all'd namespaces to the *first* one in the
  `:require` clause; see `clara.explorer.analyze.refer/re-attribute-refer-all-usages`
  for the correction.

## Summary

| Concern | Solution |
|---|---|
| Feed arbitrary source to clj-kondo | `with-in-str` + `:lint ["-"]` (`clara.explorer.analyze/analyze-source-code`) |
| Correct parse language | `:filename` ending in `.clj`/`.cljc` |
| Map results back to source | `:filename` + `:row`/`:col` via `clara.explorer.analyze/->source-loader` |
| No backing file needed | `:filename` is just an identifier when `:lint` is `"-"` |
| Session rules as source of truth | `clara.explorer.analyze.synth/synthesize-ns-source` + `clara.explorer.analyze/->rule-source-analysis` |
| Hook output vs rule structure | `clara.explorer.analyze/prune-and-rename-analysis` |
| Macro gensym / local args at callsites | `clara.explorer.analyze.callsite` tracing |
| Source-less (eval'd) namespaces | `clara.explorer.analyze.synth/reconstruct-ns-source` + snippets |
