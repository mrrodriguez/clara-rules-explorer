# Nil safety in the analysis pipeline — implementation plan

## Principles

1. **Nil is never a meaningful fact type.** A Clara session cannot match or
   insert on nil. When nil appears as a type token, it is always a defect
   upstream — a failed resolution, a malformed annotation, or a collection
   containing nil that was inserted wholesale.

2. **Push filtering toward the source.** Every nil that makes it past the
   source boundary forces downstream consumers to handle it. Filter early so
   downstream code never sees nil and does not need to.

3. **Static analysis drops silently.** A nil type produced by static analysis
   (kondo resolution failure, absent symbol in annotation EDN) carries no
   information — there is no fact behind it. Drop it, no warning. The existing
   `remove nil?` guards at each boundary already implement this.

4. **Working memory substitutes with attribution.** A real fact object in the
   session whose `fact-type-fn` returns nil _is_ real — it came from a rule.
   Give it a name (`:clara.tools.graph.analyze/unknown-fact-type`), print a
   WARN identifying the inserting rule, and proceed.

5. **Serialization is a backstop, not a policy.** After the source and
   working-memory fixes, nil should never reach `serialize-type-ref` or
   `route-id`. If it does, warn and skip — do not emit a null `:name` to the
   client.

6. **Reporting.** No logger yet — use `(println (str "WARN: ..."))` for now.
   A later pass will introduce proper logging.

---

## Steps

### Step 1 — Fix the entry point: `get-wrapped-fact-groups`

**File:** `server/src/clara/server/vendor/tools/inspect.clj`

`get-wrapped-fact-groups` builds `:all-facts` by unioning three sets:

- `facts-from-alphas` — `(map :fact)` over alpha memory elements
- `facts-from-inserts` — every fact in every insertion group
- `facts-from-matches` — token matches, with an accumulator fallback

None of the three filters `nil` or `ISystemFact`. Meanwhile `inspect-facts` in
the same namespace already guards:

```clojure
:when (and (some? fact)
           (not (instance? ISystemFact fact)))
```

**Change:** Apply the same `(some? fact) / (not (instance? ISystemFact fact))`
filter to all three source sets so `:all-facts` never contains nil or engine
internals. This is the single most impactful fix — it removes nil from every
downstream path in the session-snapshot pipeline.

**Specific edits:**

After `facts-from-alphas` is built — `(set (remove nil? ...))` and remove
ISystemFact instances.

After `facts-from-inserts` is built — same filter.

After `facts-from-matches` is built — same filter.

Since `fact-id-wrap` is applied before set collection, the filter should be
applied to the unwrapped fact **before** wrapping:

```clojure
;; facts-from-alphas: filter before wrap
facts-from-alphas (->> (vals alpha-memory)
                       (mapcat vals)
                       (mapcat identity)
                       (map :fact)                    ;; raw fact
                       (remove nil?)
                       (remove #(instance? ISystemFact %))
                       (map platform/fact-id-wrap)
                       (set))
```

Same pattern for `facts-from-inserts` and `facts-from-matches`.

This also fixes `get-root-facts`, which computes `(set/difference all-facts
facts-from-inserts)` — with nil in both sets, nil could leak into root facts.

---

### Step 2 — Extract a shared `system-fact?` helper

**File:** `server/src/clara/server/vendor/tools/inspect.clj`

The `ISystemFact` predicate is checked in two places after step 1
(`get-wrapped-fact-groups` and `inspect-facts`). Extract:

```clojure
(defn- fact-visible?
  "True when fact is a user-visible fact: non-nil and not an engine internal
   (ISystemFact like NegationResult)."
  [fact]
  (and (some? fact)
       (not (instance? ISystemFact fact))))
```

Replace the inline checks in both functions.

---

### Step 3 — Warn on unknown-fact-type substitution

**File:** `server/src/clara/server/tools/graph/memory.clj`
**Function:** `build-fact-table`

The `:clara.tools.graph.analyze/unknown-fact-type` fallback is currently
silent. Print a WARN with the inserting rule names (available from
`origin-map`):

```clojure
;; After the substitution
(when (nil? raw-type)
  (let [origins (get origin-map id [])
        rule-names (map :name origins)]
    (println (str "WARN: fact-type-fn returned nil for fact "
                  (pr-str (serialize/prune-fns fact))
                  " — inserted by rules: " (pr-str (vec (set rule-names)))
                  " — substituting :clara.tools.graph.analyze/unknown-fact-type"))))
```

Access to `origin-map` is already in scope in `session-snapshot` — pass it
through to `build-fact-table` as an additional key in the options map.

---

### Step 4 — Harden `serialize-type-ref`

**File:** `server/src/clara/server/tools/graph/serialize.clj`

Current: if `resolve-type` returns nil (because `x` is nil),
`serialize-type-ref` emits `{:name nil, :id "x-<hash>", :known false}`.

