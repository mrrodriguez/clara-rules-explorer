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
