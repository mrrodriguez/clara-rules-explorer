# Nil safety in the analysis pipeline — implementation plan

**Status:** ✅ Complete (Steps 1–8), Step 9 deferred.

---

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
   information — there is no fact behind it. Drop it, no warning.

4. **Working memory substitutes with attribution.** A real fact object in the
   session whose `fact-type-fn` returns nil _is_ real — it may be a
   user-inserted root fact or a rule-inserted fact. Give it a name
   (`:clara.tools.graph.analyze/unknown-fact-type`), print a WARN identifying
   the inserting rules (empty set means it's a root fact), and proceed. For
   the `inspect-facts` API surface, silently skip facts whose `fact-type-fn`
   returns nil — nil is never a meaningful fact type and must not propagate
   to downstream consumers.

5. **Serialization is a backstop, not a policy.** After the source and
   working-memory fixes, nil should never reach `serialize-type-ref` or
   `route-id`. If it does, warn and skip — do not emit a null `:name` to the
   client.

6. **Reporting.** No logger yet — use `(println (str "WARN: ..."))` for now.
   A later pass will introduce proper logging.

---

## Steps

### Step 1 — Fix the entry point: `get-wrapped-fact-groups` ✅

**File:** `server/src/clara/server/vendor/tools/inspect.clj`

All three source sets in `get-wrapped-fact-groups` (`facts-from-alphas`,
`facts-from-inserts`, `facts-from-matches`) now filter each fact through
`fact-visible?` before `platform/fact-id-wrap`:

```clojure
;; facts-from-alphas
(->> (vals alpha-memory)
     ...
     (map :fact)
     (filter fact-visible?)
     (map platform/fact-id-wrap)
     (set))

;; facts-from-inserts — :when guard in for comprehension
(for [... fact insertion-group
      :when (fact-visible? fact)]
  (platform/fact-id-wrap fact))

;; facts-from-matches — same pattern
```

This also fixes `get-root-facts`, which computes `(set/difference all-facts
facts-from-inserts)` — with nil excluded from both, nil can no longer leak
into root facts.

---

### Step 2 — Extract a shared `fact-visible?` helper ✅

**File:** `server/src/clara/server/vendor/tools/inspect.clj`

Extracted predicate, placed immediately before `get-wrapped-fact-groups`:

```clojure
(defn- fact-visible?
  "True when fact is a user-visible fact: non-nil and not an engine internal
   (ISystemFact like NegationResult)."
  [fact]
  (and (some? fact)
       (not (instance? ISystemFact fact))))
```

Replaced the inline `(and (some? fact) (not (instance? ISystemFact fact)))`
check in `inspect-facts` with `(fact-visible? fact)`.

---

### Step 3 — Warn on unknown-fact-type substitution ✅

**File:** `server/src/clara/server/tools/graph/memory.clj`
**Function:** `build-fact-table`

WARN printed when `fact-type-fn` returns nil for any non-nil fact (root or
rule-inserted):

```clojure
_ (when (nil? raw-type)
    (let [rule-names (into #{}
                           (keep :name)
                           (get origin-map id []))]
      (println
       (str "WARN: fact-type-fn returned nil for fact "
            (pr-str (serialize/prune-fns fact))
            " — inserted by rules: " (pr-str rule-names)
            " — substituting :clara.tools.graph.analyze/unknown-fact-type"))))
```

`origin-map` was already in scope via function destructuring — no additional
plumbing needed. An empty `rule-names` set means the fact is a user-inserted
root fact, not inserted by any rule.

---

### Step 4 — Harden `serialize-type-ref` ✅

**File:** `server/src/clara/server/tools/graph/serialize.clj`

Returns `nil` (with WARN) instead of emitting `{:name nil, ...}`:

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

---

### Step 5 — Harden `route-id*` ✅

**File:** `server/src/clara/server/tools/graph/serialize.clj`

Returns `nil` (with WARN) when called with nil:

```clojure
(defn- route-id* [s]
  (if (nil? s)
    (do (println "WARN: route-id* called with nil name — skipping")
        nil)
    (str (slug s) "-" (subs (sha1-base36 s) 0 8))))
```

**File:** `server/src/clara/server/tools/graph/memory.clj` — `build-id-name-index`

Skips entries where `route-id` returns nil:

```clojure
(if (nil? id)
  idx   ;; route-id warned; skip this entry
  (if-let [existing (get idx id)]
    (throw ...collision...)
    (assoc idx id name)))
```

The production and fact-type index builders (`build-production-id-index`,
`build-fact-type-id-index`) were left unchanged — they consume pre-computed
`:id` values from already-filtered analysis data, so nil ids cannot reach them.

---

### Step 6 — `serialize-lhs` / `serialize-condition`: use `some?` for `:type` guard ✅

**File:** `server/src/clara/server/tools/graph/serialize.clj`
**Function:** `serialize-condition` → inner `serialize-node`

Switched from `contains?` to `some?`:

```clojure
(some? (:type node)) (update :type #(serialize-type-ref known-set prod-ns %))
```

`contains?` would match `{:type nil}`, passing nil to `serialize-type-ref`.
`some?` only matches non-nil values.

---

### Step 7 — End-to-end regression tests ✅

**File:** `server/test/clara/server/tools/graph/rules/nil_safety_test_rules.clj` (new)
**File:** `server/test/clara/server/tools/graph/memory_test.clj`

Three new tests covering the full nil-safety surface:

| Test | What it covers |
|------|---------------|
| `test-nil-excluded-from-all-facts` | Rule inserts nil via `insert!` → nil is filtered by `fact-visible?` → never appears in snapshot |
| `test-unknown-fact-type-substitution` | Custom `fact-type-fn` returning `(constantly nil)` → facts get `:clara.tools.graph.analyze/unknown-fact-type` sentinel, `:known false`, valid route-id |
| `test-nil-insertion-analysis-no-crash` | Full pipeline: nil-inserting rule → `session-snapshot` → `rulebase-analysis` — completes without throwing |

---

### Step 8 — Harden `inspect-facts` against nil `fact-type-fn` results ✅

**File:** `server/src/clara/server/vendor/tools/inspect.clj`
**Function:** `inspect-facts`

Both fact-gathering paths (`root-facts` and `rule-facts`) now guard against
nil from `fact-type-fn` by skipping facts whose type cannot be determined:

```clojure
;; root-facts — :when (some? fact-type) added
root-facts (for [fact (get-root-facts session)
                 :let [fact-type (fact-type-fn fact)
                       ancestors (ancestors-fn fact-type)]
                 :when (some? fact-type)]
             {:fact fact
              :fact-types (cons fact-type (or ancestors ()))})

;; rule-facts — :when (some? fact-type) added, :when (fact-visible? fact) retained
rule-facts (for [...]
                 :when (fact-visible? fact)
                 :when (some? fact-type)]
             {:fact fact
              :rule-id id
              :bindings bindings
              :fact-types (cons fact-type (or ancestors ()))})
```

Also added `(or ancestors ())` as defense-in-depth — if `ancestors-fn`
returns nil, it's treated as an empty collection rather than propagating
nil into the fact-types sequence.

### Step 9 (deferred) — `resolve-types` drop counter

Deferred until proper logging is added. Static analysis drops are expected and
common — a counter would add noise without a logging framework to route it.

---

## What was NOT changed

- **`slug` / `sha1-base36`** — the `(or s "")` nil guard stays. These are
  low-level string utilities; nil tolerance here is cheap and correct.
- **`extract-lhs-fact-types`** — the `(remove nil?)` stays as defense-in-depth.
- **`build-type-analysis-map`** — the `(remove nil?)` on insert/retract types stays.
- **`annotations/resolve-types`** — the `keep` stays.
- **`serialize-dynamic-callsite`** — the `(remove nil?)` on `:resolved-types` stays.
- **`build-production-id-index` / `build-fact-type-id-index`** — consume
  pre-computed ids from already-filtered analysis data; nil cannot reach them.

---

## Files changed

| File | Changes |
|------|---------|
| `server/src/clara/server/vendor/tools/inspect.clj` | Steps 1–2: `fact-visible?` helper, filtered source sets, replaced inline check; Step 8: nil `fact-type-fn` guard in `inspect-facts` |
| `server/src/clara/server/tools/graph/memory.clj` | Steps 3, 5: WARN on nil `raw-type`, nil-id skip in `build-id-name-index` |
| `server/src/clara/server/tools/graph/serialize.clj` | Steps 4–6: `serialize-type-ref` backstop, `route-id*` backstop, `some?` guard |
| `server/test/clara/server/tools/graph/rules/nil_safety_test_rules.clj` | Step 7: new test rules |
| `server/test/clara/server/tools/graph/memory_test.clj` | Step 7: 3 new e2e tests |
| `server/test/clara/server/tools/graph/serialize_test.clj` | Updated `test-route-id` and `test-serialize-type-ref` for new nil → nil contract |

## Test results

```
Ran 190 tests containing 1328 assertions.
0 failures, 0 errors.
Lint: errors: 0, warnings: 0
```
