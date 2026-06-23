# Clara Rules Explorer Graph API

Unified API reference for the Clara Rules explorer graph server. Covers both **static rulebase analysis** (Phase 1) and **working-memory snapshots** (Phase 2).

---

## Architecture

The explorer server wraps a Clara `session` in a Ring/Jetty HTTP server and exposes two families of endpoints:

| Family | Mount Point | Source | State |
|--------|-------------|--------|-------|
| **Rulebase analysis** | `/v1/...` | `clara.server.tools.graph.core` | Stateless — derived from the compiled rulebase |
| **Session state** | `/v1/session/...` | `clara.server.tools.graph.memory` | Point-in-time snapshot of working memory |

The session and sidecar annotations are held in atoms so the host application can swap them at runtime without restarting.

### Server bootstrap

```clojure
(require '[clara.server.graph.server :as server])

(def s (server/start! {:session    my-session
                       :port       9999
                       :annotations-file "/etc/clara/annotations.edn"})) ;; optional
(server/stop!)  ;; when done
```

---

## Endpoints

### Rulebase Analysis (Static)

All static analysis is derived from the compiled Rete network. These endpoints do not require a running session with facts.

#### `GET /v1/rulebase-summary`

High-level dashboard counts.

**Response** `200`:
```json
{
  "rule-count": 7,
  "query-count": 2,
  "fact-type-count": 10
}
```

| Key | Type | Description |
|-----|------|-------------|
| `rule-count` | int | Number of rules (productions with an RHS) |
| `query-count` | int | Number of queries |
| `fact-type-count` | int | Distinct fact types across all rules/queries |

---

#### `GET /v1/analysis`

Full static analysis of the rulebase. Returns rules, queries, fact-types, nodes, and unresolved detections.

**Response** `200`:
```json
{
  "rules": { "fqName": { ... rule detail ... }, ... },
  "queries": { "fqName": { ... query detail ... }, ... },
  "fact-types": { "fqName": { ... fact type detail ... }, ... },
  "nodes": { nodeId: { ... node detail ... }, ... },
  "unresolved": [ { "rule": "...", "reason": "...", "hint": "..." } ]
}
```

> **Note:** The internal `:dep-graph` and `:ns` keys are not serialized to JSON. Use the `/v1/rules` list and per-rule `:upstream`/`:downstream` for dependency navigation.

---

#### `GET /v1/rules`

List of all rules with lightweight summaries (load order).

