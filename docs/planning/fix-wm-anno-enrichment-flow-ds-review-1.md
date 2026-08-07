# Design Review: fix-wm-anno-enrichment-flow-plan.md

## Summary

The plan correctly identifies the problem (WM enrichment dropped from `start!`
path) and proposes the right architectural principle (annotations-atom is source
of truth; enrichment is caller-level). The delta-layer approach via
`->memory-layer` is clean. However, there are several critical issues around
spec persistence, CLI design, and type normalization that need to be addressed
before implementation.

---

## Critical Issues

### 1. Reload breaks after `swap-session!` — AnnotationsSpec overwritten

`config-atom` holds the server startup config. Currently `swap-session!` stores
the *result* of `build-annotations` (a bare map) into `config-atom :annotations`:

```clojure
(swap! config-atom assoc :annotations bare)
```

The plan keeps this pattern. But the new `reload-annotations!` reads
`:annotations` from config-atom and passes it to `build-annotations`, which
expects an `AnnotationsSpec`, not a bare result:

```clojure
(defn- reload-annotations! []
  (let [{:keys [session annotations]} @config-atom]
    (swap! annotations-atom #(build-annotations session annotations %))))
```

After `swap-session!`, `@config-atom :annotations` is a bare map. Calling
`POST /v1/annotations/reload` would wrap it as `{:source bare-map}` and simply
return the same bare map — no re-reading from disk, no re-running auto-detect.
The reload silently degrades to a no-op.

**Fix:** Store the original `AnnotationsSpec` under a separate key
(e.g. `:annotations-spec`) that `swap-session!` never overwrites. Only
`swap-session!` with a new `:annotations` arg should update it:

```clojure
;; In start!:
(reset! config-atom (assoc config :annotations-spec annotations))

;; In swap-session!:
(when (some? annotations)
  (swap! config-atom assoc :annotations-spec annotations))

;; In reload-annotations!:
(let [{:keys [session annotations-spec]} @config-atom]
  (swap! annotations-atom #(build-annotations session annotations-spec %)))
```

### 2. `Makefile` demo-run drops curated annotations

The plan's `Makefile` entry:

```makefile
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:enrichment :auto-detect}' \
	  -l test-resources/.../loan-doc-rules-annotations.edn
```

When both `--annotations` and `-l` are given, `run-explorer-server` gives
`--annotations` priority (the `cond` short-circuits). The annotations spec
`{:enrichment :auto-detect}` has no `:source` key, so the curated sidecar
annotations are entirely discarded. All manually curated `:notes`,
`:insert-types`, and `:merge-props` from the sidecar file would vanish from
the demo server.

**Fix:** Include the sidecar path in the annotations spec and drop `-l`:

```makefile
demo-run:
	clojure -M:demo-run -s demo-data/session.bin \
	  --annotations '{:source "test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn" :enrichment :auto-detect}'
```

Or, if the intent is to merge both, restructure the cond to combine them
(spec wins for enrichment, `-l` provides the source when spec has none).

### 3. `demo_run.clj` — stale `--enrichment` flag and incomplete resource path

```clojure
(args (if (some #{"--annotations" "--enrichment"} args) ...))
```

`--enrichment` is not a CLI flag in the plan's `cli-options` — it's only a
key inside the `AnnotationsSpec` EDN map. This check would never match and is
dead code.

Also, `(io/resource "...")` is a placeholder — the actual resource path is
`"clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn"`.

**Fix:** Remove the `"--enrichment"` check. Fill in the actual resource path.

---

## Significant Issues

### 4. `parse-annotations-arg` — unquoted file paths parsed as EDN symbols

```clojure
(defn- parse-annotations-arg [s]
  (try
    (let [parsed (edn/read-string s)]
      (if (string? parsed) {:source parsed} parsed))
    (catch Exception _
      (edn/read-string (slurp s)))))
```

`$ clojure -M:demo-run --annotations my-spec.edn` (no quotes):
`edn/read-string "my-spec.edn"` → symbol `my-spec.edn` (valid EDN).
`(string? parsed)` → false. Returns symbol `my-spec.edn`.

This symbol flows into `build-annotations` → normalization sees it's not a
map with `:source`/`:enrichment` → wraps as `{:source my-spec.edn}` (symbol).
Then `coerce-to-bare-annotations` treats the symbol as neither string/File/vector
→ passes it to `->bare-annotations` → returns the symbol → layer construction
explodes.

**Fix:** Add a symbol check after parsing:

