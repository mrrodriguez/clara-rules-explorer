# Plan: REPL-free navigation (static token resolution)

Status: **planned, not started.** Builds on the `fact-type-provenance` branch:
[`fact-type-provenance-progress.md`](./fact-type-provenance-progress.md) (backend
and Emacs semantics) and
[`fact-type-provenance-nvim-progress.md`](./fact-type-provenance-nvim-progress.md)
(Neovim). This is the "REPL-free bb token resolution" item deferred in §10 of
the first.

## 1. Goal

Navigate producer/consumer, and both provenance views, should work in both
editors with **no REPL at all** over the babashka transport. The REPL path stays
**primary**: when a REPL is connected, it resolves tokens exactly as today, and
static resolution only fills in what the REPL could not resolve.

"Standalone" means:

- **Supported**: literal qualified keywords; `::kw` and `::alias/kw`, as whole
  tokens and (in the last phase) inside tuple tokens; imported or
  same-namespace record classes; explicitly referred or alias-qualified record
  constructors.
- **Best effort**: a constructor reachable only through `:refer :all` / `:use`
  (§3.3).
- **REPL-only**: exact jump lines (static jumps search the file), and aliases
  created outside the `ns` form.

Phase 0 first fixes existing tuple-token bugs on the REPL path (§3.1), so the
primary path is correct before the fallback is built.

## 2. Where things stand

Verified in the code:

