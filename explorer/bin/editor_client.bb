#!/usr/bin/env bb
;; The babashka analogue of `clara.explorer.server.client/navigate`: read a registry selection of
;; persisted artifact units, compose them into one slim analysis, rehydrate it, and answer an EDN
;; navigate query.
;;
;;   bb bin/editor_client.bb '<selection-edn>' '<navigate-input-edn>'
;;   bb bin/editor_client.bb --list-units '<registry-root>'
;;
;; `--list-units` prints the unit keys (`repo`, or `repo@<variant path>` for a variant) of every
;; unit under the root, sorted — the registry discovery the editors used to do themselves, so the
;; registry layout has one owner here.
;;
;; <selection-edn> is a registry selection `{:root "…" :units [{:repo "…"}]}` — the same shape the
;; server's `:registry` mode takes; the editor resolves the `CLARA_RULES_EXPLORER_REGISTRY` root
;; itself and passes it explicitly. <navigate-input-edn> is a
;; `clara.explorer.server.schema/NavigateInput` map, e.g. `{:production "ns/rule" :side
;; :lhs :token "com.example.Loan"}`.
;;
;; A single unit and a multi-unit selection take the same path:
;; `clara.explorer.artifacts.shared.selection/->selection` →
;; `clara.explorer.artifacts.shared.compose/->composed-analysis` →
;; `clara.explorer.artifacts.shared.rehydrate/rehydrate-analysis` →
;; `clara.explorer.server.navigate/navigate`. This mirrors the server's `:registry` mode, so the
;; bb answer and the nREPL answer over the same selection agree.
;;
;; The editor resolves aliased/:: tokens to fq over its repl before calling, so this script assumes
;; fq-in and does only pure normalization + callsite string matching. Source locations are always
;; `:var? false` — bb loads no rule namespaces.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file (str (fs/file (fs/parent (fs/canonicalize *file*)) "bootstrap.bb")))

