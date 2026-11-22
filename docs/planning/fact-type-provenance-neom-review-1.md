# Review 1: Fact Type Provenance (`fact-type-provenance`)

Branch reviewed: `fact-type-provenance` @ `ba2239e` against `main` (`ff55bc6`),
measured against `docs/planning/fact-type-provenance-progress.md`,
`AGENTS.md`, `editor/AGENTS.md`, and the `clojure-engineering` standards.

No code was modified on this pass.

## Summary

The core architecture is solid: the thin-editor boundary is respected cleanly
(no analysis-map inspection or fact-type shortening in Emacs), the POC
vocabulary and `lsp-treemacs` dependency are completely removed, the composed
`:unknown-fact-types` fix and `:unit` plumbing are clean, Emacs byte-compiles
and passes all 123 ERT tests (`make check-elisp test-tier1 test`), and there are
**zero** lingering references to `docs/planning/` or ephemeral plan section/case
IDs outside `docs/planning/`.

However, this review found **several real correctness bugs** (including a
vacuously-passing bb parity test, `:outputs :unknown` being dropped at
`:depth > 0`/`:full` and `:production` roots, an interactive Emacs `RET` jump
bug where the missing-namespace diagnostic rarely fires and clobbers point in
the wrong buffer, and `TAB` re-fetching instead of reading `child-cache`), a
broken sandbox workaround in `editor/AGENTS.md`, several test-matrix gaps,
some `clojure-engineering` violations, and a few stale docs.

---

## 1. Must Fix — Correctness Bugs & Broken Tests

### 1.1 B2 bb variant parity test vacuously asserts `(= #{} #{})`
- **Where:** `explorer/test/clara/explorer/artifacts/editor_client_bb_test.clj:302-308` (`editor-client-provenance-variant-unit-matches-mainline-test`)
- **Problem:** `get-in` is placed inside `->>` (thread-last):
  ```clojure
  (->> (bb-provenance sel input)
       (get-in [:node :children])
       (map :name)
       set)
  ```
  That expands to `(get-in [:node :children] response)`, which looks up the map
  entries of `response` in the vector `[:node :children]`, always returning
  `nil`. Both `(production-names mainline)` and `(production-names variant)`
  evaluate to `#{}`, so the test passes even if `bb-provenance` returns an
  `:error` map.
- **Fix:** Use `(-> (bb-provenance sel input) (get-in [:node :children]))` (or
  `(into #{} (map :name) (get-in answer [:node :children]))`), assert
  `(not (contains? answer :error))`, and assert `(seq (production-names mainline))`.

### 1.2 Backend drops `:outputs :unknown` and marks leaves `:seen? true` at `:depth > 0` / `:full` / `:production` roots
- **Where:** `explorer/src/clara/explorer/server/navigate.clj:442-470` (`->production-node`) and `521-538` (`->production-root-node`)
- **Problem:**
  1. In `->production-node`, `leaf` (`->consumer-leaf` / `->producer-leaf`) is
     only computed when `(not expand?)`. At `:depth 1` or `:depth :full`,
     `expand?` is `true`, so an `:unlinked-rule` takes the `expand?` branch,
     gets `:children []` from `->production-children`, and **silently loses
     `:outputs :unknown`**. Likewise, `->production-root-node` never consults
     `->consumer-leaf`, so expanding an `:unlinked-rule` as a `:production` root
     also drops `:outputs :unknown` (and queries/`:no-output-types` rules are
     not gated by `->consumer-leaf` there either).
  2. Because leaf productions enter the `expand?` branch at `:depth :full`,
     `(vswap! seen conj name)` records them in `seen`. If a leaf (e.g. a query
     or `:unlinked-rule`) appears a second time in the same `:depth :full`
     response, the second occurrence gets `:seen? true`, omits `:children []`,
     and omits `:outputs :unknown`. Emacs then renders `↑ shown above` on a leaf
     that had nothing expanded under it, and lets `TAB` issue a wasted round-trip.
