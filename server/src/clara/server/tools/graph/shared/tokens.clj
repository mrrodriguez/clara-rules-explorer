(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.tokens
  "Pure token helpers shared by the JVM editor client and the babashka editor client: kind-explicit
  name checks, constructor-syntax normalization, the editor resolve-form builder, and
  authored-callsite string matching.

   Live resolution (`ns-resolve`, record-constructor class-loading) is NOT here: each runtime
  resolves aliased and bare symbols its own way and calls these helpers with the resolved strings."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn real-type-name?
  "True when a resolved name is a genuine type name (not the unresolved-symbol sentinel, and not
  nil)."
  [name]
  (and (some? name)
       (not (str/starts-with? name "symbol["))))

(defn callsite-matches-token?
  "True when a serialized callsite entry's `:constructor-sym` / `:fact-type` / `:fact-type-spec`
  matches the fully-qualified token symbol (a string)."
  [fq-sym-str callsite]
  (or (= fq-sym-str (:constructor-sym callsite))
      (= fq-sym-str (get-in callsite [:fact-type :name]))
      (some #(= fq-sym-str %) (vals (:fact-type-spec callsite)))))

(defn normalize-ctor-target
  "If symbol token form uses Java constructor syntax, the class symbol it denotes: `X.` → `X`,
  `X/new` → `X`. Otherwise nil — record constructors (`->X`, `map->X`) and plain symbols are for
  `ns-resolve` to settle. The syntax half of `clara.server.graph.client/resolve-ctor-token`, minus
  the classpath half."
  [form]
  (let [n (name form)
        ns-part (namespace form)]
    (cond
      (str/ends-with? n ".")
      (if ns-part
        (symbol ns-part (subs n 0 (dec (count n))))
        (symbol (subs n 0 (dec (count n)))))

      (and (= n "new") ns-part)
      (symbol ns-part)

      :else nil)))

(defn record-ctor-class-symbol
  "If `form` is a fully-qualified record-constructor symbol (`ns/->X` or `ns/map->X`), the fq class
  symbol it denotes (`ns_with_underscores.X`) — the pure syntactic half of
  `clara.server.tools.graph.analyze.ctor/resolve-record-type`, minus the class-load check. The bb
  client uses this where the JVM class-loads. Otherwise nil."
  [form]
  (let [n (name form)
        ns-part (namespace form)]
    (when (and ns-part
               (or (str/starts-with? n "->")
                   (str/starts-with? n "map->")))
      (let [class-name (if (str/starts-with? n "map->")
                         (subs n 5)
                         (subs n 2))]
        (symbol (str (str/replace ns-part "-" "_") "." class-name))))))

(defn editor-token-resolve-form
  "Builds the editor resolve form (as a string) for CALLER-NS and TOKEN by filling the canonical
  resource template's slots with string literals, so arbitrary token text cannot break out of the
  form. Evaluating the result over a repl yields an fq class/var name for resolvable symbols, the
  `str` of keywords, the raw token text for any other readable form (the client normalizes those),
  and nil when the token is unreadable — the editor falls back to the raw token then."
  [caller-ns token]
  (format (slurp (io/resource "clara/server/tools/graph/shared/editor-resolve-form.clj"))
          (pr-str (str caller-ns))
          (pr-str (str token))))
