# clj-kondo interaction notes for `analyze.clj`

This document captures the mechanics of how `clara.server.tools.graph.analyze`
interacts with clj-kondo, why it uses `with-in-str` + `:lint ["-"]` +
`:filename`, and what flexibility the public API offers for analyzing
arbitrary source strings without backing files on disk.

## The core pattern

```clojure
(defn- analyze-source-code [source-code resource-path config-dir]
  (with-in-str source-code
    (kondo/run!
     (cond-> {:lint ["-"]
              :lang :clj
              :filename resource-path
              :config {:analysis {:var-definitions true
                                  :var-usages true
                                  :java-class-usages true}}}
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

The resource path (e.g. `clara/server/tools/graph/analyze.clj`) carries the
correct extension even when the source string came from `in-memory-sources` or
was slurped from a classpath resource.

### 2. Source attribution in analysis output

Every var-usage, namespace-definition, and finding in clj-kondo's output
carries `:filename`. Downstream code in `analyze.clj` uses this to map back
from analysis results to the original source:

- `callsite->dynamic-entries` uses `(:filename usage)` as a key into the
  source cache to extract the text of dynamic constructor callsites.
- `build-source-loader` caches source by `(or ns-sym filename)`, using the
  filename as a lookup key when the source didn't come from a resolved
  namespace.

The filename is the join key between the analysis output and the source text.

## How flexible is the public API?

**Fully flexible for ad-hoc source analysis.** The `with-in-str` + `:lint ["-"]`
+ `:filename` pattern works with:

- **Source code from any origin** — classpath resources, in-memory strings,
  dynamically generated forms, REPL evaluations.
- **No backing file on disk required** — the `:filename` string does not need
  to correspond to an existing file. clj-kondo never checks whether the file
  exists when the lint path is `"-"`.
- **Arbitrary `:filename` values** — the only constraint is that it should end
  in `.clj` or `.cljc` for correct language detection, and be unique/meaningful
  enough for downstream attribution.

### Example: ad-hoc source string

```clojure
(let [source "(ns my.ad-hoc.rules
                (:require [clara.rules :refer [defrule insert!]]))
              (defrule test-rule
                [?x <- :some-fact]
                =>
                (insert! (->OtherFact {:val ?x})))"
      fake-filename "my/ad_hoc/rules.clj"]
  (with-in-str source
    (kondo/run! {:lint ["-"]
                 :filename fake-filename
                 :lang :clj
                 :config {:analysis {:var-definitions true
                                     :var-usages true}}})))
```

clj-kondo will analyze this exactly as if it were a real file at
`my/ad_hoc/rules.clj`. The analysis output will carry
`:filename "my/ad_hoc/rules.clj"` and all expected var-usages.

## How `analyze.clj` uses this flexibility

### Two code paths into `analyze-source-code`

| Function | Source origin | Filename derivation |
|----------|---------------|---------------------|
| `analyze-ns-source` | `(slurp resource-url)` — classpath `.clj`/`.cljc` | `ns->resource-base` + extension |
| `analyze-ns-string` | Direct string from `in-memory-sources` map | `ns->resource-base` + `.clj` |

Both converge on `analyze-source-code`, which pipes the source through stdin
with `:filename` set.

### The `in-memory-sources` pattern

`build-analysis-from-namespaces` accepts `:in-memory-sources` — a
`{ns-symbol source-string}` map. When a namespace symbol resolves to an entry
in this map, `analyze-ns-string` is used instead of `analyze-ns-source`. The
namespace never needs to exist on the classpath. This is exactly the ad-hoc
source analysis pattern described above.

### Source lookup for dynamic callsite extraction

`build-source-loader` creates a `(fn [ns-sym filename] -> source-str)` that:

1. Checks `in-memory-sources` by namespace symbol.
2. Falls back to `find-ns-resource` (classpath) by namespace symbol.
3. Falls back to `(slurp (io/as-file filename))` — reads from disk if the
   file exists (used when `:filename` points to a real file from a path-based
   analysis run).

This layered lookup is why `:filename` is set to the resource path: it gives
the downstream source extraction code the best chance of finding the original
text when the namespace itself isn't in `in-memory-sources`.

## Summary

| Concern | Solution |
|---------|----------|
| Feed arbitrary source to clj-kondo | `with-in-str` + `:lint ["-"]` |
| Report correct language | `:filename` ending in `.clj` or `.cljc` |
| Attribute results to origin | `:filename` as join key into source cache |
| No backing file needed | `:filename` is just an identifier string |
| In-memory / dynamic namespaces | `:in-memory-sources` map + `analyze-ns-string` |
