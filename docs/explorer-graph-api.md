# Clara Rules Explorer Graph API

Unified API reference for the Clara Rules explorer graph server. Covers both
**static rulebase analysis** and **working-memory snapshots**.

The API is alpha and shaped at-will.  Every linkable entity — fact type, rule,
or query — carries a server-issued, deterministic `id` used for **all** URL
linkage; canonical names never appear in URLs and the client never parses a
name.

---

## Architecture

The explorer server wraps a Clara `session` in a Ring/Jetty HTTP server and exposes two families of endpoints:

| Family | Mount Point | Source | State |
|--------|-------------|--------|-------|
| **Rulebase analysis** | `/v1/...` | `clara.server.tools.graph.core` | Stateless — derived from the compiled rulebase |
| **Session state** | `/v1/session/...` | `clara.server.tools.graph.memory` | Point-in-time snapshot of working memory |

The session and merged annotations are held in atoms so the host application can swap them at runtime without restarting.

### Server bootstrap

```clojure
(require '[clara.server.graph.server :as server])

;; Layers are folded lowest precedence first.  The rule-:props base layer is
;; always included first; additional layers overlay it.
(def s (server/start! {:session my-session
                       :port    9999
                       :layers  ["/etc/clara/curated-annotations.edn"]}))

;; Rulebase-only analysis (no working-memory routes): pass a raw rulebase
;; and the session endpoints return 409 with reason :rulebase-input.
(def s2 (server/start! {:session my-rulebase
                        :port    9999
                        :layers  ["/etc/clara/curated-annotations.edn"]}))

;; Explicitly disable working-memory routes on a live session:
(def s3 (server/start! {:session                   my-session
                        :port                      9999
                        :layers                    ["/etc/clara/curated-annotations.edn"]
                        :working-memory-enabled    false}))
(server/stop!)  ;; when done
```

