# Progress: Render `:lhs` as Clojure data in the report's text views

Tracks work against
[`render-lhs-in-report-text-plan.md`](./render-lhs-in-report-text-plan.md).

## Status

Complete — all changes implemented and verified.

## Open questions resolved

- **Tag name (§8):** kept `clara.explorer/type-name`, the plan's first
  suggestion — a project-namespaced tag can never collide with a fact-type
  name, and it reads as a decode-of-a-type-name rather than a generic
  placeholder.
- **Narrowing the diff to changed conditions (§8):** left out, as the plan
  pre-approved — `rule-detail-text` prints the whole `:lhs` per side; matching
  only changed conditions by position misreports every condition after an
  insertion, and no cheap, correct match exists.

## Decisions

- **Reader bindings (§3.1):** used `binding` of `*read-eval*` → false and
  `*default-data-reader-fn*` → `tagged-literal` rather than an opts map, since
  the Clojure reader resolves both from those dynamic vars (confirmed on both
  the JVM and babashka). Reading goes through `clojure.core/read` on a
  `PushbackReader` with an `::eof` sentinel, read twice — once for the form,
  once to require the whole input is consumed.
- **Trailing/whole-input check:** a shared private `read-one-form` takes a
  `(fn [^PushbackReader rdr])` and returns `::not-read` on a non-string, an
  empty read, trailing input, or a throw. `read-code-text` and
  `decode-type-name` differ only in which reader fn they hand it.
- **`decode-type-name` non-string guard:** a non-string `:type` passes through
  unchanged, so `->display-lhs` never throws on data that is already decoded.
- **Accumulator non-map branch:** `display-accumulator` reads the whole value
  as code text when the accumulator is not a map, mirroring
  `clara.explorer.serialize/serialize-condition`'s `serialize-accumulator`
  branch for a bare form. The map branch reads only `:form`.
- **`rule-detail-text` layout (§4.1):** `:lhs` and `:rhs-form` print as
  indented multi-line blocks (a value more than one line long); every other
  field stays inline with `pr-str` exactly as today. `:lhs` is the only field
  whose value is rendered — `:rhs-form` prints the already-pretty-printed text
  raw, no longer `pr-str`-quoted.

## TODO / remaining

- [x] `clara.explorer.artifacts.render`: `read-code-text`, `decode-type-name`,
      `->display-lhs`, bb-loadable.
- [x] `annotations_report.bb`: `print-production` and `print-production-part`
      render `:lhs`; `production-report` combines `--part` and `--edn`; help
      text updated.
- [x] `clara.explorer.artifacts.diff/rule-detail-text`: `:lhs` and `:rhs-form`
      as blocks.
- [x] `explorer/docs/persisted-artifacts.md`: text-view rendering + `--edn`
      verbatim stated in the offline-query section.
- [x] `render_test.clj`, `bb_report_test.clj`, `diff_test.clj` updated/added.
- [x] `make test lint format-check reflection-check bb-smoke-test`.

## Log

- Read the plan, the report script, `diff.clj`, `serialize.clj`, the artifact
  docs, the Makefile, and both test files.
- Confirmed reader behavior on the JVM and babashka: regex literal → `Pattern`,
  `"[\n]"` → `[]`, `#=` throws under `*read-eval*` false, unknown tag → tagged
  literal, `:eof` sentinel lets the whole-input check work with
  `clojure.core/read` and `clojure.edn/read`.
- Inspected the persisted `production-conditions.edn` to pin the real
  condition shapes: `:type` is a bare `resolve-type` name string, `:children`
  holds `:not` groups, `:from` subtrees carry `:type`/`:constraints` with no
  `:bindings`, and a `:test` leaf carries no `:type` (the type is derived).
- Wrote `render.clj` and its unit tests.
- Wired the renderer into `annotations_report.bb` and `diff.clj`, updated the
  docs, and updated the two test files. A stray delimiter in the bb test edit
  was fixed with `clj-paren-repair`.
- Manual smoke: `production` on `app-outcome-denied?` (a `:not` group) and
  `doc-check-count` (an accumulator) render `:type`/`:constraints`/`:form` as
  data; `--part lhs --edn` shows the persisted strings verbatim; `--part rhs`
  vs `--part rhs --edn` differ as specified; `help` shows `--part`/`--edn`
  combine.
