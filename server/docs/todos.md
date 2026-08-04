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

---

## 3. Support a rulebase-only server, with working-memory routes disabled

**Where:** `clara.server.graph.server/start!`, `clara.server.graph.api/get-snapshot`
and the six `/session*` handlers.

**Severity:** medium — the server accepts a rulebase, starts clean, serves most of
its routes, and then 500s on the rest. The failure is deferred to request time and
the error names a protocol, not a configuration mistake.

### What happens

Most of the API is a function of the rulebase alone. `core/get-rulebase` already
codifies this — it accepts a session *or* a rulebase and reduces the former to the
latter — so `/v1/rulebase-summary`, `/v1/rules`, `/v1/queries`, `/v1/fact-types`,
`/v1/analysis` and `/v1/annotations` all work perfectly well with no session.

`start!` accepts whatever it is handed and stores it in `session-atom`. Nothing
checks it. The working-memory routes then fail at request time:

```
IllegalArgumentException: No implementation of method: :components of protocol:
  #'clara.rules.engine/ISession found for class: clara.rules.compiler.Rulebase
  at clara.server.tools.graph.memory/session-snapshot (memory.clj:244)
  from clara.server.graph.api/get-snapshot (api.clj:234)
```

`get-snapshot` calls `memory/session-snapshot` unconditionally, and that calls
`eng/components`, which `Rulebase` does not implement.

### Minimal repro

```clojure
(require '[clara.rules :as r]
         '[clara.rules.engine :as eng]
         '[clara.server.tools.graph.core :as core]
         '[clara.server.tools.graph.memory :as memory])

(defrecord Temp [v])
(r/defrule hot [Temp (> v 100)] => (println "hot"))

(def sess (r/mk-session 'user))
(def rb   (-> sess eng/components :rulebase))
(class rb)                                     ;; => clara.rules.compiler.Rulebase

;; rulebase-only analysis: fine
(some? (core/rulebase-summary (core/rulebase-analysis rb {})))   ;; => true

;; rulebase-only working memory: not fine
(memory/session-snapshot rb)
;; => IllegalArgumentException: No implementation of method: :components ...

;; the distinguishing predicate
(satisfies? eng/ISession rb)     ;; => false
(satisfies? eng/ISession sess)   ;; => true
```

Same shape through the server: `(server/start! {:session rb :port 9001})` succeeds,
`GET /v1/rulebase-summary` returns 200, `GET /v1/session/fact-types` returns 500.

### Why support it rather than reject it

A rulebase without a session is a normal thing to have:

- rules loaded from a project purely for static analysis, with no facts to insert;
- a rulebase deserialized on its own from a `clara.rules.durability` artifact
  (durability lets the rulebase blob be restored independently of session state);
- any caller that wants the dependency graph and annotations and has no interest in
  runtime facts.

For all of those, six routes out of a couple of dozen are inapplicable. Refusing to
start would be a worse answer than serving the rest.

The codebase already leans this way in two places, so this is consistency work rather
than a new concept: `core/get-rulebase` accepts either, and
`api/enriched-annotations` degrades gracefully — it checks the session type and skips
enrichment rather than failing when working memory is unavailable.

### Suggested approach

1. **One predicate, one place.** Something like

   ```clojure
   (defn working-memory-available?
     "True when `session-or-rulebase` is a live session and can be snapshotted."
     [session-or-rulebase]
     (satisfies? eng/ISession session-or-rulebase))
   ```

   `satisfies? ISession` rather than `instance? LocalSession`: it is the capability
   actually required, and it does not exclude other session implementations.