- **Fix:** Compute the leaf check (`->producer-leaf` / `->consumer-leaf`)
  *before* checking `seen?` / `expand?` (only after `cycle?`). If a production
  is a leaf, always return the leaf map (`:children []`, plus `:outputs :unknown`
  when applicable) without adding it to `seen` or marking it `:seen? true`.
  Apply the same leaf handling in `->production-root-node`. Pin this in
  `navigate_test.clj` at `:depth 1`, `:depth :full` (including a repeated leaf),
  and with a `:production` root.

### 1.3 Emacs `clara-explorer--goto-fallback` fails to report missing namespaces from file buffers and clobbers point
- **Where:** `editor/emacs/clara-explorer.el:861-900` (`clara-explorer--goto-fallback`) and `1409-1412` (`clara-explorer-provenance-visit`)
- **Problem:**
  1. `clara-explorer-provenance-visit` selects the MRU window *before* calling
     `clara-explorer--goto`. In real usage that MRU window is a file-visiting
     `.clj` buffer (and direct `navigate` calls also start in a `.clj` buffer),
     so `(buffer-file-name origin)` is non-nil.
  2. When `cider-find-ns` cannot find `ns` on the classpath and leaves
     `(current-buffer)` equal to `origin`, line 881 checks
     `(and (eq (current-buffer) origin) (not (buffer-file-name origin)))` — which
     is `nil`! Execution falls through to the `t` branch, runs
     `(goto-char (point-min))` in the **wrong `.clj` buffer**, searches it for
     `rule-name` (risking a false match or printing `"rule-a not found in <ns>"`),
     and never prints the classpath diagnostic.
  3. When `cider-find-ns` signals an error caught by `condition-case` on line
     878, it does not return early — it falls straight through into the `cond`,
     clobbering point and overwriting the error message.
  4. `provenance-goto-fallback-reports-a-missing-namespace-test` only passed
     because it ran in `(with-temp-buffer ...)` where `(buffer-file-name)` is `nil`.
- **Fix:** Return `nil` directly from the `condition-case` error handler. When
  `(eq (current-buffer) origin)`, only proceed to search `origin` if `origin`
  is actually the target namespace (e.g., `(equal (cider-current-ns) ns)`);
  otherwise emit the classpath message and return `nil` without moving point.
  Add an ERT test where `origin` is a file-visiting buffer in a different
  namespace.

### 1.4 Emacs `TAB` re-fetches on re-expand instead of using `clara-explorer--provenance-children`
- **Where:** `editor/emacs/clara-explorer.el:1356-1388` (`clara-explorer--provenance-toggle-path`)
- **Problem:** Collapsing a production removes `path` from
  `clara-explorer--provenance-expanded` while keeping its fetched children in
  `clara-explorer--provenance-children`. Pressing `TAB` again never consults
  `clara-explorer--provenance-children` and always re-fetches from the backend
  (even for rows seeded from `:depth 1` / `:depth :full`).
- **Fix:** Check `(gethash path clara-explorer--provenance-children :miss)`
  before fetching. When cached (and not a single `:message` error node, which
  should retry on re-expand), just mark `path` expanded and return `t`. Add a
  collapse + re-expand cache-hit test in `clara-explorer-test.el`.

### 1.5 Emacs `clara-explorer--edn-value` does not serialize vectors element-wise
- **Where:** `editor/emacs/clara-explorer.el:125-136` (`clara-explorer--edn-value`)
- **Problem:** Vectors fall through to `(t (prin1-to-string x))`. That only
  produces valid EDN by coincidence for unpropertized string vectors. Any
  propertized string inside `:ancestors` would print `#("..." 0 N (...))`
  (invalid EDN), and booleans/plists inside a vector would not print as EDN.
- **Fix:** Add `((vectorp x) (concat "[" (mapconcat #'clara-explorer--edn-value x " ") "]"))`.

