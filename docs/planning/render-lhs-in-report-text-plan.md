# Render `:lhs` as Clojure data in the report's text views

Status: **proposal**. Scope: the text output of `explorer/bin/annotations_report.bb`
`production` (full record and `--part`) and `diff --production`, plus one new
bb-loadable namespace both use. The persisted artifacts, the HTTP API, the UI,
and every `--edn` output keep their current shape.

## 1. Problem

A persisted production's `:lhs` is the same value the HTTP API serves. Its
leaves carry three kinds of string, each for a reason that holds on disk:

| Field | Persisted as | Why it is a string |
|---|---|---|
| `:constraints`, `:args` | source text of a vector of forms, `"[\n…]"` | forms can hold regex literals and other values EDN cannot read back; the UI renders the text as a code block |
| `:accumulator :form` | source text of one form | same |
| `:type` | the kind-explicit type name (`clara.explorer.serialize/resolve-type`) | the name is the key every artifact file uses for the type |

The text views `pprint` the condition maps as they are, so every one of those
strings prints quoted and escaped, beside `:bindings` and `:condition-type`
values that print as data:

```clojure
{:type ":order/line",
 :constraints
 "[\n(= status ?status)\n\n(> qty 10)\n\n(re-find #\"^A-\\d+\" sku)\n]",
 :args "[\n{:keys [qty status sku]}\n]",
 :bindings {:binding-keys [], :new-bindings [:?status]}}
```

- An empty constraint list prints as `"[\n]"`.
- A string fact type prints as `:type "\"line\""`.
- A regex prints with doubled escapes, `#\"^A-\\d+\"`, which the reader has to
  unescape by eye to see the source's `#"^A-\d+"`.

`diff --production` is worse. `clara.explorer.artifacts.diff/rule-detail-text`
prints each changed field with `pr-str`, so a changed `:lhs` is the whole
vector on one line per side:

```
shop.rules/big-order [lhs]
lhs:
  before: [{:type ":order/line", :args "[\n{:keys [qty status]}\n]", :constraints "[\n(= status ?status)\n\n(> qty 10)\n\n(re-find #\"^A-\\d+\" sku)\n]", :bindings {:binding-keys [], :new-bindings [:?status]}}]
  after:  [{:type ":order/line", :args "[\n{:keys [qty status]}\n]", :constraints "[\n(= status ?status)\n\n(>= qty 10)\n\n(re-find #\"^A-\\d+\" sku)\n]", :bindings {:binding-keys [], :new-bindings [:?status]}}]
```

The change (`>` to `>=`) is one character, buried in two lines of escaped
text. A changed `:rhs-form` goes through the same `pr-str`, so it prints as
one escaped line too.

## 2. Decision

- **The persisted format stays as it is.** Source text is the representation of
  a form that always survives EDN. The type name is an identifier, not a
  rendering.
- **Text views render, `--edn` is verbatim.** A text view turns each string back
  into the Clojure data it names and pretty-prints the result. `--edn` prints
  the persisted value unchanged, and is the machine-readable path.
- **Rendered output makes no EDN promise.** It can contain regex literals and
  tagged literals, which `clojure.edn` rejects. That is fine for a view a person
  or an agent reads. A program reads `--edn`.
- **Rendering is display only.** `diff` keeps comparing persisted values. The
  rendered text is a pure function of those values, so it inherits their
  determinism within one explorer version.

Example rendering of the condition in §1:

```clojure
{:type :order/line,
 :constraints [(= status ?status) (> qty 10) (re-find #"^A-\d+" sku)],
 :args [{:keys [qty status sku]}],
 :bindings {:binding-keys [], :new-bindings [:?status]}}
```

## 3. Rendering rules

One new namespace, `clara.explorer.artifacts.render`. It is loadable under
babashka (no JVM-only requires), so both the report script and
`clara.explorer.artifacts.diff` use it. It has three public fns.

### 3.1 `read-code-text`: code fields to forms

Applies to `:constraints`, `:args`, and `:accumulator :form`.

- Read with the Clojure reader (`read-string`), not `clojure.edn`. The text is
  source, and the Clojure reader is what reads regex literals and `#'var`.
- Bind `*read-eval*` to `false`, so `#=` throws instead of evaluating.
- Bind `*default-data-reader-fn*` to `tagged-literal`, so an unknown tag reads
  as data instead of throwing.
- Require that the whole input is consumed. Trailing text means the string is not
  one form.
- On any read failure, return the string unchanged. A code field that renders
  as a string is then visibly unrendered, since a successful read is always a
  vector (constraints, args) or a form (accumulator).

### 3.2 `decode-type-name`: type names to values

Read the name with `clojure.edn`, and require that the whole input is consumed.
The reader does the validation. Nothing builds a value from arbitrary text
without checking it.

| Name kind (`resolve-type`) | Example name | Decoded value | Prints as |
|---|---|---|---|
| keyword | `":order/line"` | keyword | `:order/line` |
| string | `"\"line\""` | string | `"line"` |
| tuple, map | `"[:order/line :v2]"` | vector, map | `[:order/line :v2]` |
| class, var-resolved symbol | `"shop.model.Line"` | symbol | `shop.model.Line` |
| unresolved symbol | `"symbol[line]"` | not decodable | see below |
| anything else not decodable | e.g. an array class name | not decodable | see below |

