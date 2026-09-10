# Editor Navigation (shared)

Jump between Clara productions from a `.clj` buffer connected to a live REPL
running the explorer server. Point at a fact type in a `defrule`/`defquery`:

- **navigate to producer** — jump from an **LHS** fact type to the production
  that inserts (or retracts) a fact satisfying it.
- **navigate to consumer** — jump from an **RHS** fact type to the downstream
  productions whose LHS consumes it (or the global consumers when point is
  outside a rule).
- **refresh** — re-derive annotations and re-warm the analysis after editing
  rule files without re-evaluating.
- **swap session** — swap in a rebuilt session after re-evaluating rules in
  the REPL.

The same feature ships as two clients over one shared contract:

| Editor | Client | Docs                                                                 |
| ------ | ------ | -------------------------------------------------------------------- |
| Emacs  | CIDER  | [Editor Navigation — Emacs](./explorer-editor-navigation-emacs.md)   |
| Neovim | Conjure | [Editor Navigation — Neovim](./explorer-editor-navigation-neovim.md) |

All semantics live in `clara.server.graph.client/navigate` (Clojure) — pure
EDN in, EDN out, no HTTP, no transport-specific types. Each editor is thin
structural navigation + transport + UX glue. No absolute paths or ports are
hard-coded anywhere in the shipped files.

## REPL bootstrap (shared)

Start the server in your REPL and register it:

```clojure
(require '[clara.server.graph.server :as server]
         '[clara.server.graph.client :as client])

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

## Refresh workflow (shared)

| What changed                                             | Fix                                    |
| -------------------------------------------------------- | -------------------------------------- |
| Rule source on disk / annotations                        | refresh                                 |
| Rules re-evaluated in the REPL (session stale)           | rebuild the session, then swap session |
| `clara.server.graph.*` source changed (namespace stale)  | `(require 'clara.server.graph.client :reload)` in the REPL, or restart it |

A plain `require` is a no-op for already-loaded namespaces, so after the
`clara.server.graph.client` / `server` sources change, a running REPL must
reload them (`:reload`) or be restarted — otherwise a freshly-evaluated form
can fail to compile with a `CompilerException` ("No such var …") against the
stale namespace.

## Swap the session (shared)

The swap command hot-swaps a rebuilt session into the running server. It
prompts for a single EDN **opts map** (the argument to
`clara.server.graph.client/swap-session!`), e.g. `{:session s2}`. The simplest
workflow: rebuild the session in the REPL and bind it to a var, then enter
`{:session s2}` at the prompt.

```clojure
;; in the REPL, after re-evaluating the rules:
(def s2 (clara.rules/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                'clara.server.tools.graph.rules.loan-app-rules))
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