### 1.6 Emacs `tabulated-list-format` enables sorting on a tree buffer (and omits direction)
- **Where:** `editor/emacs/clara-explorer.el:1450` (`clara-explorer-provenance-mode`)
- **Problem:** `[("Provenance" 0 t)]` sets `SORT` to `t`. Clicking the column
  header or pressing `S` (`tabulated-list-sort`, inherited from
  `tabulated-list-mode-map`) sorts the indented rows lexicographically and
  destroys the tree hierarchy. Also, nothing in `*Clara Fact Type Provenance*`
  currently indicates whether the user is viewing **producer** or **consumer**
  provenance.
- **Fix:** Disable sorting (`[("Provenance" 0 nil)]`). Consider setting the
  column header from `direction` (e.g. `"Producer Provenance"` /
  `"Consumer Provenance"` with `tabulated-list-init-header` on load) so the
  direction is visible at a glance.

### 1.7 Scoped RHS cursor resolution does not fall back to global resolution (and `:lhs` omits `production` ns fallback)
- **Where:** `explorer/src/clara/explorer/server/navigate.clj:554-576` (`->scoped-rhs-candidates`, `resolve-cursor-fact-type`)
- **Problem:**
  1. Per the contract (§2.1), when `:production` is non-nil and structural
     `:side` is `:rhs`, token resolution should check that production's
     callsites *first before falling back to global resolution*. Currently
     `resolve-cursor-fact-type` uses an `if` with no fallback: if
     `->scoped-rhs-candidates` returns `#{}`, `->global-candidates` is never
     consulted.
  2. When `:production` is present, `:side` is `:lhs` (or absent), and
     `:caller-ns` is omitted, `resolve-cursor-fact-type` passes `nil` to
     `->global-candidates` instead of falling back to `production`'s namespace
     like `navigate-scoped` (line 220) and `->scoped-rhs-candidates` (line 559)
     do.
- **Fix:** Compute `resolve-ns` once as `(or caller-ns-sym (some-> production symbol namespace symbol))`, and in the `:rhs` branch use
  `(or (not-empty (->scoped-rhs-candidates ...)) (->global-candidates ...))`.

### 1.8 Non-integer, non-`:full` `:depth` silently degrades to `0` outside schema-validated tests
- **Where:** `explorer/src/clara/explorer/server/navigate.clj:594-595` (`fact-type-provenance`)
- **Problem:** `(and (integer? depth) (neg? depth))` does not reject `:depth :bogus` or `:depth "1"` when `st/validate-schemas` is inactive (production JVM and all `editor-client.bb` runs); it silently behaves like `:depth 0`.
- **Fix:** Reject anything matching `(not (or (= :full depth) (and (integer? depth) (not (neg? depth)))))`.

### 1.9 Documented Babashka sandbox workaround in `editor/AGENTS.md` does not work
- **Where:** `editor/AGENTS.md:85-92` (`## Babashka sandbox note`)
- **Problem:** The doc instructs running `editor-client.bb` with
  `BABASHKA_CLASSPATH=explorer/src:explorer/resources …`. Running that command
  still fails with `java.io.IOException: Operation not permitted` inside
  `babashka.deps/add-deps` because `editor-client.bb:39` unconditionally loads
  `explorer/bin/bootstrap.bb` (which calls `deps/add-deps` for
  `prismatic/schema` and writes to `~/.clojure/.cpcache`), and
  `explorer/src:explorer/resources` does not include `prismatic/schema` on the
  classpath anyway.
- **Fix:** Verify an actually working sandbox invocation (e.g., whether
  redirecting cache/home or running outside the sandbox is required) and update
  the note so it does not recommend a failing command.

---

## 2. Test Coverage Gaps (vs. §6.1 Matrix)

1. **`type-name->short-name` shortening is completely untested in Clojure tests:**
   - Every test in `explorer/test/clara/explorer/server/navigate_test.clj` uses
     single-letter type names (`"T"`, `"S"`, `"V"`, ...), and neither
     `registry_server_test.clj` nor `client_test.clj` asserts `:type-short` or
     `:short` on `:matches`. Replacing `type-name->short-name` with `identity`
     would not fail a single Clojure test.
   - Add tests verifying shortening on dotted class/record names
     (`"com.example.DocumentCheck"` -> `"DocumentCheck"`) and non-shortening on
     keywords (`":loan/status"`), strings (`"\"a.b\""`), tuples
     (`"[:loan/status \"a.b\"]"`), and maps (`"{:k :a.b}"`), plus `:short` on a
     dotted bridge match in N2/R1.