| Step | Emacs | Neovim | Without a REPL today |
| --- | --- | --- | --- |
| Command entry | `clara-explorer--prepare-query` raises "Not connected to a CIDER REPL" | `init.navigate` and `provenance.open` return when `conjure.connected()` is false | refused |
| Transport (`auto`) | `--server-available-p` returns nil without a connection, so bb | `with_transport` probes over Conjure, and Conjure never calls back without a connection | Emacs: bb. Neovim: would hang once the entry check is gone |
| Token extraction | whole tuple text when point is inside a keyword-led tuple, on LHS and RHS | same | works |
| Token resolution | REPL eval of `editor-resolve-form.clj`; nil means the raw token | same; an error means the raw token | raw token only |
| Caller namespace | `cider-current-ns` (reads the buffer's `ns` form first) | `conjure.extract.context` (parses the buffer) | available |
| Query | bb needs no REPL | same | works |
| Jump | REPL step fails cleanly, then open buffer, then source roots | same (`repl_eval` fails fast offline) | works, by search |
| Default source root | the REPL's `nrepl-project-dir` | `user.dir`, else `vim.fs.root` of the origin buffer | Emacs: none unless configured. Neovim: works |

The bb runtime (`bin/editor-client.bb`) assumes tokens arrive fully qualified:
`bb-resolve-token` and `bb-token->fq-sym` do no namespace resolution.

**How the analysis names tuple types.** `clara.explorer.serialize/resolve-type`
names a tuple by `pr-str` of the form as read in the production's namespace.
`::` keywords are therefore already expanded, symbols are printed exactly as
written (never resolved), and whitespace is normalized. A tuple token resolves
correctly when its `::` keywords are expanded at **any position**, e.g.
`[:previous ::thing]` becomes `[:previous :my.ns/thing]`, and everything else is
left as written.

**Typical token mix.** In a large multi-repo ruleset workspace surveyed for this
plan, the large majority of fact-type tokens were literal qualified keywords,
which need no resolution. A significant minority used `::kw` / `::alias/kw`, in
roughly a third of the rule files. Imported records and record constructors were
rare. Static keyword resolution closes most of the gap.

## 3. Design

### 3.1 Phase 0: existing tuple-token bugs

All verified against a live REPL. For `[:previous ::thing]`:

| Path | Today | Correct |
| --- | --- | --- |
| Editor resolve form (both editors) | returns the raw text: it reads the token with `*ns*` bound (which expands `::`), then its `:else token-text` branch discards the result | `[:previous :my.ns/thing]` |
| Same, namespace not loaded | reads in the REPL's current namespace (`:user/thing`); masked today only by the raw-text bug above | unresolved (nil) |
| JVM client `read-token` (nREPL transport) | correct when the namespace is loaded; when it is not, only a token that *starts* with `::` is refused, so a tuple containing `::` resolves against the client's own namespace | unresolved |
| bb runtime | reads `::` in bb's own namespace (`:user/thing`) | unresolved |

So today a tuple with `::` works only over nREPL, with the namespace loaded.
Over bb it fails even with a REPL connected.

Fixes:

- **Resolve form** (`explorer/resources/clara/explorer/server/editor-resolve-form.clj`,
  symlinked into both editors; the JVM sync test keeps the copies identical):
  - For a successfully read vector or map, return `(pr-str form)`, matching the
    serializer.
  - When the namespace is not found and the token contains `::`, return nil, as
    it already does for a token starting with `::`.
- **JVM client** `read-token`: treat a token containing `::` as unreadable when
  the caller namespace is not found, not only one starting with it.
- **bb runtime** `read-token`: treat any token containing `::` as unresolvable.
  bb has no namespace to read it in, so an honest "no fact type found" beats a
  wrong type. §3.5's static step later turns these into real answers.

"Contains `::`" is checked outside string literals, using the scanner from
§3.3, so `[:x "a::b"]` is unaffected.

### 3.2 Resolution order

1. **Connected REPL** (editor side): the resolve form produces the token, fixed
   by phase 0. When it resolved, the token arrives qualified and nothing below
   changes it.
2. **Live JVM runtime** (nREPL transport only): the client's `resolve-token`
   uses `ns-resolve` over loaded namespaces.
3. **Static resolution** (new, both runtimes): applies only when the token still
   needs resolution (§3.3) **and** the steps above did not produce a qualified
   answer. It reads the buffer's `ns` form, sent as `:ns-form`, and checks
   candidates against the analysis.

Step 3 also helps the JVM transport. A namespace from another repo that the JVM
never loaded (the composed-registry case) has no `find-ns`, so `ns-resolve`
cannot answer it, but the `ns` form can.

### 3.3 Static resolver

A new bb-loaded namespace, `clara.explorer.server.ns-form`, kept in `server.*`
with the other editor-facing namespaces and carrying the
`:clara-rules-explorer/bb-loaded` marker that `navigate` uses. Its docstring
opens with the contrast to `clara.explorer.ns-deps`: this one reads the static
`(ns …)` **text** an editor sends, while `ns-deps` reads dependencies from a live
`Namespace` object.

It is pure: it takes text and the analysis, never touches the filesystem, and
loads nothing.

**What needs static resolution:**

- a token containing an auto-resolved keyword (`::kw`, `::alias/kw`), either the
  whole token or (last phase, §3.7) inside a tuple;
- an unqualified symbol that is not a dotted class name: `Foo`, `->Foo`,
  `map->Foo`;
- a symbol whose namespace part is an alias in the `ns` form: `r/->Foo`.

Everything else passes through untouched: literal `:ns/kw`, dotted class names
(`a.b.Foo`), strings, and tuples without `::`.

**Keyword expansion: an explicit scanner, no reader.** The same routine serves
a whole `::kw` token and, later, a tuple:

- Walk the token text, skipping string literals (with `\` escapes) and
  character literals.
- At each token boundary, replace `::name` with `:<ns-name>/name` and
  `::alias/name` with `:<alias-target>/name`, using `:as` or `:as-alias`.
- An unknown alias leaves the token unresolved.

It never calls `read-string` on a `::` token, which would expand against the
reader's own namespace. The runtime then reads the expanded text and prints it
back, which normalizes whitespace to match the serializer. It runs unchanged
under bb and the JVM, with no new dependency (decision §6.5).

**Parsing the `ns` form.** Read it with
`(read-string {:read-cond :allow :features #{:clj}} text)`. bb supports this;
verified, the `:clj` branch is picked. It extracts:

- the namespace name, which falls back to `:caller-ns` when absent;
- `:require` libspecs, including prefix lists (`[a.b [c :as c] [d :refer [x]]]`),
  `:as`, `:as-alias`, `:refer [...]`, `:refer :all`, and `:rename`;
- `:use` (treated as `:refer :all`);
- `:import`, both `(pkg Class …)` lists and dotted symbols;
- metadata and docstrings on the `ns` form, which are ignored.

An unreadable or missing `ns` form means no static resolution: the token passes
through as today.

**Class symbols** (`Foo`), first candidate the analysis knows as a fact type:

1. `:import`;
2. a record in the same namespace, `<munged-ns>.Foo`;
3. `java.lang.Foo`.

`refer` never brings in classes, so `:refer :all` plays no part here.

**Constructor symbols** (`->Foo`, `map->Foo`, `alias/->Foo`): candidates in
precedence order, each mapped through `tokens/record-ctor-class-symbol` and kept
when the analysis knows the class (or a callsite records that
`:constructor-sym`):

1. an alias-qualified symbol, expanded through `:as`;
2. an explicit `:refer [->Foo]`, honoring `:rename`;
3. the current namespace;
4. each `:refer :all` / `:use` namespace.

**No symbol tables.** The analysis is the symbol table: only names it knows can
be navigated, so `:refer :all` needs no loading or indexing of the referred
namespaces. When several `:refer :all` namespaces match, all of them are
returned, in precedence then name order.

**Symbols inside tuples are never resolved**: the serializer prints them as
written, so resolving them would break the match.

**Return value**: a vector of candidate token strings, possibly empty. The
caller decides how to use several (§3.5).

### 3.4 Contract change

- `NavigateInput` gains `(s/optional-key :ns-form) s/Str`: the text of the
  buffer's `ns` form. `FactTypeProvenanceInput`'s cursor `:input` is a
  `NavigateInput`, so provenance inherits it.
- The editor sends the **text**, not a path, so an unsaved `ns` form counts.
- The field is optional and additive: old editors keep working. No response
  shape changes.

### 3.5 Wiring in the backend

Decorate the runtime rather than change `navigate.clj`'s logic:

```clojure
(ns-form/->static-runtime base-runtime analysis ns-form)
```

returns a `NavigateRuntime` whose `resolve-token` and `token->fq-sym` call the
base first. When the base answer is not qualified and the token needs
resolution (§3.3), they fall back to the static candidates:

- `resolve-token` returns the first candidate.
- The candidate set feeds RHS resolution, which already unions candidate types
  (`resolve-rhs-types`), so navigate shows every match.

Both `navigate` and `fact-type-provenance` build the decorated runtime when the
input carries `:ns-form`. That covers bb (`bb-runtime` as the base) and the JVM
(`client`'s runtime as the base) in one place, so the transports cannot drift.

### 3.6 Editors

Both editors follow the same rules:

- **No connection plus effective transport bb**: run the command. Skip token
  resolution and send the raw token with `:ns-form`.
- **No connection plus a forced `nrepl` transport**: refuse with a clear message,
  as today.
- **`auto` with no connection**: choose bb without probing.
- **`:ns-form`**: the text of the buffer's first top-level `(ns …)` form, sent
  on every query, connected or not. The backend ignores it when the token is
  already qualified.

Emacs:

- `clara-explorer--prepare-query` no longer requires `cider-connected-p` when the
  effective transport is bb. `:conn` may be nil, and token resolution runs only
  with a connection.
- Store the origin buffer with the query, so later lookups have a project root.
- With no REPL, the default `clara-explorer-source-roots` root is the parent of
  the origin buffer's project root (`project-current`, else `clojure-project-dir`),
  matching Neovim's `vim.fs.root` fallback.
- The bb-selection prompt (`clara-explorer--bb-selection`) must not need a
  connection. Verify in step 4.

Neovim:

- `init.navigate` and `provenance.open` drop the `conjure.connected()` check when
  the transport is bb.
- `with_transport` returns `"bb"` without probing when not connected, removing
  the hang noted in §2.
- `resolve_token` is skipped when not connected; the same no-callback hang
  applies.
- Extract the `ns` form text with `jump.lua`'s form skipper, already used by
  `ns_name_from_text`.

### 3.7 Last phase: tuple tokens in static resolution

Kept separate so the main work starts without tuples.

- Apply §3.3's scanner across a tuple token, expanding every `::` keyword at any
  position (`[:previous ::thing]`, `[::thing "x"]`) and leaving strings, numbers,
  literal keywords, and symbols as written.
- No new parsing: it is the whole-keyword routine run over more text. Most of
  the work is tests and parity: tuples through both operations, both transports,
  and both editors' offline paths.

### 3.8 Out of scope

- `require` / `alias` / `refer` / `in-ns` calls outside the `ns` form.
- Symbol tables for `:refer :all` namespaces (§3.3 uses the analysis instead).
- Resolving symbols inside tuple tokens (the serializer does not, §2).
- Exact jump lines without a REPL.

## 4. Work breakdown, in dependency order

| # | Step | Where | Done when |
| --- | --- | --- | --- |
| 0 | Phase 0: tuple-token fixes on the existing paths (§3.1), plus the shared `::` scanner they use | `editor-resolve-form.clj`, `client.clj`, `bin/editor-client.bb`, new `server/ns_form.clj` (scanner only) | Z-matrix green; JVM sync test green; both editors' suites unchanged |
| 1 | Settle §6's open decisions | this doc | answers recorded here |
| 2 | Static resolver: `ns` form parsing, whole-token keywords, classes, constructors | `server/ns_form.clj` + test | S-matrix green on the JVM; the namespace loads under bb |
| 3 | Contract and runtime wiring | `schema.clj` (`:ns-form`), `navigate.clj` (decorated runtime), `client.clj`, `bin/editor-client.bb` | N-matrix green; `client_test` covers an unloaded namespace on the JVM |
| 4 | Emacs offline mode | `editor/emacs/clara-explorer.el` | E-matrix green, both tiers + `make check-elisp` |
| 5 | Neovim offline mode | `editor/neovim/lua/clara-explorer/{init,provenance,conjure,jump}.lua` | V-matrix green, `make check` |
| 6 | bb/JVM parity | `explorer/test/.../artifacts/editor_client_bb_test.clj` | B-matrix green |
| 7 | Tuple tokens in static resolution (§3.7) | `server/ns_form.clj`, tests in every layer | T-matrix green |
| 8 | Docs | `docs/explorer-editor-navigation*.md`, `editor/AGENTS.md`, progress §10, both editors' settings docs | "REPL optional over bb" documented, with the §1 support levels |
| 9 | Verification | §7 | green, plus a manual offline smoke in each editor |

## 5. Test matrix

### Z — phase 0, existing paths

| # | Case | Expected |
| --- | --- | --- |
| Z1 | Resolve form, `[:previous ::thing]`, namespace loaded | `"[:previous :my.ns/thing]"` (the `pr-str`'d read form) |
| Z2 | Resolve form, same, namespace not loaded | nil |
| Z3 | Resolve form, `[:x "a::b"]` with no namespace | the tuple unchanged (the `::` is inside a string) |
| Z4 | Resolve form, maps and literal-keyword tuples | `pr-str` of the read form; same as the serializer |
| Z5 | JVM `read-token`, tuple with `::`, namespace not loaded | unreadable, not resolved against the client's namespace |
| Z6 | bb, raw tuple with `::` | unresolved ("no fact type found"), never `:user/…` |
| Z7 | bb, REPL-resolved tuple | the producers/consumers of that tuple type, matching the nREPL answer |
| Z8 | Scanner | `::` found outside strings and character literals only; boundaries respected |

### S — static resolver (`ns_form_test.clj`)

| # | Case | Expected |
| --- | --- | --- |
| S1 | `::kw` | `:<ns>/kw` from the form's name; falls back to `:caller-ns` when the form has none |
| S2 | `::alias/kw` | through `:as` and through `:as-alias`; an unknown alias stays unresolved |
| S3 | Prefix lists | `[a.b [c :as c] [d :refer [x]]]` yields aliases and refers |
| S4 | Reader conditionals | the `:clj` branch is used; the `:cljs` branch is ignored |
| S5 | Class symbol | `:import` (list and dotted forms), then same-namespace record, then `java.lang`, each kept only when the analysis knows it |
| S6 | Constructor precedence | alias-qualified, then explicit `:refer` (with `:rename`), then current namespace, then `:refer :all` |
| S7 | Ambiguous `:refer :all` | two referred namespaces define known `->Foo` classes: both returned, in deterministic order |
| S8 | Unknown name | a candidate the analysis does not know is dropped; no match yields `[]` |
| S9 | Pass-through | literal `:ns/kw`, `a.b.Foo`, strings, and tuples without `::` come back unchanged |
| S10 | Bad input | missing, unreadable, or non-`ns` text yields no static resolution and no exception |
| S11 | `::` read safety | `::` tokens are expanded by the scanner, never passed to `read-string` |

### N — backend wiring (`navigate_test.clj`, hand-built analyses)

| # | Case | Expected |
| --- | --- | --- |
| N1 | `::kw` with `:ns-form` | the same targets as the qualified keyword, both operations |
| N2 | Qualified token plus `:ns-form` | identical to the no-`:ns-form` answer (static is a no-op) |
| N3 | Base runtime already resolved | the base answer wins; static is not consulted |
| N4 | RHS constructor through `:refer :all` | navigate unions every candidate's types |
| N5 | Provenance root with several candidates | the first candidate per §6.2's decision |
| N6 | Schema | `:ns-form` optional; a non-string is rejected |

### B — bb/JVM parity (`editor_client_bb_test.clj`)

| # | Case | Expected |
| --- | --- | --- |
| B1 | `::kw` navigate and provenance over the composed registry | bb `=` JVM registry mode (sources stripped) |
| B2 | Unloaded namespace on the JVM | static fallback gives the same answer as bb |

### E — Emacs (`clara-explorer-test.el`, both tiers)

| # | Case | Expected |
| --- | --- | --- |
| E1 | Offline prepare | no connection plus bb: an input with the raw token and `:ns-form`, and no REPL call |
| E2 | Offline commands | navigate and both provenance commands run over a stubbed bb |
| E3 | Forced nREPL offline | a clear refusal |
| E4 | Connected | the REPL resolution is sent; `:ns-form` rides along unchanged |
| E5 | Offline default root | the parent of the origin buffer's project root |

### V — Neovim (plenary)

| # | Case | Expected |
| --- | --- | --- |
| V1 | Offline navigate and provenance | run over a stubbed bb; `eval_edn` is never called |
| V2 | `with_transport` offline | `"bb"` with no probe (no hang) |
| V3 | `resolve_token` offline | skipped (no hang); the raw token is sent with `:ns-form` |
| V4 | Forced nREPL offline | a clear refusal |
| V5 | `ns`-form extraction | metadata, docstring, and reader conditionals survive as text |

### T — tuple tokens, static (last phase)

| # | Case | Expected |
| --- | --- | --- |
| T1 | `[:previous ::thing]` | `[:previous :my.ns/thing]` |
| T2 | `[::thing "x"]`, `[::a/thing :b/c]` | first- and mixed-position expansion, through `:as` / `:as-alias` |
| T3 | Symbols and strings inside a tuple | unchanged, including a string containing `::` |
| T4 | Whitespace and newlines inside a tuple | the read-and-print step normalizes to the serializer's form |
| T5 | Navigate and provenance from a tuple token | same targets as the resolved tuple, bb `=` JVM |
| T6 | Both editors offline | a tuple at point resolves through `:ns-form` over a stubbed bb |

## 6. Open decisions (step 1)

1. **Field name**: `:ns-form` (recommended; it is literally the form's text) or
   `:caller-ns-form`, to pair with `:caller-ns`.
2. **Several candidates at a single-type root** (provenance from a cursor):
   take the first by precedence (recommended; deterministic, consistent with
   N13's first-name-sorted rule for scoped callsites), or return an `{:error …}`
   listing the candidates.
3. **Offline when the transport is forced to `nrepl`**: refuse (recommended), or
   silently use bb.
4. **Where the editors' offline check lives**: one shared helper per editor
   (recommended), or inline in each command.
5. **`::` expansion**: the explicit scanner (recommended; a few dozen lines, no
   dependency, the same code in bb and the JVM), or `edamame`'s `:auto-resolve`
   parse. edamame is built into bb, but on the JVM it is only present
   transitively today, so it would need an explicit `deps.edn` entry. Its gain
   is full-syntax parsing, which tuple tokens do not need: the editors only
   extract keyword-led tuples without parentheses, `?`, or `=`.

## 7. Verification

```bash
cd explorer
make test
make lint
make reflection-check
make format-check
make bb-smoke-test   # CLJ_CONFIG workaround in a sandbox; see editor/AGENTS.md

cd ../editor/emacs
make test-tier1
make test
make check-elisp

cd ../neovim
make check           # mise exec -- make check where stylua/selene come from mise
```

Manual smoke, per editor, with no REPL started and the bb transport over a
composed registry:

- navigate from a `::kw`, from a literal keyword, and (after the last phase) from
  a tuple with a `::` keyword in a non-first position;
- open a producer and a consumer provenance tree;
- jump into a sibling repo through the source roots.

Then connect a REPL and confirm the same commands use the REPL resolution.
