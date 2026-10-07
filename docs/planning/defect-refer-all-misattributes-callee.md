# Defect: `:refer :all` attributes every referred name to the first refer-all namespace

Status: **fixed** — see
[`defect-refer-all-misattributes-callee-progress.md`](./defect-refer-all-misattributes-callee-progress.md).
Found while triaging unresolved insert callsites in a composed analysis.

## 1. Symptom

A rule namespace that requires two namespaces with `:refer :all` has every name it uses from
either of them reported by clj-kondo as a usage of the **first** one listed. The analyzer sees a
callee that does not exist, so anything keyed on the callee's fully-qualified name misses.

## 2. Minimal reproduction

Run the analyzer's own per-namespace lint, `clara.explorer.analyze/analyze-source-code` (private),
with the bundled kondo config dir, on a namespace that refers two namespaces wholesale:

```clojure
(ns fx
  (:require [clara.rules :refer :all]
            [facts.model.core :refer :all]))

(defrule r
  [?x <- :a/b]
  =>
  (insert! (->fact :t/x {})))
```

`:var-usages` for `insert!` and `->fact`, by the order of the two `:require` entries:

| Requires | `insert!` → | `->fact` → |
|---|---|---|
| `clara.rules :refer :all`, then `facts.model.core :refer :all` | `clara.rules` (correct) | `clara.rules` (**wrong**; no such var) |
| `facts.model.core :refer :all`, then `clara.rules :refer :all` | `facts.model.core` (**wrong**) | `facts.model.core` (correct) |
| `clara.rules :refer :all`, then `facts.model.core :refer [->fact]` | `clara.rules` (correct) | `facts.model.core` (correct) |

`(resolve 'clara.rules/->fact)` is nil. `insert!` is a real `clara.rules` var and `facts.model.core`
has none, so the second row's `insert!` is wrong the same way.

The third row shows an explicit `:refer [->fact]` is attributed correctly, so the fault is only in
the refer-all path.

## 3. Why it shows up in the analyzer

`analyze-source-code` lints each namespace's source from stdin with an explicit `:config-dir` and
no clj-kondo cache for the referred libraries. With nothing known about what `:refer :all`
brings in, kondo has no way to pick a namespace, and it uses the first.

The same lint run from a working directory that has a populated `.clj-kondo/.cache` attributes
the names correctly. That is why the fault does not reproduce in an ordinary REPL session.

## 4. Impact

- **Constructor-of-interest matching** keys on the resolved callee (`facts.model.core/->fact`). A
  usage reported as `clara.rules/->fact` is never matched, so the insert is unresolved and shows
  no `:constructor-sym`.
- **Boundary detection** keys on `clara.rules/insert!` and the other boundary vars. In the second
  table row, `insert!` is reported as `facts.model.core/insert!`. That rule's inserts would not
  be seen as boundary usages at all. Not yet reproduced end to end; worth checking first,
  since a missing insert is silent where an unresolved one is reported.

## 5. Where it might be fixed

The analyzer already expands a refer-all spec itself: `clara.explorer.ns-deps` documents that
`:refer :all` is expanded to every public var of the required namespace when it is loaded. The
usages could be re-attributed with that: for a usage whose `:to` namespace is refer-all'd and
has no such var, find the refer-all'd namespace that does. When none or several qualify, leave it
alone.

Open questions for the investigation:

1. Is the attribution in clj-kondo itself or in how the analyzer configures it? A
   `:config-dir` is passed, with no cache of the referred libraries.
2. Would giving the lint a cache or the referred sources remove the fault without a
   re-attribution step, and at what cost per namespace?
3. How many rule namespaces in a real rulebase refer two or more namespaces wholesale?
