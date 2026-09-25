(ns clara.server.tools.graph.shared.tokens
  "Pure token helpers shared by the JVM editor client and the babashka editor
   client: kind-explicit name checks, constructor-syntax normalization, the
   editor resolve-form builder, and authored-callsite string matching.

   Discipline: this namespace stays dependency-free — only `clojure.*`
   requires — so both runtimes load it (the JVM requires it normally; bb
   requires it with no classpath beyond `src`). Live resolution (`ns-resolve`,
   record-constructor class-loading) is NOT here: each runtime resolves
   aliased and bare symbols its own way and calls these helpers with the
   resolved strings."
  (:require [clojure.string :as str]))

(defn real-type-name?
  "True when a resolved name is a genuine type name (not the unresolved-symbol
   sentinel, and not nil)."
  [name]
  (and (some? name)
       (not (str/starts-with? name "symbol["))))

(defn callsite-matches-token?
  "True when a serialized callsite entry's `:constructor-sym` / `:fact-type` /
   `:fact-type-spec` matches the fully-qualified token symbol (a string)."
  [fq-sym-str callsite]
  (or (= fq-sym-str (:constructor-sym callsite))
      (= fq-sym-str (get-in callsite [:fact-type :name]))
      (some #(= fq-sym-str %) (vals (:fact-type-spec callsite)))))

(defn normalize-ctor-target
  "If symbol token form uses Java constructor syntax, the class symbol it
   denotes: `X.` → `X`, `X/new` → `X`. Otherwise nil — record constructors
   (`->X`, `map->X`) and plain symbols are for `ns-resolve` to settle.
   The syntax half of `clara.server.graph.client/resolve-ctor-token`, minus
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

(def ^:private editor-resolve-form-template
  "Quoted template for the self-contained resolve form the editors eval over
   the user's repl (clojure.core plus `String` interop only, so it evals on a
   plain project repl with no explorer dependency). `__CALLER_NS__` and
   `__TOKEN__` mark the fill-in slots. Quoted data, not a string, so the
   reader checks the parens on every load in both runtimes; the builder
   prints and fills it. The Emacs/Neovim transports hold formatted copies of
   the printed text; keep them in sync."
  '(let [ns-sym (symbol __CALLER_NS__)
         the-ns (find-ns ns-sym)
         form (binding [*read-eval* false *ns* (or the-ns *ns*)]
                (try (read-string __TOKEN__) (catch Exception _ nil)))]
     (cond
       (symbol? form)
       (let [n (name form)
             ns-part (namespace form)
             target (cond (String/.endsWith n ".")
                          (let [s (subs n 0 (dec (count n)))]
                            (if ns-part (symbol ns-part s) (symbol s)))
                          (and (= n "new") ns-part)
                          (symbol ns-part)
                          :else form)
             v (when the-ns
                 (try (ns-resolve the-ns target) (catch Exception _ nil)))]
         (cond (class? v) (Class/.getName v)
               (var? v) (str (symbol (str (ns-name (:ns (meta v)))) (name target)))
               :else (str form)))
       (keyword? form) (str form)
       (nil? form) nil
       :else __TOKEN__)))

(defn editor-token-resolve-form
  "Builds the editor resolve form (as a string) for CALLER-NS and TOKEN by
   printing `editor-resolve-form-template` and filling the slots with string
   literals, so arbitrary token text cannot break out of the form. Evaluating
   the result over a repl yields an fq class/var name for resolvable symbols,
   the `str` of keywords, the raw token text for any other readable form (the
   client normalizes those), and nil when the token is unreadable — the editor
   falls back to the raw token then."
  [caller-ns token]
  (-> (pr-str editor-resolve-form-template)
      (str/replace "__CALLER_NS__" (pr-str (str caller-ns)))
      (str/replace "__TOKEN__" (pr-str (str token)))))