**Response** `200`:
```json
{
  "rules": [
    {
      "name": "my.ns/cold-rule",
      "ns": "my.ns",
      "doc": "Fires when temperature drops below freezing",
      "lhs-types": ["my.ns.Temperature", "my.ns.WindSpeed"],
      "insert-types": ["my.ns.Cold"],
      "retract-types": [],
      "source-rule": false,
      "sink-rule": true,
      "upstream": [{ "name": "my.ns/temp-rule", "ns": "my.ns", "type": "rule" }],
      "downstream": []
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified rule name (`ns/name`) |
| `doc` | string\|null | Rule docstring |
| `lhs-types` | string[] | Fact types read on the LHS |
| `insert-types` | string[] | Fact types this rule inserts (resolved from annotations) |
| `retract-types` | string[] | Fact types this rule retracts |
| `source-rule` | boolean | True if no upstream rules (no other rule inserts what this reads) |
| `sink-rule` | boolean | True if no downstream rules consume what this inserts |
| `unlinked-rule` | object? | Present when no `insert-types` or `retract-types` declared (see below) |
| `no-output-types` | boolean? | Present (`true`) when `:clara-rules/no-output-types` annotation is set — rule vetted as pure side-effect with no downstream effects |
| `upstream` | array? | Rules/query-names whose insert types feed this rule |
| `downstream` | array? | Rules/queries that consume this rule's insert types |

**`unlinked-rule` object:**

| Key | Type | Description |
|-----|------|-------------|
| `downstream` | string | Always `"unknown"` — the rule's downstream effects cannot be determined |
| `reason` | string | Human-readable explanation of why the rule is unlinked |

When `unlinked-rule` is present:
- `sink-rule` is set to `false` (cannot determine)
- `downstream` is omitted from serialized output (unknown)

A rule with no declared `insert-types` or `retract-types` can opt out of `unlinked-rule` detection
by setting `:clara-rules/no-output-types true` in its props or sidecar annotations. This indicates
the rule has been manually vetted as a pure side-effect rule (e.g., logging, external API calls)
with no downstream fact effects.

Each element in `upstream`/`downstream`:
| Key | Type | Description |
|-----|------|-------------|
| `name` | string | FQ production name |
| `ns` | string | Namespace string |
| `type` | string | `"rule"` or `"query"` |

---

#### `GET /v1/rules/:fq-name`

Full detail for a single rule, including LHS conditions, RHS form, props, annotations, and dependency edges.

**Path parameter**: `:fq-name` — dot-separated FQ name (e.g., `my.ns.cold-rule` → `my.ns/cold-rule`).

**Response** `200`:
```json
{
  "name": "my.ns/cold-rule",
  "ns": "my.ns",
  "doc": "Fires when temperature drops below freezing",
  "lhs-types": ["my.ns.Temperature", "my.ns.WindSpeed"],
  "insert-types": ["my.ns.Cold"],
  "retract-types": [],
  "source-rule": false,
  "sink-rule": true,
  "annotation-sources": ["props"],
  "notes": "Called by the winter alert pipeline",
  "lhs": [
    {
      "type": "my.ns.Temperature",
      "constraints": "[\n(= ?t value)\n(< ?t 32)\n]"
    }
  ],
  "rhs-form": "(do\n (r/insert! (->Cold ?t ?w)))\n",
  "props": { "clara-rules/insert-types": ["my.ns.Cold"] },
  "upstream": [{ "name": "my.ns/temp-rule", "ns": "my.ns", "type": "rule" }],
  "downstream": [{ "name": "my.ns/find-cold", "ns": "my.ns", "type": "query" }]
}
```

Includes everything from the list view plus:

| Key | Type | Description |
|-----|------|-------------|
| `lhs` | object[] | Serialized LHS conditions, each with `:type` and pretty-printed `:constraints` |
| `rhs-form` | string | Pretty-printed RHS s-expression (fns redacted) |
| `props` | object | Full `:props` map from the `defrule` body (fns redacted) |
| `annotation-sources` | string[] | Origins of the resolved types: `"props"` and/or `"sidecar"` |
| `notes` | string\|null | Human-readable notes from annotations |

**Response** `404`:
```json
{ "error": "Rule not found" }
```

---

#### `GET /v1/queries`

List of all queries with lightweight summaries (load order).

**Response** `200`:
```json
{
  "queries": [
    {
      "name": "my.ns/find-cold",
      "doc": "Find all cold weather events",
      "lhs-types": ["my.ns.Cold"],
      "params": ["?location"],
      "upstream": [{ "name": "my.ns/cold-rule", "ns": "my.ns", "type": "rule" }],
      "downstream": []
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified query name |
| `doc` | string\|null | Query docstring |
| `lhs-types` | string[] | Fact types read on the LHS |
| `params` | string[]\|null | Query parameter names |
| `upstream` / `downstream` | same shape as rules | Dependencies |

---

#### `GET /v1/queries/:fq-name`

Full detail for a single query. Same shape as a rule detail, minus `insert-types`, `retract-types`, `rhs-form`, `source-rule`, `sink-rule`.

**Response** `200`: *(see rule detail for structure)*  
**Response** `404`: `{ "error": "Query not found" }`

---

#### `GET /v1/fact-types`

List of all fact types referenced by rules and queries.

**Response** `200`:
```json
{
  "fact-types": [
    {
      "name": "my.ns.Temperature",
      "used-by-rules": ["my.ns/cold-rule"],
      "used-by-queries": [],
      "inserted-by-rules": [],
      "retracted-by-rules": []
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified type name |
| `used-by-rules` | string[] | Rule FQ-names that read this type on LHS |
| `used-by-queries` | string[] | Query FQ-names that read this type on LHS |
| `inserted-by-rules` | string[] | Rule FQ-names that insert this type |
| `retracted-by-rules` | string[] | Rule FQ-names that retract this type |

---

#### `GET /v1/fact-types/:fq-name`

Single fact-type detail. Same shape as list item.

**Response** `200` / `404`.

---

#### `GET /v1/annotations`

Returns the currently loaded sidecar annotations map.

**Response** `200`:
```json
{
  "my.app/cool-customer": { "insert-types": ["my.app.HappyCustomer"], "notes": "auto-generated" }
}
```

#### `POST /v1/annotations/reload`

Re-reads the sidecar EDN file from disk. Idempotent. Returns the new annotations map. Currently returns `501` (not implemented in `api.clj` — reload is handled at the server layer via middleware).

---

### Session State (Dynamic)

All session endpoints return a **point-in-time snapshot** of working memory. The snapshot is cached per session and recalculated on session change.

#### `GET /v1/session-snapshot`

Full session snapshot. Internal indices included for completeness; the UI should use the targeted endpoints below.

**Response** `200`:
```json
{
  "fact-types": { "fqTypeName": { ... }, ... },
  "facts": { factId: { ... }, ... },
  "used-by": { factId: [ ... ], ... },
  "origin": { factId: [ ... ], ... },
  "rule-matches": { "ruleFqName": { "matches": [...], "inserted-facts": [...] }, ... },
  "query-matches": { "queryFqName": { "matches": [...] }, ... }
}
```

---

#### `GET /v1/session/fact-types`

Summary of all fact types currently in working memory.

**Response** `200`:
```json
{
  "types": [
    { "name": "my.ns.Application", "count": 1 },
    { "name": "my.ns.GivenDocument", "count": 3 }
  ],
  "total-count": 12
}
```

| Key | Type | Description |
|-----|------|-------------|
| `types` | object[] | Each fact type in memory with `name` and `count` |
| `total-count` | int | Total number of fact instances across all types |

---

#### `GET /v1/session/fact-types/:fq-name`

All instances of a specific fact type, grouped by origin and usage.

**Path parameter**: `:fq-name` — dot-separated type name (e.g., `my.ns.Application`).

**Response** `200`:
```json
{
  "name": "my.ns.Application",
  "count": 1,
  "ids": [1],
  "inserted-from": [
    {
      "name": "Root Facts (External)",
      "type": "root",
      "facts": [
        {
          "id": 1,
          "type": "my.ns.Application",
          "data": { "app-id": "app-1" },
          "is-root": true,
          "inserted-from": [],
          "used-by": [
            { "name": "my.ns/check-app", "ns": "my.ns", "type": "rule" }
          ]
        }
      ]
    }
  ],
  "used-by": [
    {
      "name": "my.ns/check-app",
      "ns": "my.ns",
      "type": "rule",
      "facts": [ ... ]
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fact type FQ-name |
| `count` | int | Number of instances in memory |
| `ids` | int[] | All fact IDs of this type |
| `inserted-from` | object[] | Facts grouped by their origin rule (or `"Root Facts (External)"`) |
| `used-by` | object[] | Facts grouped by which rule/query reads them |

**Response** `404`: `{ "error": "Fact type not found in session" }`

---

#### `GET /v1/session/facts/:id`

A single fact instance with its lineage and usage.

**Response** `200`:
```json
{
  "id": 1,
  "type": "my.ns.Application",
  "data": { "app-id": "app-1" },
  "is-root": true,
  "inserted-from": [],
  "used-by": [
    { "name": "my.ns/check-app", "ns": "my.ns", "type": "rule" }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `id` | int | Stable, monotonic fact ID |
| `type` | string | Fact type FQ-name |
| `data` | object | Fact data (arbitrary Clojure structure, fns redacted) |
| `is-root` | boolean | True if inserted externally (not by a rule) |
| `inserted-from` | object[] | Rules that inserted this fact (`{name, ns, type}`) |
| `used-by` | object[] | Rules/queries currently matching this fact (`{name, ns, type}`) |

**Response** `404`: `{ "error": "Fact not found in session" }`

---

#### `GET /v1/session/rules/:fq-name`

Unified activity view for a rule: what it matched + what it inserted.

**Response** `200`:
```json
{
  "matches": [
    {
      "id": 1,
      "type": "my.ns.Application",
      "data": { "?app-id": "app-1" },
      "is-root": true,
      "inserted-from": [],
      "used-by": [ ... ]
    }
  ],
  "inserted-facts": [
    {
      "id": 12,
      "type": "my.ns.ApplicationOutcome",
      "data": { "app-id": "app-1", "status": "approved" },
      "is-root": false,
      "inserted-from": [{ "name": "my.ns/app-outcome-approved", "ns": "my.ns", "type": "rule" }],
      "used-by": [ ... ]
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `matches` | object[] | All facts matched by this rule's activation. Note: `data` contains **variable bindings** (`:?var` keys), not raw fact data. |
| `inserted-facts` | object[] | Facts this rule inserted (empty if rule never fired). Contains raw fact data in `data`. |

**Response** `404`: `{ "error": "Rule matches not found" }`

---

#### `GET /v1/session/queries/:fq-name`

Activity view for a query.

**Response** `200`:
```json
{
  "matches": [
    {
      "id": 12,
      "type": "my.ns.ApplicationOutcome",
      "data": { "?outcome": { ... }, "?app-id": "app-1" },
      "is-root": false,
      "inserted-from": [...],
      "used-by": [{ "name": "my.ns/find-app-outcome", "ns": "my.ns", "type": "query" }]
    }
  ]
}
```

Same match shape as rules; queries have no `inserted-facts`.

**Response** `404`: `{ "error": "Query matches not found" }`

---

## Fact ID Stability

Fact IDs are **monotonic integers** (`1`, `2`, `3`, ...) assigned via `IdentityHashMap` during snapshot creation. Sorting order:

1. Production load order (from the rulebase compiler)
2. Fact type name
3. Fact hash

IDs are stable **within a single snapshot** and deterministic for the same session state, but not guaranteed across snapshots.

---

## Annotations & Metadata

The API supports two paths for declaring rule metadata used in dependency graph construction:

### Path A — Rule `:props` map

Keys in the `defrule` body `:props` map:
- `:clara-rules/insert-types` — types the RHS may insert
- `:clara-rules/retract-types` — types the RHS may retract
- `:clara-rules/no-output-types` — boolean; when `true`, marks the rule as a pure side-effect rule with no downstream inserts/retracts (suppresses unlinked-rule detection)
- `:clara-rules/notes` — free-form documentation

### Path B — Sidecar EDN file

Keyed by rule FQ-name with `clara-rules/` qualified keys:

```edn
{"my.app/cool-customer" {:clara-rules/insert-types [my.app.HappyCustomer]
                          :clara-rules/notes "auto-generated"}
 "my.app/legacy-rule"   {:clara-rules/insert-types [my.app.V2Fact]
                          :clara-rules/merge-props {:clara-rules/insert-types :replace}}}
```

**Precedence**: By default, sidecar `insert-types` and `retract-types` are **merged** (unioned) with props. Use `:clara-rules/merge-props` with a per-category map to override the strategy per type:

| Key | Value | Description |
|-----|-------|-------------|
| `:clara-rules/merge-props` | map | Per-category merge strategy. Each key is an annotation category (`:clara-rules/insert-types`, `:clara-rules/retract-types`), each value is `:merge` (default) or `:replace`. |

Notes always use override semantics: sidecar note wins if present, otherwise falls back to props.

The `resolved-annotation-data` field on each rule/query maps each annotation key to its resolution status:

| Value | Meaning |
|-------|---------|
| `"props"` | Declared only in defrule props |
| `"sidecar"` | Declared only in the sidecar file (or replaced from sidecar) |
| `"merge"` | Declared in both, merged together |

```json
{ "clara-rules/insert-types": "merge",
  "clara-rules/retract-types": null,
  "clara-rules/notes": "sidecar" }
```

A `null` value indicates the key was not declared in either path.

---

## Unresolved Detection

The `:unresolved` collection in `/v1/analysis` tracks rules whose RHS appears to contain `insert!` / `retract!` but no types are declared in either props or sidecar. Each entry:

```json
{
  "rule": "my.ns/orphan-rule",
  "reason": "RHS likely contains insertion/retraction calls but no :clara-rules/insert-types or :clara-rules/retract-types declared.",
  "hint": "Add :clara-rules/insert-types to the rule's properties map or a sidecar annotation file."
}
```

---

## Content Negotiation

All endpoints return `application/json` via Muuntaja. Keys use **kebab-case** (`rule-count`, `lhs-types`, etc.). `FQ-names` in URLs use **dots** as separators (the last dot is treated as the `ns/name` boundary).

Example: `GET /v1/rules/my.app.cold-rule` resolves to `my.app/cold-rule`.

---

## Source Modules

| Module | Purpose |
|--------|---------|
| `clara.server.tools.graph.core` | Static rulebase analysis, dep graph, LHS walker |
| `clara.server.tools.graph.memory` | Working-memory snapshots and indices |
| `clara.server.tools.graph.annotations` | Sidecar EDN loader and annotation merging |
| `clara.server.tools.graph.serialize` | JSON-safe serialization (fn redaction, type resolution) |
| `clara.server.graph.api` | Reitit routes and Ring handler |
| `clara.server.graph.server` | Jetty lifecycle (start/stop) |
le (start/stop) |