2. **N7 (`provenance-diamond-stays-linear-test`) only tests a 1-level diamond:**
   - `explorer/test/clara/explorer/server/navigate_test.clj:191-202` only tests
     `k = 1`, which cannot distinguish linear growth from $2^k$ blowup. Build a
     chained `k`-level diamond (e.g. `k = 6`) and assert the node count stays
     linear in `k`.
3. **N11 (`provenance-ordering-test`) misses three ordering invariants:**
   - `explorer/test/clara/explorer/server/navigate_test.clj:266-284` does not
     assert: (a) producer `:fact-type` children (`:lhs-types`) preserving
     declaration order (e.g. `["Z" "A"]`), (b) consumer production children
     name-sorted with rules and queries interleaved, or (c) a consumer
     `:fact-type` child that is both inserted and retracted appearing once in
     `:insert-types` order with `:vias [:insert :retract]`.
4. **B3 (`editor-client-provenance-full-depth-guard-test`) does not pin a count or tree equality:**
   - `explorer/test/clara/explorer/artifacts/editor_client_bb_test.clj:310-319`
     only compares `(= (node-count jvm) (node-count bb))`. If `navigate.clj`
     regressed to exponential growth in both runtimes, B3 would still pass.
     Assert full equality `(= jvm-answer bb-answer)` and pin the expected node
     count (or a tight upper bound).
5. **E5 (`provenance-tab-request-shape-test`) does not test expanding a `:seen?` row on demand:**
   - `editor/emacs/test/clara-explorer-test.el:1469-1530` checks root, nested
     ancestors, and non-fetching rows, but omits the explicit E5 requirement
     that a `:seen? t` row *does* trigger a `:depth 0` fetch on `TAB`.
6. **Emacs label tests don't cover `(insert+retract)` or realistic `:vias` shapes:**
   - `editor/emacs/test/clara-explorer-test.el:1422` tests `:via :retract :vias [:retract]`
     on a `:production` node, whereas the backend only emits `:vias` on a
     production node when both insert and retract occur (`[:insert :retract]`).
     Cover producer `:via :retract` (without `:vias`), producer
     `:via :retract :vias [:insert :retract]` -> `(insert+retract)`, and
     consumer `:fact-type` `:vias [:retract]` / `[:insert :retract]`.
7. **Emacs file test leaks visited buffer:**
   - `editor/emacs/test/clara-explorer-test.el:1606-1624`
     (`provenance-goto-fallback-finds-the-form-test`) visits a temp file with
     `find-file-noselect` and deletes the file in `unwind-protect` without
     killing the buffer.

---

## 3. Clojure Engineering Standards (`clojure-engineering`)

### 3.1 Docstring qualified references & accuracy
- `explorer/src/clara/explorer/server/schema.clj:73,124` — bare
  `` `fact-type-provenance` `` should be
  `` `clara.explorer.server.navigate/fact-type-provenance` ``.
- `explorer/src/clara/explorer/artifacts/shared/compose.clj:136` — bare
  `` `TypeReference` `` should be `` `clara.explorer.server.api/TypeReference` ``.
- `explorer/test/clara/explorer/server/navigate_test.clj:2,5` — uses
  fully-qualified `` `clara.explorer.server.navigate/fact-type-provenance` ``
  and `` `clara.explorer.artifacts.shared.rehydrate/rehydrate-analysis` ``
  despite `navigate` and `shared-rehydrate` being aliased in `:require`; use the
  in-scope aliases per the skill.
- `explorer/test/clara/explorer/server/registry_server_test.clj:164` —
  `"Runs F with..."` uses uppercase Elisp parameter style instead of `` `f` ``.
