# TODOs

## 1. `synth/reconstruct-ns-source` emits an invalid `:refer` clause

**Where:** `clara.server.tools.graph.analyze.synth/build-require-clauses`

**Severity:** high — no exception, just a wrong answer. Every constructor
callsite in an affected namespace silently fails to resolve, and the rules come
back declaring that they insert nothing.

### What happens

`build-require-clauses` builds each refer clause with

```clojure
(->> kvs (map first) sort (into [target :refer]))
```

`(into [target :refer] '[a b c])` conjes the symbols onto the *outer* vector, so
the clause is

```clojure
[clojure.set :refer union difference]      ; wrong
```

where a libspec requires the referred symbols in their own vector:

```clojure
[clojure.set :refer [union difference]]    ; right
```

Alias clauses are unaffected — `[target :as a]` is already the right shape.

### Minimal repro

`reconstruct-ns-source` is pure with respect to a live `Namespace` object, so no
session or rulebase is needed:

```clojure
(require 'clojure.set '[clara.server.tools.graph.analyze.synth :as synth])

;; a namespace that refers something, built without a source file
(create-ns 'demo.two)
(binding [*ns* (the-ns 'demo.two)]
  (clojure.core/refer-clojure)                            ; else :exclude lists all of core
  (clojure.core/refer 'clojure.set :only '[union difference]))

(println (synth/reconstruct-ns-source 'demo.two))
;; (ns demo.two (:require [clojure.set :refer difference union]) (:import [clojure.lang Compiler] ...))
;;                                     ^^^^^^^^^^^^^^^^^^^^^^^ not a vector

;; not merely cosmetic — the form does not survive a round trip:
(try (eval (read-string (synth/reconstruct-ns-source 'demo.two)))
     (catch Throwable t (ex-message (or (ex-cause t) t))))
;; => "Call to clojure.core/ns did not conform to spec."
```

(The `refer-clojure` call is only to keep the example readable: `create-ns` maps
nothing from `clojure.core`, so `core-deviations` would otherwise emit a
`:refer-clojure :exclude` listing every core public — ~6.8 KB of output that
obscures the point.)

### Why it matters

The reconstructed source is fed to clj-kondo as the base for a synthesized
namespace (see `synthesize-ns-source`, and `analyze-clj-kondo-notes.md`).
clj-kondo cannot register refers from a malformed libspec, so every referred
symbol in the rule snippets that follow stays **unqualified and unresolved**.

`generate-annotations-from-analysis` matches constructor callsites on their
*fully-qualified* symbol, so an unresolved constructor matches nothing. The rule
then reaches the annotations map with no detected insert types and is recorded as
`:clara-rules/no-output-types true`.

### Why it has gone unnoticed

`analyze-session-rules` only reaches `reconstruct-ns-source` for rule-owning
namespaces with **no source on the classpath** — `synthesize-ns-source` prefers
the real file whenever `find-ns-resource` finds one. So any analysis run against
a checked-out rule project takes the real-source path and never sees this.

It shows up when the rules were never on the classpath as source in the first
place:

- a session restored from a `clara.rules.durability` artifact, where the
  rulebase's productions are all that exist and their namespaces are created at
  restore time;
- rules defined by `eval` / at the REPL;
- rules loaded from a jar published without sources.

Measured on one restored session (~4.3k productions across 168 source-less
namespaces): **3,889 of 4,337 rules** came back `:no-output-types`, and clj-kondo
found **zero** constructor callsites. With the clause nested correctly, the same
run resolved **4,536 of 4,659** callsites (97.4%) — against 99.1% for an
equivalent analysis of the same rules built from real source. So the fix is worth
roughly the entire value of the analysis on this path, and source-less
reconstruction is otherwise a near-parity substitute for real source.

### Suggested fix

```clojure
refer-clauses (mapv (fn [[target kvs]]
                      [target :refer (vec (sort (map first kvs)))])
                    refer-groups)
```

### Adjacent, lower priority

`reconstruct-ns-source` also emits the `:refer-clojure` reference as a **vector**
rather than a list:

```clojure
(ns demo [:refer-clojure :exclude [inc]] (:require ...))
```

`clojure.core/ns` happens to tolerate this — `process-reference` only uses
`first`/`rest` — and clj-kondo reads it, so nothing is currently broken. It is
still inconsistent with the `(:require …)` / `(:import …)` clauses beside it, and
worth making a list while the above is being fixed.

### Test gap

There appears to be no coverage of `reconstruct-ns-source` against a namespace
that refers anything, which is why a wrong-shaped clause survives. A round-trip
assertion would catch this class of bug outright:

```clojure
;; the reconstructed form must at minimum be a legal ns form
(is (= 'demo.two (eval (read-string (synth/reconstruct-ns-source 'demo.two)))))
;; stronger: eval it into a fresh namespace and assert the resulting
;; ns-refers / ns-aliases / ns-imports match the original's.
```

---

## 2. `assign-callsite-ids` throws on an empty callsite vector

**Where:** `clara.server.tools.graph.annotations.callsite/assign-callsite-ids`

**Severity:** medium — a hard failure, but an obvious one once triggered. Its
real cost is that the error message points nowhere near the cause.

### What happens

The id-uniqueness check is

```clojure
(if (or (apply distinct? ids) (>= len 64))
  ...)
```

`clojure.core/distinct?` has no 0-arity, so an empty `ids` throws:

```clojure
(apply distinct? [])
;; => ArityException: Wrong number of args (0) passed to: clojure.core/distinct?
```

An empty vector is a legitimate input — "this rule has no callsites in this
dimension" — and the function's own contract (assign ids to every entry of a
callsite vector) is trivially satisfiable for zero entries.

### Minimal repro

```clojure
(require '[clara.server.tools.graph.annotations.callsite :as callsite])

(callsite/assign-callsite-ids [])
;; => ArityException: Wrong number of args (0) passed to: clojure.core/distinct?

(callsite/assign-callsite-ids
 [{:ns-name-sym 'demo :source-str "(some-ctor :x)"}])
;; => [{:ns-name-sym demo :source-str "(some-ctor :x)" :callsite-id "demo:-:c50f6d45:0"}]
```

### Why it matters

The throw surfaces from inside `generate-annotations-from-analysis` →
`infer-annotation-for-var` → `extract-insert-types`, several frames from the
call, as an `ArityException` naming `clojure.core/distinct?`. Nothing in the
message suggests callsites, ids, or which rule was being processed.

Note this is latent behind issue 1: while the refer bug suppresses callsite
discovery, `extract-insert-types` does not reach `assign-callsite-ids` with an
empty vector, so fixing 1 alone converts a silent wrong answer into this crash.
The two should land together.

### Suggested fix

```clojure
(if (or (empty? ids) (apply distinct? ids) (>= len 64))
  ...)
```

or return `callsites` unchanged at the top when it is empty.

### Test gap

`(assign-callsite-ids [])` returning `[]` is a one-line test worth having next to
the existing single/duplicate/collision cases.
