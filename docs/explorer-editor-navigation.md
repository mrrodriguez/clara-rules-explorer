# Editor Navigation (shared)

Jump between Clara productions from a `.clj` buffer connected to a live REPL
running the explorer server. Point at a fact type in a `defrule`/`defquery`:

- **navigate to producer** — jump from an **LHS** fact type to the production
  that inserts (or retracts) a fact satisfying it.
- **navigate to consumer** — jump from an **RHS** fact type to the downstream
  productions whose LHS consumes it (or the global consumers when point is
  outside a rule).
- **show producer provenance** — open an expandable tree of everything upstream
  of the fact type at point.
- **show consumer provenance** — open an expandable tree of everything
  downstream of the fact type at point.
- **refresh** — re-derive annotations and re-warm the analysis after editing
  rule files without re-evaluating.
- **swap session** — swap in a rebuilt session after re-evaluating rules in
  the REPL.

The same feature ships as two clients over one shared contract:

| Editor | Client | Docs                                                                 |
| ------ | ------ | -------------------------------------------------------------------- |
| Emacs  | CIDER  | [Editor Navigation — Emacs](./explorer-editor-navigation-emacs.md)   |
| Neovim | Conjure | [Editor Navigation — Neovim](./explorer-editor-navigation-neovim.md) |

All semantics live in `clara.explorer.server.client/navigate` and
`clara.explorer.server.client/fact-type-provenance` (Clojure, thin JVM shells
over the pure bodies in `clara.explorer.server.navigate`) — pure EDN in, EDN
out, no HTTP, no transport-specific types. Each editor is thin structural
navigation + transport + UX glue. No absolute paths or ports are hard-coded
anywhere in the shipped files.

## REPL bootstrap (shared)

Start the server in your REPL and register it:

```clojure
(require '[clara.explorer.server.serve :as server]
         '[clara.explorer.server.client :as client])

(def explorer-system
  (server/start! {:session my-session :port 9999}))
(client/register! explorer-system)
```

`client/register!` is optional — `navigate` falls back to
`server/get-current-system` (the most recently started server).

## Fact types and LHS structure (shared)

`navigate` resolves the token under point to a kind-explicit type string
(`conditions/extract-lhs-fact-types` contract) — class name, keyword,
`pr-str`'d string/tuple/map. Both editors mirror that contract:

- **Plain / record / class** — `[Application ...]` or `[?v <- Application]` → `Application`
- **Keyword** — `[?d <- ::supporting-document]` → `::supporting-document` (fully-qualified via `*ns*`)
- **String** — `[?x <- "my-string"]` or `(r/insert! "my-string")` → `"my-string"` (quoted)
- **Vector tuple** — `[:loan/status "verified"]`, `[:my-thing]`, `[:my-thing :qual]` → `pr-str`'d vector
- **Props `{:clara-rules/insert-types [T] :clara-rules/retract-types [T]}`** — the optional rule map (second form after the name) is parsed; point inside its `[T]` vector is treated as an **RHS** producer fact type (the same `insert-types`/`retract-types` the server advertises), so consumer navigation finds its consumers.
- **Accumulator `:from`** — the LHS extractor is `case :fact → :type / :accumulator → :from / :and/:or/:not/:exists → :children` (`conditions.clj`). The editor walks the condition vector:
  - `[?acc <- (acc/all) :from [:my-thing ...]]` → `:my-thing`
  - `[?acc <- (acc/all) :from [[:my-thing]]]` → `[:my-thing]`
  - `[?acc <- (acc/all) :from [[:my-thing] [this] (= ?x ...)]]` → `[:my-thing]`
  - `[?acc <- (acc/all) :from [[:my-thing :qual]]]` → `[:my-thing :qual]`
  - any point inside the condition (including `[this]` or a constraint) returns its fact type

The two editors converge on the same structural heuristics but implement them
differently under the hood: Emacs uses a `syntax-ppss`/`forward-sexp` sexp
walker; Neovim uses tree-sitter for the enclosing-form skeleton plus a
byte-based sexp-walker port of the same heuristics. Both send the raw token
text; the server resolves it against the production's declared types and the
serialized dynamic-insert/retract callsite linkage.

## Jump semantics (shared)

Targets are productions. Jump to a target means opening the file at the
`(defrule …)` / `(defquery …)` form:

1. **Var-backed targets** (`:var? true`, the common case) — reuse the editor's
   definition lookup (CIDER `cider-find-var` / Conjure `def-str`, an nREPL
   `info` op that resolves `file:`/`jar:` to absolute paths).
2. **Non-var targets** (`:var? false`) — resolve the classpath-relative
   `:source.file` via `(clojure.java.io/resource …)` and open it; if that is
   `nil` (e.g. `jar:` source), fall back to opening the namespace file and
   searching for `(defrule|defquery NAME`.

Both editors push the jump list (evil/xref in Emacs, tag stack in Neovim)
before jumping so `C-o` returns.

## Fact type provenance (shared)

A fact type's **provenance** is the reference tree around its facts, walked in
either direction:

- **producer provenance** — upstream: which productions insert or retract the
  type (or a subtype of it), and what those productions consume.
- **consumer provenance** — downstream: which productions consume the type (or
  a supertype of it), and what those productions insert or retract.

This is *not* the fact type hierarchy: `:ancestors` / `:descendants` stay
as they are, and provenance uses them. The repo already spends `:provenance`
on annotation/unit origin, so the operation and the UI are always called
**fact type provenance**.