(require '[clara.explorer.artifacts.layout :as layout]
         '[clara.explorer.artifacts.shared.compose :as shared-compose]
         '[clara.explorer.artifacts.shared.rehydrate :as shared-rehydrate]
         '[clara.explorer.artifacts.shared.registry :as shared-registry]
         '[clara.explorer.server.navigate :as navigate]
         '[clara.explorer.server.tokens :as tokens])

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(defn- print-error [e]
  (prn {:error (or (.getMessage e) (str e))}))

(defn- ->unit-dir
  "The persistence dir of one unit, mirroring `clara.explorer.artifacts.store/get-out-dir`:
  `<:root>/<:repo>/`, or `<:root>/_variants/<:repo>/<variant path>` for a variant."
  [root {:keys [repo variant]}]
  (layout/->unit-dir {:root (str root) :repo repo :variant variant}))

(defn- ->unit-key
  "A unit's registry-relative path segments → its unit-key string
  (`repo[@variant path]`), or nil for a path that does not parse as a unit.
  The split is `layout/segments->unit-ref` — the same function the JVM
  `clara.explorer.artifacts.registry` walk uses — so the two runtimes cannot
  drift on how `_variants/` is read."
  [segments]
  (some-> (layout/segments->unit-ref segments) shared-registry/unit-key))

(defn- list-unit-repos
  "Every unit under `root`, as unit-key strings (`repo` or `repo@variant path`), sorted. Discovery is the
  directory walk the editors previously did themselves: find every
  `rules-inspect-manifest.edn` and name the unit by its directory relative to the root. The
  registry split has one owner (`layout/segments->unit-ref`, shared with the JVM walk); this
  script supplies only the babashka directory walk, and the editors only prompt over the
  returned list."
  [root]
  (let [root-file (fs/canonicalize root)]
    (when-not (fs/directory? root-file)
      (throw (ex-info (format "Registry root is not a directory: %s" root) {:root root})))
    (->> (fs/glob root-file "**/rules-inspect-manifest.edn")
         (keep (fn [manifest]
                 (let [rel (fs/unixify (fs/relativize root-file (fs/parent manifest)))]
                   (->unit-key (str/split rel #"/")))))
         sort
         vec)))

(defn- read-part-or-nil
  "One part of the unit's split `merged-rulebase-analysis/` directory, or nil when the part is
  absent — a missing part is an absent artifact, not an error, the same posture the JVM store
  takes."
  [dir part-key]
  (let [f (fs/file dir (:rulebase-analysis layout/artifact-files) (layout/part-files part-key))]
    (when (fs/exists? f)
      (edn/read-string {:default (fn [_tag v] v)} (slurp f)))))

(defn- read-slim-analysis
  "The unit's slim analysis re-joined from the parts navigation and composition read: the scan
  index (`:rules` / `:queries` projections), `:fact-types`, `:dep-graph`, and the `:meta` block
  (`:slim` / `:unresolved`). Nil when the unit has no analysis to read."
  [root unit]
  (let [dir (->unit-dir root unit)
        index (read-part-or-nil dir :index)
        meta (read-part-or-nil dir :meta)]
    (when (and index meta)
      {:rules (:rules index)
       :queries (:queries index)
       :fact-types (read-part-or-nil dir :fact-types)
       :dep-graph (read-part-or-nil dir :dep-graph)
       :unresolved (:unresolved meta)
       :slim (:slim meta)})))

(defn- unit-slim-dropped
  "A unit's slim `:dropped` shape, or nil when the unit has no analysis to merge (no `:slim`
  block). The one vocabulary the compatibility check compares across units."
  [root unit]
  (let [meta (read-part-or-nil (->unit-dir root unit) :meta)]
    (when (some? (:slim meta))
      (set (get-in meta [:slim :dropped])))))

(defn- assert-compatible!
  "Throws when `selection` is not mergeable — some units have no analysis to merge, or the analyzed
  units do not share one slim shape. The bb twin of
  `clara.explorer.artifacts.registry/assert-compatible!`, over the parts on disk."
  [root selection]
  (let [dropped (mapv #(unit-slim-dropped root %) selection)
        no-analysis (->> (map vector selection dropped)
                         (keep (fn [[unit d]] (when (nil? d) unit)))
                         vec)]
    (when (seq no-analysis)
      (throw (ex-info (format "%d unit(s) have no merged-rulebase-analysis to merge: %s"
                              (count no-analysis)
                              (pr-str (mapv shared-registry/unit-key no-analysis)))
                      {:no-analysis no-analysis})))
    (when-not (apply = dropped)
      (throw (ex-info (format "Cannot merge registry units with differing slim shapes: %s"
                              (pr-str (mapv shared-registry/unit-key selection)))
                      {:dropped (into {} (map (fn [unit d]
                                                [(shared-registry/unit-key unit) d])
                                              selection dropped))})))))

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
   :production-source (fn [_fq-name] {:var? false})})

(defn- run [selection input]
  (let [{:keys [root units]} selection]
    (when-not (and root (seq units))
      (throw (ex-info "editor_client.bb needs :root and a non-empty :units selection"
                      {:selection selection})))
    (let [caps {:read-analysis #(read-slim-analysis root %)
                :assert-compatible! #(assert-compatible! root %)}
          analysis (shared-compose/->composed-analysis caps units)
          rehydrated (shared-rehydrate/rehydrate-analysis analysis)]
      (navigate/navigate rehydrated bb-runtime input))))

(let [[cmd arg1] *command-line-args*]
  (cond
    ;; Registry discovery: the editors shell out here rather than walking the tree themselves.
    (= cmd "--list-units")
    (do
      (when-not arg1
        (die "usage: bb editor_client.bb --list-units '<registry-root>'"))
      (try
        (prn (list-unit-repos arg1))
        (catch Throwable e
          (print-error e))))

    :else
    (let [selection-edn cmd
          input-edn arg1]
      (when-not (and selection-edn input-edn)
        (die "usage: bb editor_client.bb '<selection-edn>' '<navigate-input-edn>'"))
      (let [selection (try (edn/read-string selection-edn)
                           (catch Throwable e
                             (die "Could not read selection-edn:" (.getMessage e))))
            input (try (edn/read-string input-edn)
                       (catch Throwable e
                         (die "Could not read navigate-input-edn:" (.getMessage e))))]
        (try
          (prn (run selection input))
          (catch Throwable e
            (print-error e)))))))