- `explorer/test/clara/explorer/artifacts/editor_client_bb_test.clj:106` —
  `bb-provenance` docstring says `` `selection` `` while its parameter is `sel`
  (and `selection` is a separate top-level `def`).
- `explorer/src/clara/explorer/server/navigate.clj:426` — `->production-node*`
  docstring says `` `ctx` already carries this production's name in `:ancestors` ``,
  which is untrue for `->production-node` callers (`->production-node*` does not
  read `:ancestors`).

### 3.2 String formatting (`format` over `str` for placeholders)
- `explorer/src/clara/explorer/server/navigate.clj:527,592,595,598,611,618` —
  replace `(str "no production named " name)` etc. with `(format "no production named %s" name)`.
- `explorer/src/clara/explorer/server/client.clj:289` — `(format "internal error: %s" ...)`
  (matches pre-existing `navigate`, minor).
- `explorer/test/clara/explorer/server/navigate_test.clj:30` —
  `(symbol (str caller-ns "/" token))` can be 2-arity `(symbol (str caller-ns) token)`.

### 3.3 Threading & transducers
- `explorer/src/clara/explorer/server/navigate.clj:366` (`->producer-targets`) —
  `(->> (group-by :name deps) ...)` leaves a nested call as the first form;
  fully thread as `(->> deps (group-by :name) ...)` (matching
  `->consumer-targets` on line 391).
- `explorer/src/clara/explorer/server/navigate.clj:363,374,376,389` — uses
  2-arity `(into coll (keep :name xs))` instead of 3-arity transducer
  `(into coll (keep :name) xs)`.
- `explorer/src/clara/explorer/server/navigate.clj:480-484`
  (`->consumer-child-types`) — `(->> (distinct (concat inserted retracted)) (mapv ...))`
  has a nested first form; prefer `(into [] (comp (distinct) (map ...)) (concat inserted retracted))`.
- `explorer/src/clara/explorer/server/navigate.clj:493-494`
  (`->production-children`) — `(mapv f (distinct (keep :name ...)))` ->
  transducer `(into [] (comp (keep :name) (distinct) (map ...)) (:lhs-types summary))`.
- `explorer/test/clara/explorer/artifacts/compose_test.clj:148` — mixes `->`
  inside `->>` (`(->> (-> composed ...) (filter ...) first)`).

### 3.4 Naming, shadowing & concrete return types
- `explorer/src/clara/explorer/server/navigate.clj:428,447,490,524` — local
  bindings / params named `name` shadow `clojure.core/name` and are inconsistent
  with sibling helpers (`p-name` on lines 368, 394, 405, 416). Use `p-name`
  consistently.
- Bare-noun helper names in test namespaces (`clojure-engineering` applies to
  `test/` as well): `provenance`, `cursor-input`, `child-names`,
  `production-nodes` (`navigate_test.clj`); `provenance`, `cursor-root`,
  `by-name`, `production-nodes` (`registry_server_test.clj`);
  `provenance-input` (`client_test.clj`); `node-count`, `provenance-input`
  (`editor_client_bb_test.clj`). Prefer `->cursor-input`, `->provenance-input`,
  `collect-production-nodes`, `index-by-name`, `count-nodes`, etc.
- `production-nodes` (duplicated in `navigate_test.clj:90` and
  `registry_server_test.clj:192`) returns a lazy seq (`concat` + `mapcat`)
  rather than a concrete vector.

### 3.5 Small Clojure cleanups
- `explorer/bin/editor-client.bb:212` — misplaced comment
  `;; Registry discovery: the editors shell out here rather than walking the tree themselves.`
  now sits above `(= flag "--provenance")` instead of `(= flag "--list-units")`.
- `explorer/src/clara/explorer/artifacts/shared/rehydrate.clj:65` — unused
  `:as _production` in `(let [{:keys [unit] :as _production} ...] ...)`.
- `explorer/src/clara/explorer/server/navigate.clj:417` (`->consumer-leaf`) —
  `(and (empty? (:insert-types summary)) (empty? (:retract-types summary)))`
  avoids allocating a lazy `concat`.