```clojure
(defn- parse-annotations-arg [s]
  (let [parsed (try (edn/read-string s)
                    (catch Exception _ nil))]
    (cond
      (map? parsed)     parsed
      (string? parsed)  {:source parsed}
      (symbol? parsed)  {:source s}   ;; treat as file path
      (nil? parsed)     (try (edn/read-string (slurp s))
                             (catch Exception e
                               (throw (IllegalArgumentException.
                                       (format "Invalid --annotations: %s" s) e))))
      :else             (throw (IllegalArgumentException.
                                (format "Unexpected --annotations value: %s" s))))))
```

### 5. `--annotations` silently discards `--layer`

When both flags are present, the `cond` in `run-explorer-server` picks
`--annotations` and ignores `--layer` entirely. No warning is printed. A user
who passes both (perhaps from muscle memory) gets a server with broken
annotations and no indication why.

**Fix:** Print a warning:

```clojure
(when (and annotations (seq layer))
  (println "Warning: --annotations takes priority; --layer values ignored:"
           (pr-str layer)))
```

### 6. Type normalization inconsistency in enrichment vs. delta

Two different normalization functions are used at different points in the pipeline:

| Function | Source file | String input | Keyword input |
|---|---|---|---|
| `annot-type->str` (`resolve-type`) | `serialize.clj` | `pr-str` → `"\"foo\""` | `str` → `":foo/bar"` |
| `type-str` | `annotations.clj` | identity → `"foo"` | `(str (symbol x))` → `"foo/bar"` |

When curated/generated annotations contain types as strings (which kondo
analysis produces), `annot-type->str` quotes them (`"\"SomeClass\""`) while
`type-str` leaves them bare (`"SomeClass"`). This means
`enrich-annotations-from-session` may consider a type "truly new" and re-add
it, while `annotations-delta` correctly sees it as already known. The memory
layer delta ends up empty for those types, but `enrich-annotations-from-session`
still does the wasted work.

For keywords, `annot-type->str` preserves the colon (`":foo/bar"`) while
`type-str` strips it (`"foo/bar"`). This actually *could* cause the delta to
claim a keyword type is new when the enrichment pipeline already correctly
deduped it — though in practice, keyword types from WM match their props
counterparts via `annot-type->str` in the enrichment side.

**Fix:** Unify the normalization. Either:
- A) Make `type-str` delegate to `resolve-type` with a nil namespace (and
  handle the `pr-str` difference for strings), or
- B) Create a shared normalization function used by both `annotations-delta`
  and `enrich-annotations-from-session`.

Option B is cleaner — define `type-str` in `annotations.clj` to produce the
same canonical form that `resolve-type` would produce, or vice versa. Since
`resolve-type` is the boundary serializer, `type-str` should match its output
for primitive types (class → `.getName`, keyword → `str`, string → `pr-str`).

---

## Moderate Issues

### 7. `swap-session!` — `@annotations-atom` deref after swap is racy

The plan's "AFTER" code:

```clojure
(let [s @session-atom]
  (swap! annotations-atom #(build-annotations s annotations %))
  (swap! config-atom assoc :annotations @annotations-atom))
```

`swap!` returns the new value. Use it instead of a separate `@` deref:

```clojure
(let [s @session-atom
      new-annos (swap! annotations-atom #(build-annotations s annotations %))]
  (swap! config-atom assoc :annotations new-annos))
```

