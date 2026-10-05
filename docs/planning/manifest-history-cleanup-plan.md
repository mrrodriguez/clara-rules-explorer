# Manifest `:history` cleanup

Status: **proposed**.

## 1. Problem

`clara.explorer.artifacts.manifest/write-manifest!` appends one entry to the
manifest's `:history` on every write:

```clojure
{:date "2026-10-05" :change "Annotation generation via <generated-by>."}
```

The `:change` text is a generic default, and the date has day granularity. Two
writes on the same day therefore produce **identical** entries. Regenerating a
unit repeatedly in one day, for example while iterating on explorer tooling that
does not change the analyzed source, fills `:history` with exact duplicates that
carry no information beyond the first one.

Duplicates cost bytes in a file that registry consumers read often, and they bury
the entries that mark a real change in date.

## 2. Goal

- A manifest's `:history` never gains an entry that is identical to one already
  present.
- Existing duplicates can be removed from a manifest or a whole registry.
- No change to the entry shape. An entry stays `{:date :change}`.

Out of scope: richer `:change` text, per-entry counts, and any attempt to
reconstruct why a write happened. A manifest under version control already
records when it changed, and that is enough for now.

## 3. Design

### 3.1 Dedupe on append

`write-manifest!` builds the merged history as
`(vec (concat existing-history fresh-history))`. Replace the concatenation with a
named helper that keeps the first occurrence of each entry:

```clojure
(defn- append-history
  "`existing` followed by the entries of `fresh` it does not already hold."
  [existing fresh]
  (into [] (distinct) (concat existing fresh)))
```

Properties:

- Order is preserved. `distinct` keeps the earliest occurrence, and history is
  already in append order.
- Equality is whole-entry value equality, so entries that differ in `:date` or in
  `:change` both survive.
- An existing manifest whose history already holds duplicates is also cleaned the
  next time it is written. The helper deduplicates the whole concatenation, not
  only the new entry.
- Re-writing a manifest on a later date still appends. History keeps one entry
  per distinct `(date, change)` pair.

### 3.2 A public cleanup function

Callers that own a registry need to clean manifests they are not about to
regenerate. Expose the same rule as a public pure function over a manifest value,
so a caller can apply it to files it has read and rewrite them itself:

```clojure
(s/defn dedupe-history :- schema/Manifest
  "`manifest` with exact-duplicate `:history` entries removed, keeping the first of
  each. A manifest with no `:history` is returned unchanged."
  [manifest :- schema/Manifest]
  (cond-> manifest
    (contains? manifest :history) (update :history #(into [] (distinct) %))))
```

`append-history` and `dedupe-history` share the one `distinct` rule. The append
helper can be expressed through the public function so there is a single
definition of "duplicate".

Reading and writing the file stays with the caller. This namespace does not gain a
registry walk. `clara.explorer.artifacts.registry/discover` already enumerates
units, and `clara.explorer.edn-io/write-edn-file!` is the writer that keeps the
file layout stable.

## 4. Tests

In `clara.explorer.artifacts.manifest` tests:

- Two consecutive `write-manifest!` calls on the same day leave a single history
  entry.
- A write on a later date appends a second entry.
- Two entries with the same date and different `:change` text both survive.
- An existing manifest holding duplicate entries is deduplicated by the next
  write, with order preserved.
- `dedupe-history`:
  - removes exact duplicates and keeps the first occurrence,
  - is idempotent,
  - returns a manifest without `:history` unchanged,
  - leaves every other key untouched.

## 5. Docs

Update the `:history` description in the namespace docstring from "append-only"
to "append-only, without exact duplicates", and note the same in the manifest
section of the user docs if one describes `:history`.

## 6. Rollout

1. Land 3.1 and 3.2 with tests in one change.
2. Downstream callers that hold registries bump the dependency and may call
   `dedupe-history` over their existing manifests as a one-time cleanup.

Behavior change: a manifest rewritten on the same day no longer shows a second
entry, so its `:history` is byte-identical to before. A consumer that counted
entries to count runs will see fewer. Nothing in this repo does.