After steps 1–3, this path should never be reached. Make it a backstop:

```clojure
(defn serialize-type-ref [known-set prod-ns x]
  (let [name (resolve-type prod-ns x)]
    (if (nil? name)
      (do (println (str "WARN: serialize-type-ref received a nil-resolving type token: "
                        (pr-str x) " — dropping. prod-ns=" prod-ns))
          nil)
      {:name name
       :id (route-id name)
       :known (contains? known-set name)})))
```

Callers that use `mapv serialize-type-ref` will now get nil entries for
nil-resolving types. The callers already filter upstream (steps 1–3 and the
existing `remove nil?` guards), so this is defense-in-depth. Callers that
iterate with `keep` or `remove nil?` in the surrounding context are unaffected.

---

### Step 5 — Harden `route-id*`

**File:** `server/src/clara/server/tools/graph/serialize.clj`

Current: `slug` and `sha1-base36` both handle nil via `(or s "")`, so
`route-id` on nil produces `"x-<hash>"` — aliased with the empty-string case.

After step 4 and upstream fixes, nil should not reach `route-id` from
`serialize-type-ref`. But `route-id` is also called directly from index
builders with `(str name)` — and `str` of nil is `""`, which is a legitimate
(if strange) name.

**Change:** guard `route-id*` against nil with a WARN and return nil so
callers can skip:

```clojure
(defn- route-id* [s]
  (if (nil? s)
    (do (println "WARN: route-id* called with nil — skipping")
        nil)
    (str (slug s) "-" (subs (sha1-base36 s) 0 8))))
```

Then in index builders (`build-id-name-index` in memory.clj, and the two
fact-type / production id index builders), skip entries where `route-id`
returns nil:

```clojure
(defn- build-id-name-index [names]
  (reduce (fn [idx name]
            (let [id (serialize/route-id (str name))]
              (if (nil? id)
                idx   ;; route-id warned; skip
                ...existing collision check...)))
          {} names))
```

---

### Step 6 — `serialize-lhs` / `serialize-condition`: use `some?` for `:type` guard

**File:** `server/src/clara/server/tools/graph/serialize.clj`
**Function:** `serialize-condition` → inner `serialize-node`

Current:

```clojure
(contains? node :type) (update :type #(serialize-type-ref known-set prod-ns %))
```

`contains?` is true for `{:type nil}`, which would pass nil to
`serialize-type-ref`. After step 4 that would warn-and-drop, but better to
never call it:

```clojure
(some? (:type node)) (update :type #(serialize-type-ref known-set prod-ns %))
```

---

### Step 7 — End-to-end regression test

**File:** new test or in existing `server/test/clara/server/tools/graph/memory_test.clj`

Test that a rule inserting a collection containing nil:

1. does not crash the analysis pipeline,
2. produces a fact entry with `:name` matching the `unknown-fact-type` keyword
   serialization,
3. marks it `:known false`, and
4. attributes it to the inserting rule.

Also test that `ISystemFact` instances (e.g. `NegationResult`) never appear in
`:all-facts`.

Demo rules for this type of fixture already live under:
`server/test/clara/server/tools/graph/rules/`.

---

### Step 8 (stretch, optional) — `resolve-types` drop counter

**File:** `server/src/clara/server/tools/graph/annotations.clj`

Currently `resolve-types` silently drops nil-resolving tokens. If we want
visibility into how often this happens in static analysis, count drops and
WARN once at the end. This is low priority — static analysis drops are
expected and common for unresolvable symbols. Defer until logging is added.

---

## What does NOT change

- **`slug` / `sha1-base36`** — the `(or s "")` nil guard stays. These are
  low-level string utilities; nil tolerance here is cheap and correct.
- **`extract-lhs-fact-types`** — the `(remove nil?)` stays as defense-in-depth.
- **`build-type-analysis-map`** — the `(remove nil?)` on insert/retract types
  stays.
- **`annotations/resolve-types`** — the `keep` stays.
- **`serialize-dynamic-callsite`** — the `(remove nil?)` on `:resolved-types`
  stays.

All of the above are valid belt-and-braces; they don't hurt performance and
they prevent a future regression from crashing the pipeline.

---

## Ordering

| Step | Dependency | Impact |
|------|-----------|--------|
| 1. Fix `get-wrapped-fact-groups` | none | Eliminates nil at source for session pipeline |
| 2. Extract `fact-visible?` | on step 1 | Cleanup |
| 3. Warn on unknown-fact-type | on step 1 | Observability |
| 4. Harden `serialize-type-ref` | after 1–3 | Backstop |
| 5. Harden `route-id*` | after 4 | Backstop |
| 6. `serialize-lhs` `some?` guard | after 4 | Backstop |
| 7. E2E test | after all | Regression safety |
| 8. Drop counter (optional) | any time | Observability |

Steps 1–2 and 3 can be done together. Steps 4–6 can be done together.
Step 7 should go last.