2. **Fail the inapplicable routes uniformly and early**, in `get-snapshot`, so all
   six handlers plus `/v1/session-snapshot` inherit the behaviour from one guard.
   A distinct status with a machine-readable reason beats a 500 and is
   distinguishable from a genuine miss (the handlers already use 404 for "fact not
   found in session"):

   ```clojure
   {:status 409
    :body {:error "No working memory: the server was started with a rulebase, not a session"
           :reason :no-working-memory}}
   ```

   An alternative is to omit the `/session*` routes entirely when there is no
   session, letting reitit 404 them. That is less code but conflates "unsupported in
   this configuration" with "not found", which is the ambiguity worth avoiding.

3. **Say so at startup.** `start!` should log once that working-memory routes are
   disabled, rather than leaving it to be discovered by a 500 later.

4. **Advertise the capability** so a client can branch without probing — e.g. a
   `:working-memory?` flag on an existing summary or meta response.

5. **Update the `start!` docstring**, which currently reads `:session - The Clara
   session to analyze.` It should state that a rulebase is accepted and what is lost.

### Test gap

Worth a small suite that starts the server with a rulebase and asserts: the
rulebase-backed routes return 200, and each working-memory route returns the chosen
status rather than 500.

---

## 4. `deterministic-fact-str` cannot canonicalize a set of maps

**Where:** `clara.server.tools.graph.memory/deterministic-fact-str`

**Severity:** high for anyone snapshotting working memory — it takes down every
memory-backed route at once, for the whole session, because one fact in it has a
set-valued field.

### What happens

`deterministic-fact-str` builds a stable sort key by recursively canonicalizing a
fact into sorted collections:

```clojure
(map? x) (into (sorted-map) (map (fn [[k v]] [(canonicalize k) (canonicalize v)])) x)
(set? x) (into (sorted-set) (map canonicalize) x)
```

Both rely on Clojure's default comparator, which requires every map **key** and
every set **element** to be mutually `Comparable`. Clojure maps are not
`Comparable`, so a fact holding a set of maps throws:

```
ClassCastException: Default comparator requires nil, Number, or Comparable:
  {:name :some-output, :pass false, :data {}, ...}
```

Three distinct shapes hit the same two lines:

| fact shape | result |
|---|---|
| `{:results #{{...}}}` — set of maps | `Default comparator requires ...` |
| `{:by {{...} 1}}` — map keyed by a map | `Default comparator requires ...` |
| `{:a 1 "b" 2}` — mixed key types | `ClassCastException: Keyword cannot be cast to String` |
| `{:results [{...}]}` — vector of maps | fine |

The vector row is why this can go unnoticed for a long time: flat facts, and facts
whose nesting is all vectors, never reach the failing branch.

### Minimal repro

```clojure
(require '[clara.server.tools.graph.memory :as memory])

(#'memory/deterministic-fact-str {:fact/type :t :results #{{:a 1}}})
;; => ClassCastException: Default comparator requires nil, Number, or Comparable: {:a 1}

(#'memory/deterministic-fact-str {:fact/type :t :by {{:a 1} 1}})
;; => same

(#'memory/deterministic-fact-str {:fact/type :t :results [{:a 1}]})
;; => fine
```

### Why it is hard to diagnose

Called from `session-snapshot` → `sort-facts`, it is several frames below any API
handler, and when triggered through an nREPL eval the exception arrives wrapped in
a `CompilerException` tagged `:clojure.error/phase :execution`. It reads as a
syntax/macroexpansion error at the REPL form's own line number, with no frames
pointing at `memory.clj` at all. The message names the offending fact, which is the
only real clue.

### Suggested fix

The return value is used solely as a stable sort key (`sort-facts`), so the
canonical form does not have to be the same data type as the input — it only has to
be deterministic and injective enough to distinguish distinct facts. Ordering by
`pr-str` of the already-canonicalized element is total, and needs no comparator:

```clojure
(defn- deterministic-fact-str [fact]
  (letfn [(canon [x]
            (cond
              (map? x) (into [::map] (sort-by pr-str (map (fn [[k v]] [(canon k) (canon v)]) x)))
              (set? x) (into [::set] (sort-by pr-str (map canon x)))
              (sequential? x) (mapv canon x)
              :else x))]
    (pr-str (canon (serialize/prune-fns fact)))))
```

The `::map` / `::set` markers keep `#{1 2}`, `[1 2]` and `{1 2}` from canonicalizing
to the same string, which matters because collisions here would merge distinct facts
in the sort. Verified: all four shapes above succeed, key order in the input does not
affect the output, set element order does not affect the output, and sets stay
distinct from vectors.

Cost: `pr-str` is called on nested values rather than compared structurally, so this
is slower on deep facts. If that matters, the alternative is a total comparator
(compare by type name, then by value for comparables, then by `pr-str`) — more code
for the same guarantee.

### Test gap

Fixture facts appear to be flat enough to miss this entirely. Worth a table test over
the four shapes above, plus two determinism assertions: the same map built in
different key orders, and the same set built in different element orders, must
produce identical strings.

---

## 5. A layer cannot contribute `:fact-instance-derived-types` without losing something

**Where:** `clara.server.tools.graph.annotations.merge/fold-detection-key`,
`merge-detection-maps`, `normalize-detection-map`

**Severity:** medium — no crash. A caller either silently destroys the analyzer's
callsite audit trail or silently loses its own contribution, and both look like
success.

### What happens

`:fact-instance-derived-types` is described in `DetectionMap` as "the
session-enrichment channel … such maps carry no `:callsites` and merge as opaque
values". That works when session enrichment owns the whole annotation map. It
does not work for a caller that wants enrichment to be *its own layer* folded over
a generated one — and layering is the model everything else here is built around.

Both routes through the detection-map fold lose information:

**Route A — emit `{:fact-instance-derived-types [...]}` with no `:callsites`.**
`fold-detection-key` takes the no-callsites branch: opaque, last declared wins. The
whole merged detection map is replaced, so every callsite the generated layer
contributed is erased.

**Route B — emit the base's `:callsites` alongside, to stay on the deep path.**
`merge-detection-maps` ends with

```clojure
;; non-callsite keys on `a` (e.g. :fact-instance-derived-types from
;; session enrichment) survive the deep merge
(merge (dissoc a :callsites :resolution)
       {:callsites callsites
        :resolution (ann.callsite/aggregate-resolution callsites)})
```

Non-callsite keys survive from `a`, the accumulator — not from `b`, the incoming
layer. So the layer's `:fact-instance-derived-types` is dropped. `normalize-detection-map`
(first fold of a key) has the same shape and keeps them, so the asymmetry only bites
on the second and later layers to touch a given rule.

### Minimal repro

```clojure
(require '[clara.server.tools.graph.annotations.merge :as m])

(def generated
  (m/layer {:id :generated
            :annotations {"a.ns/r" {:clara-rules/dynamic-insert-types-detected
                                    {:callsites [{:ns-name-sym 'a.ns
                                                  :source-str "(->fact :a/one)"
                                                  :status :full}]}}}}))

;; Route A: callsites erased
(def route-a
  (m/layer {:id :enriched
            :annotations {"a.ns/r" {:clara-rules/dynamic-insert-types-detected
                                    {:fact-instance-derived-types ["a/two"]}}}}))
(get-in (m/merge-layers [generated route-a])
        [:annotations "a.ns/r" :clara-rules/dynamic-insert-types-detected])
;; => {:fact-instance-derived-types ["a/two"]}      ; the callsite is gone

;; Route B: derived types dropped
(def route-b
  (m/layer {:id :enriched
            :annotations {"a.ns/r" {:clara-rules/dynamic-insert-types-detected
                                    {:callsites [{:ns-name-sym 'a.ns
                                                  :source-str "(->fact :a/one)"
                                                  :status :full}]
                                     :fact-instance-derived-types ["a/two"]}}}}))
(get-in (m/merge-layers [generated route-b])
        [:annotations "a.ns/r" :clara-rules/dynamic-insert-types-detected
         :fact-instance-derived-types])
;; => nil                                            ; the contribution is gone
```

### Why it matters

The types themselves are fine either way: `:clara-rules/insert-types` merges by
`:union` and its provenance correctly reports both layers as contributing. What is
unreachable is the *audit trail* — the record of which of those types came from
observing a running session rather than from reading source.

A caller can work around it by withholding the key whenever the base already has
callsites, which is what `gateless-rules-explorer`'s working-memory layer does. That
keeps both the callsites and the union, and loses only the per-rule note of which
types were runtime-derived — for exactly the rules where static analysis found
something, i.e. the interesting ones.

### Suggested fix

Merge non-callsite keys from **both** sides in `merge-detection-maps`, preferring the
incoming layer, and let a no-callsites map merge into an existing one instead of
replacing it wholesale:

```clojure
;; in merge-detection-maps, replace (dissoc a :callsites :resolution) with
(merge (dissoc a :callsites :resolution)
       (dissoc b :callsites :resolution)
       {:callsites callsites
        :resolution (ann.callsite/aggregate-resolution callsites)})

;; and in fold-detection-key, the no-callsites branch becomes a merge rather
;; than a replace when something is already there:
(if-not (contains? v :callsites)
  [(assoc merged k (merge (get merged k) v))
   (assoc prov k (contributing (get prov k) layer-id))]
  ...)
```

That makes `:fact-instance-derived-types` behave like every other layered value —
additive, attributable — instead of being a channel only the whole-map caller can
use. Worth checking whether any consumer depends on the current replace semantics
first; the schema comment suggests the opaque path was written for a single-writer
assumption that layering has since outgrown.

### Test gap

No coverage of a detection map arriving from a *second* layer. Worth both routes
above as tests: callsites must survive an incoming derived-types map, and an
incoming derived-types map must survive alongside callsites.
