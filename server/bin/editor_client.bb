#!/usr/bin/env bb
;; The babashka analogue of `clara.server.graph.client/navigate`: read a registry selection of
;; persisted artifact units, rehydrate the slim analysis, and answer an EDN navigate query.
;;
;;   bb bin/editor_client.bb '<selection-edn>' '<navigate-input-edn>'
;;
;; <selection-edn> is a registry selection `{:root "…" :units [{:repo "…"}]}` (the same shape the
;; server's `:registry` mode takes); the editor resolves the `CLARA_RULES_EXPLORER_REGISTRY` root
;; itself and passes it explicitly. <navigate-input-edn> is a
;; `clara.server.tools.graph.shared.schema/NavigateInput` map, e.g. `{:production "ns/rule" :side
;; :lhs :token "com.example.Loan"}`.
;;
;; Single-unit selections are the current milestone; multi-unit composition (`shared.selection` /
;; `shared.compose`) lands after the editor transport.
;;
;; The editor resolves aliased/:: tokens to fq over its repl before calling, so this script assumes
;; fq-in and does only pure normalization + callsite string matching. Source locations are always
;; `:var? false` — bb loads no rule namespaces.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file (str (fs/file (fs/parent (fs/canonicalize *file*)) "bootstrap.bb")))

(require '[clara.server.tools.graph.artifacts.layout :as layout]
         '[clara.server.tools.graph.shared.navigate :as shared-navigate]
         '[clara.server.tools.graph.shared.rehydrate :as shared-rehydrate]
         '[clara.server.tools.graph.shared.tokens :as tokens])

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(defn- unit-dir
  "The persistence dir of one unit, mirroring `clara.server.tools.graph.artifacts.store/get-out-dir`:
  `<:root>/<:repo>/`, with `:branch` nested under `<repo>/branches/<branch>/`."
  [root {:keys [repo branch]}]
  (let [base (fs/file root repo)]
    (if (str/blank? branch)
      base
      (fs/file base "branches" branch))))

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

(defn- run [selection input]
  (let [{:keys [root units]} selection]
    (when-not (and root (= 1 (count units)))
      (throw (ex-info "editor_client.bb supports a single-unit selection for now"
                      {:selection selection})))
    (let [dir (unit-dir root (first units))
          index (read-part dir :index)
          fact-types (read-part dir :fact-types)
          dep-graph (read-part dir :dep-graph)
          meta (read-part dir :meta)
          analysis {:rules (:rules index)
                    :queries (:queries index)
                    :fact-types fact-types
                    :dep-graph dep-graph
                    :slim (:slim meta)}
          rehydrated (shared-rehydrate/rehydrate-analysis analysis)]
      (shared-navigate/navigate rehydrated bb-runtime input))))

(let [[selection-edn input-edn] *command-line-args*]
  (when-not (and selection-edn input-edn)
    (die "usage: bb editor_client.bb '<selection-edn>' '<navigate-input-edn>'"))
  (let [selection (try (edn/read-string selection-edn)
                       (catch Throwable e
                         (die "Could not read selection-edn:" (.getMessage e))))
        input (try (edn/read-string input-edn)
                   (catch Throwable e
                     (die "Could not read navigate-input-edn:" (.getMessage e))))]
    (try
      (println (pr-str (run selection input)))
      (catch Throwable e
        (println (pr-str {:error (or (.getMessage e) (str e))}))))))