A name that does not decode becomes
`(tagged-literal 'clara.explorer/type-name name)`, which prints as
`#clara.explorer/type-name "symbol[line]"`. A plain string is not enough here,
because a string is also what a decoded string type looks like. Without the tag,
an unresolved `symbol[line]` and a string type named `symbol[line]` would print
the same.

### 3.3 `->display-lhs`: walk a persisted `:lhs`

Walk every condition node: leaves, `:children` of groups, `:from` of
accumulators, at any depth. On each node, apply `decode-type-name` to `:type`
and `read-code-text` to the code fields. Leave every other key as it is
(`:bindings`, `:condition-type`, `:fact-binding`, `:result-binding`).

## 4. Views

| Invocation | Output |
|---|---|
| `production NAME` | `lhs:` prints `->display-lhs` conditions, one `pprint` per condition. The header lines and the raw `:rhs-form` text stay as they are |
| `production NAME --part lhs` | `->display-lhs`, pretty-printed |
| `production NAME --part lhs --edn` | the persisted `:lhs`, pretty-printed |
| `production NAME --part rhs` / `props` | as today |
| `production NAME --part rhs\|props --edn` | the persisted value as EDN (`:rhs-form` as a quoted string) |
| `production NAME --edn` | the whole persisted record, as today |
| `diff A B --production NAME` | see §4.1 |
| `diff A B --production NAME --edn` | as today |

`--part` picks the field and `--edn` picks the format, independently. Today
`--part` takes precedence and `--edn` is silently ignored beside it. `--part lhs`
renders because it is the narrowed form of the `lhs:` section, and narrowing
should not change the format. The verbatim slice is one flag away.

### 4.1 `diff --production` text

`rule-detail-text` prints each changed field's two sides as multi-line blocks:

- `:lhs`: `->display-lhs`, pretty-printed.
- `:rhs-form`: the text as is.
- Every other field: `pr-str`, as today.

```
shop.rules/big-order [lhs]
lhs:
  before:
    [{:type :order/line,
      :args [{:keys [qty status]}],
      :constraints [(= status ?status) (> qty 10) (re-find #"^A-\d+" sku)],
      :bindings {:binding-keys [], :new-bindings [:?status]}}]
  after:
    [{:type :order/line,
      :args [{:keys [qty status]}],
      :constraints [(= status ?status) (>= qty 10) (re-find #"^A-\d+" sku)],
      :bindings {:binding-keys [], :new-bindings [:?status]}}]
```

## 5. Changes

1. **New `clara.explorer.artifacts.render`** with `read-code-text`,
   `decode-type-name`, and `->display-lhs`, with docstrings that name
   `clara.explorer.serialize/resolve-type` as the encoding it decodes.
2. **`annotations_report.bb`**:
   - `print-production` and `print-production-part` render `:lhs`.
   - `production-report` makes `--edn` combine with `--part`.
   - The `help` text for `production` says `--part` and `--edn` combine.
3. **`clara.explorer.artifacts.diff/rule-detail-text`** renders as in §4.1.
4. **`explorer/docs/persisted-artifacts.md`**: the offline-query section states
   that text views render `:lhs` as Clojure data, and that `--edn` (with or
   without `--part`) prints the persisted value.

## 6. Tests

- **`render` unit tests**:
  - `read-code-text`: a regex constraint round-trips to a `Pattern` with the
    source's escapes; `"[\n]"` reads as `[]`; `#=` throws inside and returns
    the string; unreadable text and trailing text return the string; an
    unknown tag reads as a tagged literal.
  - `decode-type-name`: one case per row of the §3.2 table. An unresolved
    symbol and a string type with the same text print differently.
  - `->display-lhs`: a `:not` group with leaf children, an accumulator with
    `:from`, a `:test` condition (no `:type`), and an empty `:lhs`.
- **`bb_report_test`**: the `production` text view, `--part lhs`,
  `--part lhs --edn` (equal to the persisted value), and `--part rhs --edn`.
- **`diff_test`**: `rule-detail-text` over a production whose `:lhs` changed
  in one constraint, and one whose `:rhs-form` changed.

## 7. Verification

- `cd explorer && make test lint format-check reflection-check bb-smoke-test`.
- Manual: `production` on a rule with a `:not` group, one with an accumulator,
  and one with a regex constraint. `--part lhs --edn` output read back with
  `clojure.edn/read-string` equals the persisted `:lhs`. One `diff --production`
  on a rule with a changed constraint.

## 8. Open questions

- **The tag name** for undecodable type names: `clara.explorer/type-name`, or a
  shorter one.
- **Narrowing the diff to changed conditions.** §4.1 prints the whole `:lhs` on
  each side. Printing only the conditions that differ is smaller, but matching
  conditions by position misreports every condition after an inserted one.
  Leave that out of this change unless a cheap, correct match exists.