- `explorer/src/clara/explorer/server/navigate.clj:355` (`hierarchy-bridge?`) —
  guard `(and (seq matches) (not= (mapv :name matches) [parent-type]))`.
- `explorer/src/clara/explorer/server/schema.clj:103` — `:children` on
  `:fact-type` is marked `s/optional-key`, though `:fact-type` nodes always emit
  `:children`.

---

## 4. Elisp Quality & Simplifications (`editor/emacs/clara-explorer.el`)

Overall the Elisp is clean, idiomatic, byte-compiles cleanly, and stays thin.
In addition to the bugs in §1 (items 1.3–1.6), a few targeted simplifications:

1. **Discarded `plist-put` return value (`clara-explorer--navigate-input`, lines 968–974):**
   Per the Elisp manual, callers should always use the return value of
   `plist-put`. Simplify to:
   ```elisp
   (plist-put (copy-sequence (plist-get query :input)) :side direction)
   ```
2. **Convoluted `cl-loop` + `push` + `nreverse` (`clara-explorer--provenance-production-ancestors`, lines 1339–1347):**
   Replace with:
   ```elisp
   (vconcat (cl-loop for i from 1 below (1- (length path)) by 2
                     collect (aref path i)))
   ```
3. **Redundant EDN serialization & implicit default in `op` dispatch (`clara-explorer--op-code:764-771`, `clara-explorer--op-result:980-998`):**
   `clara-explorer--op-result` computes `input-edn` on line 989, then ignores it
   on the `nrepl` branch where `clara-explorer--op-code` calls
   `clara-explorer--edn-map` a second time. Pass `input-edn` through (or compute
   it once), and use `pcase` on `op` (`'navigate` / `'fact-type-provenance` /
   error on unknown) instead of `(if (eq op 'navigate) ... <default to provenance>)`.
4. **`clara-explorer-provenance-depth` `:type` (lines 1126–1133):**
   Prefer `(choice (const :tag "Immediate (0)" 0) (natnum :tag "Production levels") (const :tag "Full" full))`
   so Customize disallows negative integers.
5. **Minor UX / duplication notes:**
   - `clara-explorer-provenance-refresh:1425` and `clara-explorer--provenance-display:1472`
     duplicate `(setq tabulated-list-entries (clara-explorer--provenance-load ...)) (tabulated-list-print t)`.
   - Expanding a `:seen? t` row on demand via `TAB` leaves the `↑ shown above`
     suffix on its label while expanded (cosmetic).
   - `clara-explorer-provenance-mode-map` binds both `RET` and `<return>`; if
     minor/evil modes can shadow `TAB`, binding `<tab>` alongside `TAB` is
     consistent.

---

## 5. Documentation Accuracy & Sync

