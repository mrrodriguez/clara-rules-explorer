(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.render
  "Rendering the persisted LHS code fields back into the Clojure data they name.

  A persisted production's `:lhs` stores three kinds of string, each for a
  reason that holds on disk:

    `:constraints` / `:args`  source text of a vector of forms, written by
                              `clara.explorer.serialize/serialize-condition`
    `:accumulator :form`      source text of one form, same writer
    `:type`                   the kind-explicit type name produced by
                              `clara.explorer.serialize/resolve-type`

  Text views render these back to data so a person (or agent) reads forms
  instead of quoted, escaped strings.  The persisted files and every `--edn`
  output keep the strings unchanged; a rendered value makes no EDN promise — it
  can hold regex literals and tagged literals that `clojure.edn` rejects."
  (:require
   [clojure.edn :as edn]))

(set! *warn-on-reflection* true)

(defn- read-one-form
  "Read exactly one form from string `s` with `read-fn`, a fn of one
  `java.io.PushbackReader` that returns `::eof` at end of input.  Returns the
  form when the whole string is one form; `::not-read` otherwise (no form,
  trailing input, or a throw)."
  [read-fn ^String s]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. s))
          form (read-fn rdr)]
      (if (and (not= ::eof form)
               (= ::eof (read-fn rdr)))
        form
        ::not-read))
    (catch Exception _ ::not-read)))

(defn read-code-text
  "Reads a persisted code field — `:constraints`, `:args`, or an `:accumulator`
  `:form` — from source text into the Clojure data it names.  Uses the Clojure
  reader, not `clojure.edn`, because the text is source and can hold regex
  literals and `#'var` forms; `*read-eval*` is bound to false so a `#=` form
  throws instead of evaluating, and `*default-data-reader-fn*` is bound to
  `tagged-literal` so an unknown tag reads as data instead of throwing.
  Requires the whole input to be one form.  Returns the string unchanged when
  it does not read as one form (or is not a string at all)."
  [s]
  (if-not (string? s)
    s
    (let [form (binding [*read-eval* false
                         *default-data-reader-fn* tagged-literal]
                 (read-one-form #(read % false ::eof) s))]
      (if (= ::not-read form) s form))))

(defn decode-type-name
  "Decodes a persisted type name into the value
  `clara.explorer.serialize/resolve-type` encoded: a keyword with its colon, a
  string, a tuple/map via `pr-str`, or a class / var-resolved symbol as a bare
  symbol.  Reads with `clojure.edn` and requires the whole input.  A name that
  does not decode — an unresolved `symbol[...]`, an array class name — becomes
  `(tagged-literal 'clara.explorer/type-name name)`, a tagged literal rather
  than a plain string, so it cannot be mistaken for a string fact type that
  happens to carry the same text."
  [name]
  (if-not (string? name)
    name
    (let [v (read-one-form #(edn/read {:eof ::eof} %) name)]
      (if (= ::not-read v)
        (tagged-literal 'clara.explorer/type-name name)
        v))))

(defn- display-accumulator
  "One accumulator value as data: its `:form` is the code field; a non-map
  accumulator is a whole form written as source text."
  [acc]
  (if (map? acc)
    (cond-> acc
      (contains? acc :form) (update :form read-code-text))
    (read-code-text acc)))

(defn- display-condition
  "One condition node with its code fields read back: `:type` decoded,
  `:constraints` / `:args` / `:accumulator` read, and `:children` / `:from`
  recursed.  Every other key (`:bindings`, `:condition-type`, `:fact-binding`,
  `:result-binding`) is left as it is."
  [node]
  (cond-> node
    (some? (:type node)) (update :type decode-type-name)
    (contains? node :constraints) (update :constraints read-code-text)
    (contains? node :args) (update :args read-code-text)
    (contains? node :accumulator) (update :accumulator display-accumulator)
    (contains? node :from) (update :from display-condition)
    (contains? node :children) (update :children #(mapv display-condition %))))

(defn ->display-lhs
  "A persisted `:lhs` as display data: every condition node — leaves, `:children`
  of groups, `:from` of accumulators, at any depth — has its `:type` decoded and
  its code fields read back.  A pure function of the persisted value, so it
  inherits that value's determinism within one explorer version."
  [lhs]
  (mapv display-condition lhs))