The contract is one pure operation,
`clara.explorer.server.navigate/fact-type-provenance`, answered by
`clara.explorer.server.client/fact-type-provenance` on the JVM and by
`bb bin/editor-client.bb --provenance '<selection>' '<input>'` offline. The
editor asks for one node's expansion; the backend does all graph work.

### Input

```clojure
{:node {:kind :cursor
        :input {:production "ns/rule"   ; enclosing production, or absent
                :side :lhs              ; structural side at point
                :caller-ns "ns"
                :token "DocumentCheck"}}
 :direction :producer                   ; :producer | :consumer
 :depth 0                               ; non-negative integer, or :full
 :ancestors []}                         ; production names strictly above the root
```

or, expanding a rendered production row:

```clojure
{:node {:kind :production :name "ns/rule"}
 :direction :producer
 :depth 0
 :ancestors ["ns/outer"]}               ; productions above this one
```

`:side` inside `:input` is **structural** (where point sits in the form), never
the traversal direction — that is the top-level `:direction`. `:depth 0` shows
one production level below the requested root; `:depth n` shows `n + 1` levels;
`:depth :full` expands until a path repeats (`:cycle? true`) or a production
was already expanded in the same response (`:seen? true`). A production root
already present in `:ancestors` comes back as a cycle.

### Output

Three node kinds, all plain EDN:

| Kind | Fields |
| --- | --- |
| `:fact-type` | `:type`, `:type-short`, `:children`; `:external?` on producer-direction `:fact-type` nodes; `:vias` on a consumer production's child |
| `:production` | `:name`, `:ns`, `:type`, `:unit` (composed selections only), `:via`, `:vias`, `:matches` (`[{:name :short}]`), `:bridge?`, `:outputs :unknown`, `:source`, `:cycle?`, `:seen?`, `:children` |
| `:message` | `:text` — a renderer-built error row |

- `:children` **absent** means expandable, and is also absent when the node was
  stopped by `:cycle? true` or `:seen? true`; `[]` means a known leaf (query,
  `:no-output-types` rule, `:unlinked-rule` with `:outputs :unknown`, or a
  zero-LHS production).
- Display names are contract data, not editor logic: `:type-short` on a
  fact-type node, `:short` on each `:matches` entry, and `:bridge?` for the
  `via …` decision. Only class-like names shorten; keywords, strings, tuples,
  and maps stay whole. No editor computes or shortens a fact-type name.
- `:external?` is `true` when no producer inserts the type (a retract-only
  producer still leaves it external), derived from the producer edges — never
  from `:known`.
- Backend errors are `{:error "…"}`; a renderer turns them into `:message`
  rows.

**Polarity is not labelled in v1.** A rule matching `T` inside `[:not [T …]]`,
`:exists`, or an accumulator `:from [T]` is drawn as an ordinary consumer of
`T` (and as an upstream LHS child in the producer tree); it *is* a consumer
for provenance purposes. Point on a wrapper head (`:not`, `:exists`, `:and`,
`:or`) or its bracket resolves the first child condition's type. Labelling
negation/accumulation would need the persisted `production-conditions.edn`
part, which the offline babashka client does not read, so it is deferred.

### The editor stays thin

The editor may gather structural context, resolve aliases, choose the
transport, build the input, cache returned children, render labels from the
contract's fields, and dispatch expand/jump. It may **not** read analysis
maps, compute closures, decide which productions match, detect cycles, compute
bridge types, or shorten fact-type names. A change that makes an editor
inspect analysis data belongs in `navigate.clj`.

## Refresh workflow (shared)

| What changed                                             | Fix                                    |
| -------------------------------------------------------- | -------------------------------------- |
| Rule source on disk / annotations                        | refresh                                 |
| Rules re-evaluated in the REPL (session stale)           | rebuild the session, then swap session |
| `clara.explorer.server.*` source changed (namespace stale)  | `(require 'clara.explorer.server.client :reload)` in the REPL, or restart it |

A plain `require` is a no-op for already-loaded namespaces, so after the
`clara.explorer.server.client` / `server` sources change, a running REPL must
reload them (`:reload`) or be restarted — otherwise a freshly-evaluated form
can fail to compile with a `CompilerException` ("No such var …") against the
stale namespace.

## Swap the session (shared)

The swap command hot-swaps a rebuilt session into the running server. It
prompts for a single EDN **opts map** (the argument to
`clara.explorer.server.client/swap-session!`), e.g. `{:session s2}`. The simplest
workflow: rebuild the session in the REPL and bind it to a var, then enter
`{:session s2}` at the prompt.

```clojure
;; in the REPL, after re-evaluating the rules:
(def s2 (clara.rules/mk-session 'clara.explorer.test.rules.loan-doc-rules
                                'clara.explorer.test.rules.loan-app-rules))
```

The opts map is read as EDN in the REPL's current namespace, so a bare var
like `s2` inside it must be resolvable there. The rule namespaces must already
be loaded. The last opts map is remembered per connection/buffer; a prefix arg
/ bang re-prompts.

`swap-session!` with only `:session` re-derives annotations from rule `:props`
alone, dropping any sidecar / `:enrichment` annotations the server was
started with. To keep those, call `server/swap-session!` directly with the
same `:annotations` options, or restart the server.

## Testing (shared tier model)

Both editors use the same three-tier model (see the per-editor docs for
commands and details):

| Tier | Scope                          | Network/JVM | CI           |
| ---- | ------------------------------ | ----------- | ------------ |
| 1    | pure unit (stubbed deps)       | no          | yes          |
| 2    | real modules, mocked transport | no          | yes          |
| 3    | live nREPL integration         | yes         | deferred     |

Tier 3 runs the **same `client/navigate` inputs** against the demo rules for
both editors — the point is one contract, two editors.