(Becomes moot if issue #1 is resolved and the result is no longer stored in
config-atom's `:annotations`.)

### 8. No unit tests for `start!` with WM enrichment

The plan's "Test updates" section only changes the `start-server!` helper
signature — no new test cases are added. The verification steps rely entirely
on the e2e demo scrape, which is slow and fragile for TDD.

**Fix:** Add test cases that exercise `start!` directly with
`:annotations {:enrichment :auto-detect}` and assert that the resulting
annotations include WM-derived types (the same shape as the existing
`test-swap-session-auto-detect` tests but via `start!`). Also test that
`POST /v1/annotations/reload` after `swap-session!` correctly re-derives from
the original spec (once issue #1 is fixed).

---

## Minor Issues

### 9. `build-static-layers` — source layer uses `->layer` wrapper incorrectly

The plan's code:

```clojure
(conj (ann.merge/->layer {:id :source
                          :annotations (ann.merge/coerce-to-bare-annotations source session)}))
```

`->layer` validates its argument as a Layer via `(layer {...})`. But
`coerce-to-bare-annotations` returns *bare* annotations, not a Layer map.
The intent is to build a Layer around bare annotations, which is what
`ann.merge/layer` does directly. `->layer` is for coercion (file path →
read, map → validate). Here you're already constructing the map, so calling
`layer` directly is clearer:

```clojure
(conj (ann.merge/layer {:id :source
                         :annotations (ann.merge/coerce-to-bare-annotations source session)}))
```

(Minor: `->layer` happens to work because its map branch calls `layer`, but
the indirection is misleading.)

### 10. `annotations-atom` initialization — empty map vs. build-annotations result

In `start!`, the plan replaces `(reload-annotations!)` with:

```clojure
(swap! annotations-atom #(build-annotations session annotations %))
```

`annotations-atom` is declared as `(atom {})`. The `%` in the `swap!` fn will
be `{}` on the first call. But `build-annotations` for `:auto-detect` modes
calls `build-auto-detect-annotations` → `build-static-layers` → `merge-layers`
→ all of which operate on fresh state and ignore the `current-annotations`
argument. So the initial `{}` is fine. But it's worth noting that the
`current-annotations` arg is only used by `:reuse`, `:none`, and `nil` modes
(and even then, only `:reuse` reads it). This subtlety should be documented
or the arg could be removed from the non-`:reuse` paths for clarity.

### 11. `start!` docstring — remove `:layers`, but `config-atom` still stores it

Actually, after the change, `config-atom` no longer stores `:layers` at all.
The `reload-annotations!` no longer looks for `:layers`. This is correct and
consistent. Just noting for completeness.

---

## API Surface Review: `AnnotationsSpec`

The final API consists of these user-facing shapes:

| Shape | Example | Meaning |
|---|---|---|
| `nil` | `nil` | No annotations |
| Bare string | `"annos.edn"` | `{:source "annos.edn"}` |
| Bare vector | `["a.edn" "b.edn"]` | `{:source ["a.edn" "b.edn"]}` |
| Bare map | `{"rule" {...}}` | `{:source {"rule" {...}}}` |
| Spec `:enrichment` only | `{:enrichment :auto-detect}` | Auto-detect, no explicit source |
| Spec `:source` only | `{:source "a.edn"}` | Static source, no enrichment |
| Spec both | `{:source "a.edn" :enrichment :auto-detect}` | Both |
| Spec `:reuse` | `{:enrichment :reuse}` | Keep current annotations |

**Edge cases to verify:**

- `{:source nil :enrichment :auto-detect}` — source is nil, auto-detect runs with just props base. Should work.
- `{:source [] :enrichment :auto-detect}` — empty vector source. `coerce-to-bare-annotations` with `[]` → merges `[props-layer]` → returns props annotations. Then auto-detect builds on top of props. Should work.
- `{:enrichment :none}` — explicitly no enrichment. Returns `{}`. Consistent with plan.
- `swap-session!` with only `:session` and no `:annotations` — annotations cleared to `{}`. Consistent.

**Concern: `:enrichment :none` vs. `nil` enrichment**

The `case` in `build-annotations` has separate branches for `:none` and `nil`:

```clojure
:reuse ... 
:none (if (some? source) ... {})
nil   (if (some? source) ... {})
```

Both `:none` and `nil` produce identical behavior. Having two branches that
do the same thing is a maintenance hazard — if one is changed, the other may
drift. Combine them:

```clojure
(:none nil)
(if (some? source)
  (ann.merge/coerce-to-bare-annotations source session)
  {})
```

Or keep them separate with a comment explaining that `:none` is the explicit
opt-out and `nil` is the default, even though behavior is currently identical.

---

## Summary of Required Changes

| Priority | Issue | Affected files |
|---|---|---|
| **P0** | Spec lost after swap — reload breaks | `server.clj` |
| **P0** | demo-run drops curated annotations | `Makefile`, `demo_run.clj` |
| **P0** | `--enrichment` dead code + placeholder path | `demo_run.clj` |
| **P1** | `parse-annotations-arg` symbol bug | `main.clj` |
| **P1** | Silent `--layer` discard | `main.clj` |
| **P1** | Type normalization inconsistency | `annotations.clj`, `analyze.clj` |
| **P2** | `@annotations-atom` deref after swap | `server.clj` |
| **P2** | Missing unit tests for start! + WM | `server_test.clj` |
| **P3** | `->layer` vs `layer` in `build-static-layers` | `server.clj` |
| **P3** | Duplicate `:none`/`nil` branches | `server.clj` |

## Architecture Verdict

The plan's core architecture — `AnnotationsSpec` as the universal interface,
WM enrichment as a delta layer via `->memory-layer`, and `annotations-atom`
as the single source of truth — is sound. The issues above are all at the
implementation/integration layer, not architectural. Once the critical issues
are addressed, the design is clean and consistent.
