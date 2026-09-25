(ns clara.server.tools.graph.shared.tokens
  "Pure token helpers shared by the JVM editor client and the babashka editor
   client: kind-explicit name checks and authored-callsite string matching.

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
