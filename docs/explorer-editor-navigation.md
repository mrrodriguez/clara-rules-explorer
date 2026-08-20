# Editor Navigation (Emacs)

Jump between Clara productions from a `.clj` buffer connected to a live CIDER
REPL running the explorer server.  Point at a fact type in a `defrule`/
`defquery` and:

- `M-x clara-explorer-navigate-producer` — jump from an **LHS** fact type to
  the production that inserts (or retracts) a fact satisfying it.
- `M-x clara-explorer-navigate-consumer` — jump from an **RHS** fact type to
  the downstream productions whose LHS consumes it.
- `M-x clara-explorer-refresh` — re-derive annotations and re-warm the analysis
  after editing rule files without re-evaluating.
- `M-x clara-explorer-swap-session` — swap in a rebuilt session after
  re-evaluating rules in the REPL.

All semantics live in `clara.server.graph.client/navigate` (Clojure); the
editor file is thin transport + UX glue.

## REPL bootstrap

Start the server in your CIDER REPL and register it:

```clojure
(require '[clara.server.graph.server :as server]
         '[clara.server.graph.client :as client])

(def explorer-system
  (server/start! {:session my-session :port 9999}))
(client/register! explorer-system)
```

`client/register!` is optional — `navigate` falls back to
`server/get-current-system` (the most recently started server).

## Install

**Spike (no config change):** open `editor/emacs/clara-explorer.el`,
`M-x eval-buffer`, then call the commands with `M-x`.

**Durable (Spacemacs):** add the private layer and set its checkout variable:

```elisp
;; dotspacemacs-configuration-layers
'((clara-explorer :variables clara-explorer-root "~/src/clara-rules-explorer"))
```

The layer skeleton lives at `editor/emacs/spacemacs-layer/` and adds the
`editor/emacs` directory to `load-path` from that variable.  No absolute paths
or ports are hard-coded anywhere in the shipped files.

## Refresh workflow

| What changed | Fix |
| --- | --- |
| Rule source on disk / annotations | `M-x clara-explorer-refresh` |
| Rules re-evaluated in the REPL (session stale) | rebuild the session, then `M-x clara-explorer-swap-session` |
| `clara.server.graph.*` source changed (namespace stale) | `(require 'clara.server.graph.client :reload)` in the REPL, or restart it |

A plain `require` is a no-op for already-loaded namespaces, so after the
`clara.server.graph.client` / `server` sources change, a running REPL must
reload them (`:reload`) or be restarted — otherwise a freshly-evaluated form
can fail to compile with a `CompilerException` ("No such var …") against the
stale namespace.

## Swap the session

`M-x clara-explorer-swap-session` hot-swaps a rebuilt session into the
running server.  It prompts for a **single Clojure expression** that, when
evaluated in the REPL, yields the in-memory session (or rulebase) to swap in:

```clojure
(clara.server.graph.server/swap-session! {:session <expression>})
```

The simplest workflow: rebuild the session in the REPL and bind it to a var,
then pass that var as the expression.

```clojure
;; in the REPL, after re-evaluating the rules:
(def s2 (clara.rules/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                                'clara.server.tools.graph.rules.loan-app-rules))
```

then `M-x clara-explorer-swap-session` and enter `s2`.  You can also inline
the whole form instead of referencing a var:

```clojure
(clara.rules/mk-session 'clara.server.tools.graph.rules.loan-doc-rules
                        'clara.server.tools.graph.rules.loan-app-rules)
```

The expression runs in the REPL's current namespace, so a bare var like `s2`
must be resolvable there (it is, when you `def` it in that namespace).  The
rule namespaces must already be loaded — they are once you have evaluated the
rule files.  The last expression is remembered per connection; `C-u M-x
clara-explorer-swap-session` re-prompts.

`swap-session!` with only `:session` re-derives annotations from rule `:props`
alone, dropping any sidecar / `:enrichment` annotations the server was
started with.  To keep those, call `server/swap-session!` directly with the
same `:annotations` options, or restart the server.