1. **`docs/explorer-graph-api.md` & `ui/src/lib/types/api.ts` missing `ProductionDep :unit`:**
   - `explorer/src/clara/explorer/server/api.clj:68` added `(s/optional-key :unit) s/Str`
     to `ProductionDep`, and `shared/rehydrate.clj` populates it in composed /
     registry HTTP responses. Per `AGENTS.md` (*"If the API contract changes,
     update `docs/explorer-graph-api.md`"*), update `ProductionDep` in
     `docs/explorer-graph-api.md:136-142` and `ProductionReference` in
     `ui/src/lib/types/api.ts:33-39` with optional `unit`.
2. **`editor/AGENTS.md:85-92`:**
   - Fix the non-working `BABASHKA_CLASSPATH` sandbox workaround (see §1.9).
3. **`docs/explorer-editor-navigation.md`:**
   - Line 27: still says `"All semantics live in clara.explorer.server.client/navigate"`
     — update to include `client/fact-type-provenance` (and pure bodies in
     `clara.explorer.server.navigate`).
   - Line 145: `":external? on producer edges"` -> `":external? on producer-direction :fact-type nodes"`
     (root fact-type nodes have no incoming edge).
   - Line 149: `":children absent means expandable; [] means a known leaf"` ->
     also note `:children` is absent when stopped by `:cycle? true` or
     `:seen? true` (matching `schema.clj:90`).
4. **`docs/explorer-editor-navigation-emacs.md`:**
   - Line 184: Testing table still says `35+ passed` (now `123`) and
     `make test-unit (no Eldev)` instead of aligning with `editor/AGENTS.md`'s
     `make test-tier1` + `make test` split.
5. **`explorer/docs/persisted-artifacts.md:309-325`:**
   - `## Navigating offline: editor-client.bb` still only documents
     `client/navigate` and the unflagged invocation; add `--provenance` (and
     optionally `--list-units`).
6. **File headers & ns docstrings:**
   - `editor/emacs/clara-explorer.el:12-26,50` — Commentary and
     `clara-explorer-transport` docstring still only list `navigate` and the
     original 4 commands, omitting the two provenance commands.
   - `explorer/src/clara/explorer/server/navigate.clj:2-10` and
     `explorer/src/clara/explorer/server/client.clj:2-15` — ns docstrings still
     describe only `navigate`, not `fact-type-provenance`.
   - `explorer/bin/editor-client.bb:2-4,30` — top summary and pipeline line
     still mention only `navigate` (lines 7 & 15–18 are already updated).
7. **`docs/planning/fact-type-provenance-progress.md`:**
   - Line 3 still says `Status: proposed (consolidated, ready to implement)` even
     though steps 1–10 in §7 are all checked `[x]`.
   - Lines 620 & 623 say `N1–N12` (missing `N13`, which is implemented).

---

## 6. Ephemeral `docs/planning/` References & Branch Hygiene

- **Verified clean:** Repo-wide searches outside `docs/planning/` found **zero**
  references to `docs/planning/`, `fact-type-provenance-progress`, `§`-numbered
  plan sections, test-matrix IDs (`N1`–`N13`, `R1`–`R7`, `L1`–`L5`, `B1`–`B3`,
  `E1`–`E8`), or POC terms (`lhs-producers`, `producer-hierarchy`,
  `lsp-treemacs`).
- **Branch scope note:** Commit `8d52e00` ("pass 11 impl") also stripped older
  plan-section labels from unrelated test and Neovim files (`analyze_test.clj`,
  `annotations_merge_test.clj`, `annotations_test.clj`, `serve_test.clj`,
  `analyze_test_rules.clj`, `edn.lua`, `token.lua`). Those edits are clean and
  desirable — just note that they widen the PR diff outside the provenance
  feature if you prefer keeping PRs strictly scoped.

---

## Response from implementation (pass 13)

Everything in §1–§5 was checked against the branch and nearly all of it was
acted on; the per-item disposition is recorded in
`fact-type-provenance-progress.md` (§7 step 11 and §7.1). This section contains
only what is *not* in that "accepted" set: the critiques that do not hold as
written, and the places the prescription was right in principle but unsafe or
unspecified as stated. Unless noted, the underlying defect was still fixed.

### Vetoed — the critique does not hold as written

1. **§1.4, "(even for rows seeded from `:depth 1` / `:depth :full`)".** Not
   accurate. `clara-explorer--provenance-seed` puts every production path whose
   children arrived populated into **both** `clara-explorer--provenance-children`
   and `clara-explorer--provenance-expanded`, and
   `clara-explorer--provenance-toggle-path` checks `expanded` first. So the first `TAB` on a seeded row *collapses* it and
   sends nothing; the wasted round-trip only happens after the user collapses a
   row and then re-expands it. The bug is real (re-expand ignored the cache), the
   parenthetical is not, and the fix covers both cases. Pinned by
   `provenance-collapse-then-expand-uses-the-cache-test`.

2. **§1.2, "queries and `:no-output-types` rules are not gated by
   `->consumer-leaf` there either".** No observable consequence: both already
   answered `:children []` through `->production-children`
   (`->consumer-child-types` is empty for them), so the pre-fix production root
   emitted exactly what a leaf gate emits. The genuine losses in that bullet were
   `:unlinked-rule`'s `:outputs :unknown` — at `:depth > 0`/`:full` and at a
   `:production` root — and the spurious `:seen?` on a repeated leaf. All leaf
   kinds are gated now regardless.

3. **§3.5, "`hierarchy-bridge?` should guard `(and (seq matches) …)`" as a
   correctness fix.** Not a bug: at that call site `:matches` is the intersection
   of the production's referenced types with the same closure that produced the
   edge, so it can never be empty. The guard was added anyway as belt-and-braces
   and changes no reachable output. Worth knowing if you were tracking it as a
   defect rather than hardening.

### Altered — right diagnosis, prescription unsafe or under-specified

4. **§1.2's fix instruction, applied literally, breaks termination.** "Compute
   the leaf check before `seen?`/`expand?` (only after `cycle?`)" is the right
   shape, but the rewritten predicate must also keep `(not cycle?)` inside
   `expand?`. With the leaf short-circuit in place, an on-path production is
   neither a leaf nor seen, so it expands forever — reproduced as an immediate
   `StackOverflowError` in `provenance-cross-type-cycle-terminates-test`. Shipped
   form gates only `seen?` and the depth decision on the leaf result and keeps
   the cycle guard in `expand?`.

5. **§2.4 (B3), "pin the expected node count (or a tight upper bound)".** Took
   the bound option: `(= jvm-answer bb-answer)` for full structural equality plus
   `(< 1 nodes 50)` against the measured 25. An exact 25 would fail for unrelated
   reasons the next time the example registry is regenerated
   (`make regen-artifacts`); a path-only-cycle regression that reintroduces
   duplication blows past 50 immediately.

6. **§1.8, "outside schema-validated tests".** The framing is correct, and the
   test now makes the precondition explicit rather than implicit.
   `navigate_test.clj` installs `st/validate-schemas`, so a bad `:depth` there
   throws `ExceptionInfo` from the schema before the body runs (confirmed:
   `s/fn-validation?` is `true` inside the fixture, `false` outside). The test
   therefore asserts `(s/fn-validation?)` first, switches validation off only
   around the guard assertions (the `editor-client.bb` / JVM-shell posture), and
   restores it afterwards. Without that, the test would be asserting schema
   behaviour and silently stop covering the guard.

### Accepted in spirit, remedy replaced

7. **§1.9 / §5.2 (the sandbox note).** Your §1.9 hint — "whether redirecting
   cache/home or running outside the sandbox is required" — is exactly what
   shipped: `CLJ_CONFIG=<writable dir>` redirects the tools.deps cache, so
   `bin/bootstrap.bb`'s unconditional `deps/add-deps` has somewhere to write.
   Verified with a fresh cache dir (`make bb-smoke-test` green). For the record,
   §5.2's phrasing ("fix the non-working `BABASHKA_CLASSPATH` workaround") reads
   as making that variable work; no classpath value can, because `add-deps` runs
   before the classpath is consulted. The note now says so explicitly.

### No action needed

8. **§6's branch-scope note.** The widened `8d52e00` diff (stripping old
   plan-section labels from unrelated tests and Neovim files) was intentional;
   left as is.

### Also for the record

- **§3.3's four `into` sites were all genuinely 2-arity** at the reviewed
  revision (`navigate.clj:363, 374, 376, 389`), so that critique was accepted in
  full. An earlier note I wrote in the progress doc claimed part of that list was
  already 3-arity; that was my error and has been corrected there.
- **§5.4's test counts** landed slightly differently after the review fixes: the
  Emacs table now reads `123 expected + 5 skipped` for Tier 1 (`make test-tier1`,
  no Eldev) and documents the `make test-tier1` + `make test` split; Tier 2 via
  Eldev is 128/128 with 0 skipped.
- **Verification after the pass:** `explorer` — 559 tests / 3741 assertions,
  `lint` 0/0, reflection clean, `format-check` clean, `bb-smoke-test` green (with
  `CLJ_CONFIG`); `editor/emacs` — Tier 1 123 + 5 skipped, Tier 2 128/128,
  byte-compile clean; `ui` — `svelte-check` clean over the
  `ProductionReference.unit` addition.