#### `start!` Options

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:session` | session or rulebase | _required_ | Clara session (working memory enabled) or raw Rete rulebase (working memory disabled; session routes return 409 `:rulebase-input`) |
| `:port` | int | `9999` | HTTP listen port |
| `:layers` | vector | `[]` | Ordered annotation layers (paths or in-memory maps), folded lowest precedence first |
| `:working-memory-enabled` | boolean | `true` | When `false`, all `/v1/session/*` and `/v1/session-snapshot` routes return 409 `:disabled-by-config` regardless of session type |

**CLI flag:** `--working-memory-enabled BOOL` (passed through `run-explorer-server` → `start!`).

---

## Common Shapes

### Kind-explicit type names

Fact types are serialized so the kind is self-describing in JSON (`serialize/resolve-type`):

| Raw kind | Serialized form | Example |
|---|---|---|
| Class | `.getName` | `my.ns.MarkerRecord` |
| keyword | `(str x)` — colon preserved | `:my.ns/child` |
| string | `(pr-str x)` — quotes visible | `"foo"` |
| symbol, unresolved | `symbol[<value>]` | `symbol[my.ns/foo]` |
| symbol, resolved via ns | resolved class/var name | `my.ns.MarkerRecord` |
| vector / sequential (tuple) | `(pr-str x)` | `[:loan/status "verified"]` |
| arbitrary object | `(str x)` | — |

Distinct raw kinds never serialize identically (a keyword `:foo`, a string
`"foo"`, and an unresolved symbol `foo` all differ), so the `known` check and
all downstream set logic are collision-free.

### `TypeReference` — a linkable type

Anywhere the API emits a fact type that the UI may hyperlink, the value is a
`TypeReference` object, never a bare string:

```json
{ "name": ":my.ns/child", "id": "my.ns.child-x7k9p2m4", "known": true }
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Kind-explicit serialized type string (display) |
| `id` | string | Deterministic route id (linkage) |
| `known` | boolean | `true` iff the type appears in the analysis `fact-types` map; `false` marks hierarchy ghosts, which render as plain text (their ids are not a supported linking surface) |

`TypeReference` is used for: fact-type `:ancestors` entries, rule/query
`:lhs-types` / `:insert-types` / `:retract-types`, LHS condition `:type`,
dynamic-callsite `:resolved-types` / `:fact-type`, session fact `:type`, and
the `:match` pairs below.  `known` is always `true` for the
insert/retract/lhs/match entries (those types are in the map by
construction); it distinguishes ghosts only in `:ancestors` and callsite
`:resolved-types`.

### `ProductionDep` — a linkable production reference

Production references are always objects, never bare names:

```json
{ "name": "my.ns/consume-marker", "id": "my.ns.consume-marker-a1b2c3d4", "ns": "my.ns", "type": "rule" }
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified production name (`ns/name`) |
| `id` | string | Deterministic route id (linkage) |
| `ns` | string | Production namespace |
| `type` | string | `"rule"` or `"query"` |
| `match` | TypeBridgeMatch[]? | Present on rule/query upstream/downstream entries when the pair links via at least one type pair |

### `TypeBridgeMatch` — the type pair behind a dep edge

On `:upstream` and `:downstream` entries (see
`clara.server.graph.api/TypeBridgeMatch`), `match` lists the concrete type
pairs that link the two productions.  The shape and meaning are symmetric in
both directions — context (upstream vs downstream) is what identifies which
end produces:

```json
{
  "producer-type": { "name": "my.ns.MarkerRecord", "id": "my.ns.MarkerRecord-a1b2c3d4", "known": true },
  "consumer-type": { "name": "my.ns.IScanMarker",   "id": "my.ns.IScanMarker-b2c4d6e8",   "known": true }
}
```

- `producer-type` — the concrete type the producing rule inserts (or retracts).
- `consumer-type` — the concrete type the consuming rule's LHS requires.
- `via` — `"retract"` when `producer-type` is a **retract type** of the
  producer, so the UI can distinguish retraction coupling from production;
  absent (omitted) for insertion-based bridges.
- Direct matches (same type both ends) are included — no client-side special-casing.
- `match` is an array because one production pair can link via multiple type pairs.
- Entries are deterministically sorted by `producer-type` `:name`, then
  `consumer-type` `:name` (post-serialization).
- Cross-field invariant: `producer-type.name` string-equals the corresponding
  entry in the producer's own `:insert-types`/`:retract-types`, and
  `consumer-type.name` the corresponding entry in the consumer's own
  `:lhs-types` (both serialized in their own production's ns context).

### Route ids

Every fact type, rule, and query has a deterministic `:id` used for ALL URL
linkage — no fallbacks, no name-based resolution (see `serialize/route-id`):

```
id(s)  = slug(s) + "-" + hash8(s)
slug(s)   = every char outside [A-Za-z0-9.-] replaced by "-", runs
            collapsed, leading/trailing "-" trimmed, truncated to 60 chars
hash8(s)  = first 8 base36 chars of SHA-1(s)
```

Uniform across kinds — classes, keywords, strings, tuples, and production
names all get the same treatment.  Ids are a pure function of the name, so
re-running the analysis never changes existing ids.  Uniqueness is asserted
per analysis (a collision throws loudly at analysis-build time, never
silently mislinks).  The reverse indexes are internal: they live in the
analysis cache / snapshot, never in the `/v1/analysis` payload.

Example ids:

| Name | `id` |
|---|---|
| `my.ns.MarkerRecord` (class) | `my.ns.MarkerRecord-a1b2c3d4` |
| `:my.ns/child` (keyword) | `my.ns.child-x7k9p2m4` |
| `"foo"` (string) | `foo-z9y8x7q2` |
| `[:loan/status "verified"]` (tuple) | `loan.status.verified-k4x9p2m8` |
| `my.ns/verify-docs?` (rule) | `my.ns.verify-docs-q2w8e5r4` |

---

## Endpoints

### Rulebase Analysis (Static)

All static analysis is derived from the compiled Rete network. These endpoints do not require a running session with facts.

#### `GET /v1/rulebase-summary`

High-level dashboard counts.  Always returns 200 — this endpoint does not require working memory.

**Response** `200`:
```json
{
  "rule-count": 7,
  "query-count": 2,
  "fact-type-count": 10,
  "working-memory-available": true
}
```

| Key | Type | Description |
|-----|------|-------------|
| `rule-count` | int | Number of rules (productions with an RHS) |
| `query-count` | int | Number of queries |
| `fact-type-count` | int | Distinct fact types across all rules/queries |
| `working-memory-available` | boolean | **Effective state** — `true` iff working-memory routes are served. Computed as `(and :working-memory-enabled (working-memory-available? session))`. When `false`, all `/v1/session/*` and `/v1/session-snapshot` routes return 409. A rulebase input or explicit `:working-memory-enabled false` opt-out both produce `false`. |

---

#### `GET /v1/analysis`

Full static analysis of the rulebase: rules, queries, fact-types, nodes, the
internal dependency graph, and unresolved detections.  (The internal id
reverse indexes are NOT included — handlers use them, API consumers do not.)

**Response** `200`:
```json
{
  "rules": { "fqName": { ... rule detail ... }, ... },
  "queries": { "fqName": { ... query detail ... }, ... },
  "fact-types": { "typeName": { ... fact type detail ... }, ... },
  "nodes": { nodeId: { ... node detail ... }, ... },
  "dep-graph": { "fqName": { "upstream": [...], "downstream": [...] }, ... },
  "unresolved": [ { "rule": "...", "reason": "...", "hint": "..." } ]
}
```

`dep-graph` holds the internal production-name adjacency sets; the
type-bridge `match` info lives on the per-rule `:upstream` / `:downstream`
entries instead.  Prefer the list/detail endpoints for dependency
navigation.

---

#### `GET /v1/rules`

List of all rules with lightweight summaries (load order).  Omits
`:upstream` / `:downstream` (detail-only) and `:ancestors`.

**Response** `200`:
```json
{
  "rules": [
    {
      "name": "my.ns/cold-rule",
      "id": "my.ns.cold-rule-a1b2c3d4",
      "ns": "my.ns",
      "doc": "Fires when temperature drops below freezing",
      "lhs-types": [
        { "name": "my.ns.Temperature", "id": "my.ns.Temperature-e4f5g6h7", "known": true },
        { "name": "my.ns.WindSpeed", "id": "my.ns.WindSpeed-i8j9k1l2", "known": true }
      ],
      "insert-types": [
        { "name": "my.ns.Cold", "id": "my.ns.Cold-m3n4o5p6", "known": true }
      ],
      "retract-types": [],
      "source-rule": false,
      "sink-rule": true
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified rule name (`ns/name`) |
| `id` | string | Route id (see Route ids) |
| `ns` | string | Rule namespace |
| `doc` | string\|null | Rule docstring |
| `lhs-types` | TypeReference[] | Fact types read on the LHS |
| `insert-types` | TypeReference[] | Fact types this rule inserts (resolved from annotations) |
| `retract-types` | TypeReference[] | Fact types this rule retracts |
| `source-rule` | boolean | True if no upstream rules (no other rule inserts what this reads) |
| `sink-rule` | boolean | True if no downstream rules consume what this inserts |
| `unlinked-rule` | object? | Present when no `insert-types` or `retract-types` declared (see below) |
| `no-output-types` | boolean? | Present (`true`) when `:clara-rules/no-output-types` annotation is set — rule vetted as pure side-effect with no downstream effects |

**Optional dynamic detection fields** (`dynamic-insert-types-detected` /
`dynamic-retract-types-detected`): each carries `:callsites` and a
`:resolution`; callsite `:resolved-types` / `:fact-type` are TypeReferences.

---

#### `GET /v1/rules/:id`

Full detail for a single rule, including LHS conditions, RHS form, props,
annotations, and dependency edges (with `:match`).

**Path parameter**: `:id` — the rule's route id (from the list payload).

**Response** `200`:
```json
{
  "name": "my.ns/cold-rule",
  "id": "my.ns.cold-rule-a1b2c3d4",
  "ns": "my.ns",
  "doc": "Fires when temperature drops below freezing",
  "lhs-types": [ { "name": "my.ns.Temperature", "id": "...", "known": true } ],
  "insert-types": [ { "name": "my.ns.Cold", "id": "...", "known": true } ],
  "retract-types": [],
  "source-rule": false,
  "sink-rule": true,
  "notes": "Called by the winter alert pipeline",
  "lhs": [
    {
      "type": { "name": "my.ns.Temperature", "id": "...", "known": true },
      "constraints": "[\n(= ?t value)\n(< ?t 32)\n]"
    }
  ],
  "rhs-form": "(do\n (r/insert! (->Cold ?t ?w)))\n",
  "props": { "clara-rules/insert-types": ["my.ns.Cold"] },
  "upstream": [
    { "name": "my.ns/temp-rule", "id": "my.ns.temp-rule-b2c4d6e8", "ns": "my.ns", "type": "rule",
      "match": [
        { "producer-type": { "name": "my.ns.Temperature", "id": "...", "known": true },
          "consumer-type": { "name": "my.ns.Temperature", "id": "...", "known": true } }
      ] }
  ],
  "downstream": [
    { "name": "my.ns/find-cold", "id": "my.ns.find-cold-c3d5e7f9", "ns": "my.ns", "type": "query" }
  ]
}
```

Includes everything from the list view plus:

| Key | Type | Description |
|-----|------|-------------|
| `lhs` | object[] | Serialized LHS conditions, each with `:type` (a TypeReference) and pretty-printed `:constraints` / `:args` |
| `rhs-form` | string | Pretty-printed RHS s-expression (fns redacted) |
| `props` | object | Full `:props` map from the `defrule` body (fns redacted, keys stringified) |
| `notes` | string\|null | Human-readable notes from annotations |
| `upstream` / `downstream` | ProductionDep[] | Dependency edges; `:match` present when the pair links via at least one type pair |

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
      "id": "my.ns.find-cold-c3d5e7f9",
      "ns": "my.ns",
      "doc": "Find all cold weather events",
      "lhs-types": [ { "name": "my.ns.Cold", "id": "...", "known": true } ],
      "params": ["?location"]
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Fully qualified query name |
| `id` | string | Route id |
| `ns` | string | Query namespace (derived from the name) |
| `doc` | string\|null | Query docstring |
| `lhs-types` | TypeReference[] | Fact types read on the LHS |
| `params` | string[]\|null | Query parameter names |

---

#### `GET /v1/queries/:id`

Full detail for a single query. Same shape as a rule detail, minus
`insert-types`, `retract-types`, `rhs-form`, `source-rule`, `sink-rule`;
`upstream` entries carry `:match` (queries are never producers, so
`:downstream` is absent).

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
      "id": "my.ns.Temperature-e4f5g6h7",
      "ns": "my.ns",
      "used-by-rules": [
        { "name": "my.ns/cold-rule", "id": "my.ns.cold-rule-a1b2c3d4", "ns": "my.ns", "type": "rule" }
      ],
      "used-by-queries": [],
      "inserted-by-rules": [],
      "retracted-by-rules": []
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Serialized type name (kind-explicit) |
| `id` | string | Route id |
| `ns` | string\|null | Best-effort namespace/package for grouping (keyword/symbol → namespace, class → package, string/tuple/other → null) |
| `used-by-rules` | ProductionDep[] | Rules that read this type on LHS |
| `used-by-queries` | ProductionDep[] | Queries that read this type on LHS |
| `inserted-by-rules` | ProductionDep[] | Rules that insert this type |
| `retracted-by-rules` | ProductionDep[] | Rules that retract this type |

`:ancestors` is detail-only — the list endpoint omits it.

---

#### `GET /v1/fact-types/:id`

Full fact-type detail — the list shape plus the hierarchy-ordered
`:ancestors`.

**Response** `200`:
```json
{
  "name": "my.ns.MarkerRecord",
  "id": "my.ns.MarkerRecord-a1b2c3d4",
  "ns": "my.ns",
  "used-by-rules": [],
  "used-by-queries": [],
  "inserted-by-rules": [ { "name": "my.ns/insert-marker", "id": "...", "ns": "my.ns", "type": "rule" } ],
  "retracted-by-rules": [],
  "ancestors": [
    { "name": "my.ns.IScanMarker", "id": "my.ns.IScanMarker-b2c4d6e8", "known": true },
    { "name": "java.lang.Object", "id": "java.lang.Object-f4g6h8j1", "known": false }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `ancestors` | TypeReference[] | Ancestor types in deterministic hierarchy order — descendants before their own ancestors, ties broken lexicographically (see `core/hierarchy-order`). `known: true` entries link via their id; `known: false` ghosts render as plain text |

`known` is the primary noise filter: Clara's default ancestors-fn
(`clojure.core/ancestors`) gives every record type a long tail of JDK/CLJ
interface ghosts, all `known: false`.  The concrete type itself is not
included in its own `ancestors`.

**Response** `404`: `{ "error": "Fact type not found" }`

---

#### `GET /v1/annotations`

Returns the currently loaded merged annotations (a `MergedAnnotations` value with `:annotations`, `:layers`, and `:provenance`). The `:annotations` payload is the merged rule→annotation map; `:provenance` records which layer(s) supplied each key (library-internal, not exposed over HTTP).

**Response** `200`:
```json
{
  "annotations": {
    "my.app/cool-customer": {
      "insert-types": ["my.app.HappyCustomer"],
      "notes": "curated",
      "dynamic-insert-types-detected": {
        "callsites": [
          {
            "callsite-id": "my.app:->HappyCustomer:abc12345:0",
            "source-str": "(->HappyCustomer ?cust)",
            "ns-name-sym": "my.app",
            "filename": "my/app.clj",
            "status": "full",
            "resolved-types": [
              { "name": "my.app.HappyCustomer", "id": "...", "known": true }
            ]
          }
        ],
        "resolution": "full"
      }
    }
  },
  "layers": [
    {"id": "props", "source": "rulebase"},
    {"id": "generated", "source": "generated-from session.bin"},
    {"id": "curated", "source": "/etc/clara/curated-annotations.edn"}
  ],
  "provenance": {}
}
```

#### `POST /v1/annotations/reload`

Re-reads file-backed annotation layers from disk. In-memory layers are kept as-is. Idempotent. Returns the new `MergedAnnotations` value (same shape as `GET`).

---

### Session State (Dynamic)

All session endpoints return a **point-in-time snapshot** of working memory. The snapshot is cached per session and recalculated on session change.

#### Working-Memory Availability (409)

Every session endpoint may return a **409 Conflict** when working memory is unavailable. The `:reason` key is machine-readable; use it (not the message string) to branch:

```json
{
  "error": "No working memory: the server was started with a rulebase, not a session",
  "reason": "rulebase-input"
}
```

| `:reason` | Cause |
|-----------|-------|
| `"rulebase-input"` | The server was started with a raw Rete rulebase instead of a session. Detected per request (the session atom can be hot-swapped at runtime). |
| `"disabled-by-config"` | `:working-memory-enabled false` was set at startup. Resolved once at router construction — all seven working-memory routes return this fixed 409 without per-request branching. |

Clients should check `:working-memory-available` on `/v1/rulebase-summary` to decide whether to attempt session navigation, rather than probing endpoints and handling 409s.

---

#### `GET /v1/session-snapshot`

Full session snapshot. Internal indices included for completeness; the UI should use the targeted endpoints below.

**Response** `200`:
```json
{
  "fact-types": { "typeName": { ... }, ... },
  "facts": { factId: { ... }, ... },
  "used-by": { factId: [ ... ], ... },
  "origin": { factId: [ ... ], ... },
  "rule-matches": { "ruleFqName": { "matches": [...], "inserted-facts": [...] }, ... },
  "query-matches": { "queryFqName": { "matches": [...] }, ... },
  "fact-type-id-index": { "id": "typeName", ... },
  "rule-id-index": { "id": "ruleFqName", ... },
  "query-id-index": { "id": "queryFqName", ... }
}
```

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409) above.

The `*-id-index` maps are the session-side reverse indexes used by the
session detail handlers (id → name), built per snapshot with the same id
function as the analysis side.

---

#### `GET /v1/session/fact-types`

Summary of all fact types currently in working memory.

**Response** `200`:
```json
{
  "types": [
    { "name": "my.ns.Application", "id": "my.ns.Application-a1b2c3d4", "ns": "my.ns", "count": 1 },
    { "name": ":my.ns/status-check", "id": "my.ns.status-check-e4f5g6h7", "ns": "my.ns", "count": 3 }
  ],
  "total-count": 12
}
```

| Key | Type | Description |
|-----|------|-------------|
| `types` | object[] | Each fact type in memory with `name`, `id`, `ns` (nullable), `count` |
| `total-count` | int | Total number of fact instances across all types |

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409).

---

#### `GET /v1/session/fact-types/:id`

All instances of a specific fact type, grouped by origin and usage.

**Path parameter**: `:id` — the fact type's route id.

**Response** `200`:
```json
{
  "name": "my.ns.Application",
  "id": "my.ns.Application-a1b2c3d4",
  "ns": "my.ns",
  "count": 1,
  "ids": [1],
  "inserted-from": [
    {
      "name": "Root Facts (External)",
      "id": "Root.Facts.External-d4e5f6g7",
      "type": "root",
      "facts": [
        {
          "id": 1,
          "type": { "name": "my.ns.Application", "id": "my.ns.Application-a1b2c3d4", "known": true },
          "ns": "my.ns",
          "data": { "app-id": "app-1" },
          "is-root": true,
          "inserted-from": [],
          "used-by": [
            { "name": "my.ns/check-app", "id": "my.ns.check-app-h8i9j1k2", "ns": "my.ns", "type": "rule" }
          ]
        }
      ]
    }
  ],
  "used-by": [
    {
      "name": "my.ns/check-app",
      "id": "my.ns.check-app-h8i9j1k2",
      "ns": "my.ns",
      "type": "rule",
      "facts": [ ... ]
    }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `name` | string | Serialized fact-type name |
| `id` | string | Route id |
| `ns` | string\|null | Best-effort namespace/package |
| `count` | int | Number of instances in memory |
| `ids` | int[] | All fact IDs of this type |
| `inserted-from` | object[] | Facts grouped by their origin rule (or `"Root Facts (External)"`); each group carries `name`/`id`/`type` |
| `used-by` | object[] | Facts grouped by which rule/query reads them; each group carries `name`/`id`/`type`/`ns` |

**Response** `404`: `{ "error": "Fact type not found in session" }`

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409).

---

#### `GET /v1/session/facts/:id`

A single fact instance with its lineage and usage.

**Response** `200`:
```json
{
  "id": 1,
  "type": { "name": "my.ns.Application", "id": "my.ns.Application-a1b2c3d4", "known": true },
  "ns": "my.ns",
  "data": { "app-id": "app-1" },
  "is-root": true,
  "inserted-from": [],
  "used-by": [
    { "name": "my.ns/check-app", "id": "my.ns.check-app-h8i9j1k2", "ns": "my.ns", "type": "rule" }
  ]
}
```

| Key | Type | Description |
|-----|------|-------------|
| `id` | int | Stable, monotonic fact ID |
| `type` | TypeReference | Fact type (always `known: true` — it is in the session by construction) |
| `ns` | string\|null | Best-effort namespace/package of the type |
| `data` | object | Fact data (arbitrary Clojure structure, fns redacted) |
| `is-root` | boolean | True if inserted externally (not by a rule) |
| `inserted-from` | ProductionDep[] | Rules that inserted this fact |
| `used-by` | ProductionDep[] | Rules/queries currently matching this fact |

**Response** `404`: `{ "error": "Fact not found in session" }`

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409).

---

#### `GET /v1/session/rules/:id`

Unified activity view for a rule: what it matched + what it inserted.

**Path parameter**: `:id` — the rule's route id.

**Response** `200`:
```json
{
  "matches": [
    {
      "id": 1,
      "type": { "name": "my.ns.Application", "id": "...", "known": true },
      "ns": "my.ns",
      "data": { "?app-id": "app-1" },
      "is-root": true,
      "inserted-from": [],
      "used-by": [ ... ]
    }
  ],
  "inserted-facts": [
    {
      "id": 12,
      "type": { "name": "my.ns.ApplicationOutcome", "id": "...", "known": true },
      "ns": "my.ns",
      "data": { "app-id": "app-1", "status": "approved" },
      "is-root": false,
      "inserted-from": [ { "name": "my.ns/app-outcome-approved", "id": "...", "ns": "my.ns", "type": "rule" } ],
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

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409).

---

#### `GET /v1/session/queries/:id`

Activity view for a query.

**Response** `200`:
```json
{
  "matches": [
    {
      "id": 12,
      "type": { "name": "my.ns.ApplicationOutcome", "id": "...", "known": true },
      "ns": "my.ns",
      "data": { "?outcome": { ... }, "?app-id": "app-1" },
      "is-root": false,
      "inserted-from": [],
      "used-by": [ { "name": "my.ns/find-app-outcome", "id": "...", "ns": "my.ns", "type": "query" } ]
    }
  ]
}
```

Same match shape as rules; queries have no `inserted-facts`.

**Response** `404`: `{ "error": "Query matches not found" }`

**Response** `409`: See [Working-Memory Availability (409)](#working-memory-availability-409).

---

## Fact ID Stability

Fact IDs are **monotonic integers** (`1`, `2`, `3`, ...) assigned via `IdentityHashMap` during snapshot creation. Sorting order:

1. Production load order (from the rulebase compiler)
2. Fact type name
3. Fact hash

IDs are stable **within a single snapshot** and deterministic for the same session state, but not guaranteed across snapshots.

---

## Annotations & Metadata

Annotations come in **layers** — one per source — folded together with sparse-overlay semantics (omission = no opinion, explicit `null` = erase, deep callsite merge by `:callsite-id`). The three layer sources are:

| Layer | Source | Description |
|-------|--------|-------------|
| `:props` | Rule `:props` maps on the compiled rulebase | Always folded first as the base |
| Generated | Auto-discovered via clj-kondo static analysis | Callsite discovery and resolution |
| Curated | User-authored EDN files | Hand-resolved types, notes, overrides |

For the complete schema, merge strategies, callsite identity format, and derivation modes, refer to the dedicated [Rule Annotations Documentation](../server/docs/rule-annotations.md).

### Dynamic Callsite Status

Each callsite entry carries a `:status` from the three-valued resolution vocabulary:

| Value | Meaning |
|-------|---------|
| `"full"` | All fact types resolved — produces graph edges |
| `"partial"` | Some types known, some unknown |
| `"none"` | No types resolved — needs curation |

The dimension-level `:resolution` (on `dynamic-insert-types-detected` / `dynamic-retract-types-detected`) aggregates across callsites: all-`:full` → `"full"`, all-`:none` → `"none"`, otherwise `"partial"`.

---

## Unresolved Detection

The `:unresolved` collection in `/v1/analysis` tracks rules whose RHS appears to contain `insert!` / `retract!` but no types could be resolved from any annotation layer. Each entry:

```json
{
  "rule": "my.ns/orphan-rule",
  "reason": "RHS likely contains insertion/retraction calls but no :clara-rules/insert-types or :clara-rules/retract-types declared.",
  "hint": "Add :clara-rules/insert-types to the rule's properties map or a curated annotation layer."
}
```

---

## Content Negotiation

All endpoints return `application/json` via Muuntaja. Keys use **kebab-case** (`rule-count`, `lhs-types`, `used-by-rules`, `producer-type`, ...).

URLs never carry canonical names.  Every fact-type, rule, and query detail
route takes the server-issued `:id` verbatim — clients pass it through
unmodified (no encoding, no decoding, no last-dot heuristics).  Ids contain
only `[A-Za-z0-9.-]`, so they always route as plain single segments; a
name-based URL is not a supported addressing surface and 404s.

---

## Source Modules

| Module | Purpose |
|--------|---------|
| `clara.server.tools.graph.core` | Static rulebase analysis, dep graph, type-hierarchy indexes, summary building |
| `clara.server.tools.graph.memory` | Working-memory snapshots, indices, per-snapshot id indexes |
| `clara.server.tools.graph.serialize` | Kind-explicit type serialization (`resolve-type`), route ids (`route-id`), TypeReferences, match serialization |
| `clara.server.tools.graph.annotations` | Layered annotations: format, merge, callsite identity, derivation, rebase, validation |
| `clara.server.graph.api` | Reitit routes, Ring handlers, reverse indexes, Prismatic response schemas |
| `clara.server.graph.server` | Jetty lifecycle (start/stop) |
