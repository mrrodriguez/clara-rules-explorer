#!/usr/bin/env bb
;; The babashka twin of `clara.server.graph.client/navigate`, single-unit first: read one persisted
;; artifact unit, rehydrate its slim analysis, and answer an EDN navigate query.
;;
;;   bb bin/editor_client.bb <unit-dir> <navigate-input-edn>
;;
;; <unit-dir> is a persisted artifact set (the directory holding `merged-rulebase-analysis/`);
;; <navigate-input-edn> is a `clara.server.tools.graph.shared.schema/NavigateInput` map, e.g.
;; `{:production "ns/rule" :side :lhs :token "com.example.Loan"}`
;;
;; The editor resolves aliased/:: tokens to fq over its repl before calling, so this script assumes
;; fq-in and does only pure normalization + callsite string matching. Source locations are always
;; `:var? false` — bb loads no rule namespaces.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn])

(load-file (str (fs/file (fs/parent *file*) "bootstrap.bb")))

(require '[clara.server.tools.graph.artifacts.layout :as layout]
         '[clara.server.tools.graph.shared.navigate :as shared-navigate]
         '[clara.server.tools.graph.shared.rehydrate :as shared-rehydrate]
         '[clara.server.tools.graph.shared.tokens :as tokens])

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(defn- read-part
  "One part of the unit's split `merged-rulebase-analysis/` directory."
  [unit-dir part-key]
  (let [f (fs/file unit-dir (:rulebase-analysis layout/artifact-files) (layout/part-files part-key))]
    (when-not (fs/exists? f)
      (die "Missing analysis part:" (str f)))
    (edn/read-string {:default (fn [_tag v] v)} (slurp f))))

(defn- read-token
  "The raw token text as one Clojure form, or nil when unreadable. Tokens arrive
  already fq-resolved from the editor, so no caller-ns binding is needed."
  [token]
  (binding [*read-eval* false]
    (try (read-string token) (catch Throwable _ nil))))

(defn- bb-resolve-token
  "Pure, fq-assuming token resolution: keyword → `str`, string → `pr-str`, record
  ctor → its fq class name, anything else → its text. No `ns-resolve`."
  [_caller-ns-sym token]
  (let [form (read-token token)]
    (when form
      (cond
        (keyword? form) (str form)
        (string? form) (pr-str form)
        (symbol? form) (or (some-> form tokens/record-ctor-class-symbol str)
                           (str form))
        :else (str form)))))

(defn- bb-token->fq-sym
  "The fq symbol of an already-qualified symbol token, for callsite matching."
  [_caller-ns-sym token]
  (let [form (read-token token)]
    (when (and (symbol? form) (namespace form))
      form)))

(def ^:private bb-runtime
  {:resolve-token bb-resolve-token
   :token->fq-sym bb-token->fq-sym
   :production-source (fn [_fq-name] {:var? false :file nil :line nil :column nil})})

(defn- run [unit-dir input]
  (let [index (read-part unit-dir :index)
        fact-types (read-part unit-dir :fact-types)
        dep-graph (read-part unit-dir :dep-graph)
        meta (read-part unit-dir :meta)
        analysis {:rules (:rules index)
                  :queries (:queries index)
                  :fact-types fact-types
                  :dep-graph dep-graph
                  :slim (:slim meta)}
        rehydrated (shared-rehydrate/rehydrate-analysis analysis)]
    (shared-navigate/navigate rehydrated bb-runtime input)))

(let [[unit-dir input-edn] *command-line-args*]
  (when-not (and unit-dir input-edn)
    (die "usage: bb editor_client.bb <unit-dir> <navigate-input-edn>"))
  (let [input (try (edn/read-string input-edn)
                   (catch Throwable e
                     (die "Could not read navigate-input-edn:" (.getMessage e))))]
    (try
      (println (pr-str (run unit-dir input)))
      (catch Throwable e
        (println (pr-str {:error (or (.getMessage e) (str e))}))))))
