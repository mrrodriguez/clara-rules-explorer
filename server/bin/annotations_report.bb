#!/usr/bin/env bb
;; Triage the persisted clara-rules-explorer artifacts WITHOUT reading them into
;; an LLM context window. A layer file is ~150KB and the analysis
;; runs from ~10KB to 20MB (a restored 18-ruleset session) — never `cat` them.
;;
;; This is the ANNOTATION-side tool: layers, callsites, resolution, curation,
;; provenance. Seven of the nine subcommands never open the analysis at all.
;; For anything structural the analysis does not hold — the Rete graph, :lhs-form,
;; working memory — start the explorer server and query /v1.
;;
;;   bb annotations_report.bb <dir|file.edn> [subcommand [arg]] [--file auto|agent|merged]
;;
;; <dir> is one run's artifact directory, holding the two
;; annotation LAYERS plus merged-annotations.edn and merged-rulebase-analysis/.
;;
;; merged-rulebase-analysis/ is a DIRECTORY split by access pattern, so a scan
;; opens production-index.edn (~1.9MB) instead of the whole ~12MB value:
;;   production-index.edn       :name :ns :lhs-types :insert-types + flags
;;   production-conditions.edn  :lhs
;;   production-details.edn     :rhs-form :doc :props :notes :params
;;   fact-types.edn / dep-graph.edn / meta.edn
;; merged-annotations.edn is stored by REFERENCE to the layers: ~99% of rules
;; have a merged annotation identical to one layer's, so the file names that
;; layer instead of restating the value, and is ~78KB rather than 5.6MB. Only
;; rules the fold genuinely combined are written out. The references are resolved
;; for you, so every subcommand sees whole annotations with whole callsites —
;; :via and :source-str included.
;;
;; Envelopes are unwrapped for you: a layer file is {:id … :annotations {…}} and
;; the merge is {:verbatim … :annotations … :layers … :provenance …}; every
;; subcommand below works on the inner rule->annotation map either way.
;;
;; Subcommands:
;;   summary              (default) counts + resolution tallies
;;   gaps                 rules whose :resolution is not :full, with the
;;                        unresolved callsites to go read. Reads the AUTO layer —
;;                        the deterministic baseline is the real work list
;;   types                every resolved insert-type, with producer count
;;   producers <type>     rules inserting <type>            (annotations)
;;   consumers <type>     rules with <type> on their LHS    (production-index)
;;   rule <fq-name>       one rule's full annotation        (annotations)
;;   edges <fq-name>      dep-graph upstream/downstream     (dep-graph; downstream inverted)
;;   curated              what the agent overlay changed vs the auto-gen baseline
;;   layers               the fold: which layers contributed, and per-key
;;                        provenance (add a <fq-name> for one rule's)
;;
;; --file picks which annotations file the annotation-reading subcommands use:
;; auto (the generated layer), agent (the curated overlay alone), or merged (the
;; fold). Default is merged, except `gaps`, which defaults to auto.
;;
;; <type> may be written :foo/bar or foo/bar; substring matching kicks in when
;; there is no exact hit. <fq-name> likewise falls back to substring search.
;;
;; Callsite :status and dimension :resolution share one vocabulary:
;; :full / :partial / :none.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

;; Artifact filenames, the layer fold order, and the merged-annotations.edn
;; decode come from the JVM source, through the `layout.cljc` symlink
;; beside this script. Do not replace it with a copy: a copy still parses long
;; after it stops agreeing with what wrote the files.
(def ^:private layout-file
  (fs/file (fs/parent *file*) "layout.cljc"))

(when-not (fs/exists? layout-file)
  (binding [*out* *err*]
    (println "Missing layout.cljc beside this script:" (str layout-file))
    (println "It symlinks to src/clara/server/tools/graph/artifacts/layout.cljc — run this")
    (println "script from a checkout, not from a copy of the file alone."))
  (System/exit 1))

(load-file (str layout-file))
(alias 'layout 'clara.server.tools.graph.artifacts.layout)

(def ^:private dims
  [[:clara-rules/dynamic-insert-types-detected :clara-rules/insert-types "insert"]
   [:clara-rules/dynamic-retract-types-detected :clara-rules/retract-types "retract"]])

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(def ^:private artifact-files
  "The `--file` values, mapped to the filenames `layout/artifact-files` gives
  their roles. Only the three an annotation-reading subcommand may be pointed at."
  (into {} (map (fn [k] [(name k) (layout/artifact-files k)])) [:auto :agent :merged]))

(def ^:private analysis-dir-name
  "The analysis is a DIRECTORY split by access pattern — a scan opens
  production-index.edn (~1.9MB) rather than the whole ~11.9MB value."
  (:rulebase-analysis layout/artifact-files))

(def ^:private layer-filenames
  "The layer files, in FOLD order — lowest precedence first. merged-annotations.edn
  stores nearly every rule as a reference into one of these, so reading the merge
  means reading them too."
  (mapv layout/artifact-files (keys layout/layer-artifacts)))

(def ^:private resolved-statuses
  "Callsite statuses that carry a conclusion. `:partial` counts: it resolved
  something, just not everything."
  #{:full :partial})

(defn- resolve-paths
  "Locate the artifacts. `which` names the annotations file to read (a key of
  `artifact-files`); pointing `target` straight at an .edn overrides it."
  [target which]
  (let [filename (or (artifact-files which)
                     (die "Unknown --file:" which "- expected one of"
                          (str/join ", " (sort (keys artifact-files)))))]
    (letfn [(siblings [dir]
              {:auto (fs/file dir (artifact-files "auto"))
               :agent (fs/file dir (artifact-files "agent"))
               :merged (fs/file dir (artifact-files "merged"))
               :dir dir})]
      (cond
        (fs/directory? target)
        (assoc (siblings target) :annotations (fs/file target filename))

        (fs/regular-file? target)
        (assoc (siblings (fs/parent target)) :annotations (fs/file target))

        :else (die "No such dir or file:" (str target))))))

(defn- read-edn [f what]
  (when-not (fs/exists? f) (die "Missing" what "-" (str f)))
  (edn/read-string {:default (fn [_tag v] v)} (slurp (fs/file f))))

(defn- read-layer-stack
  "Every layer file in `dir` that exists, in fold order, as [layer-id annotations].
  What merged-annotations.edn's references resolve against."
  [dir]
  (into []
        (keep (fn [filename]
                (let [f (fs/file dir filename)]
                  (when (fs/exists? f)
                    (let [m (read-edn f filename)]
                      [(:id m) (:annotations m)])))))
        layer-filenames))

(defn- read-merged
  "merged-annotations.edn, expanded. The whole `{:annotations :layers
  :provenance}` value, as if it had been written out in full — which costs
  opening the layer files its references point at.

  `layout/expand-merged-annotations` is the same code `store/read-merged-annotations`
  runs, not a second implementation of it."
  [paths]
  (layout/expand-merged-annotations
   (read-edn (:merged paths) (artifact-files "merged"))
   (read-layer-stack (:dir paths))))

(defn- read-annotations
  "The rule->annotation map, whatever envelope it arrived in: a layer
  ({:id … :annotations …}), a compacted merge ({:verbatim … :annotations …}), or
  a bare map. Rule names are strings, so the keyword keys are unambiguous."
  [f what dir]
  (let [m (read-edn f what)]
    (cond
      (:verbatim m) (:annotations (layout/expand-merged-annotations m (read-layer-stack dir)))
      (map? (:annotations m)) (:annotations m)
      :else m)))

(defn- ->analysis-part
  "`(f part-key)` reading one part of the analysis. Only the part asked for is
  ever read, which is the point of the split."
  [dir]
  (fn [k]
    (let [filename (layout/part-files k)]
      (read-edn (fs/file dir analysis-dir-name filename) filename))))

(defn- trunc [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- norm-type
  "Accept :foo/bar, foo/bar, or \"foo/bar\" -> the bare string \"foo/bar\"."
  [s]
  (str/replace (str s) #"^:" ""))

(defn- type->str [t]
  (cond (keyword? t) (subs (str t) 1)
        (vector? t) (pr-str t)
        :else (str t)))

;; ---------------------------------------------------------------------------
;; summary
;; ---------------------------------------------------------------------------

(defn- summary [anns]
  (println "rules:" (count anns))
  (println "with :insert-types:"
           (count (filter #(seq (:clara-rules/insert-types %)) (vals anns))))
  (println "with :no-output-types:"
           (count (filter :clara-rules/no-output-types (vals anns))))
  (println)
  (doseq [[dyn-k _ label] dims]
    (let [detected (keep dyn-k (vals anns))]
      (when (seq detected)
        (println (str label " dimension — " (count detected) " rules with detections"))
        (println "  :resolution" (into (sorted-map) (frequencies (map :resolution detected))))
        (println "  callsite :status"
                 (into (sorted-map)
                       (frequencies (map #(get % :status :missing)
                                         (mapcat :callsites detected)))))
        (println "  constructors"
                 (into (sorted-map)
                       (frequencies (keep :constructor-sym (mapcat :callsites detected))))))))
  (println)
  (let [gaps (count (filter (fn [a] (some (fn [[dyn-k _ _]]
                                            (when-let [d (dyn-k a)]
                                              (not= :full (:resolution d))))
                                          dims))
                            (vals anns)))]
    (println "rules needing follow-up (:resolution not :full):" gaps
             "->  run `gaps`")))

;; ---------------------------------------------------------------------------
;; gaps
;; ---------------------------------------------------------------------------

(defn- gaps [anns]
  (let [hits (for [[rule a] (sort anns)
                   [dyn-k _ label] dims
                   :let [d (dyn-k a)]
                   :when (and d (not= :full (:resolution d)))]
               [rule label d])]
    (println (count hits) "dimension(s) needing follow-up\n")
    (doseq [[rule label d] hits]
      (println rule)
      (println (str "  " label " :resolution " (:resolution d)))
      (doseq [c (:callsites d)
              :when (not (contains? resolved-statuses (:status c)))]
        (println (str "    [" (:status c) "]"
                      (when (:dangling? c) " DANGLING — matched no discovered form")
                      " " (:filename c)))
        (println (str "      id: " (:callsite-id c)))
        (println (str "      " (trunc (:source-str c) 160)))
        (when-let [n (:note (:resolution-evidence c))]
          (println (str "      evidence: " (trunc n 140)))))
      (println))))

;; ---------------------------------------------------------------------------
;; types / producers / consumers
;; ---------------------------------------------------------------------------

(defn- types [anns]
  (let [freqs (frequencies (mapcat #(concat (:clara-rules/insert-types %)
                                            (:clara-rules/retract-types %))
                                   (vals anns)))]
    (println (count freqs) "distinct resolved types (producer count)\n")
    (doseq [[t n] (sort-by (comp type->str key) freqs)]
      (printf "%4d  %s%n" n (type->str t)))))

(defn- producers [anns raw]
  (let [want (norm-type raw)
        match? (fn [t] (let [s (type->str t)]
                         (or (= s want) (str/includes? s want))))
        hits (for [[rule a] (sort anns)
                   :let [ins (filter match? (:clara-rules/insert-types a))
                         ret (filter match? (:clara-rules/retract-types a))]
                   :when (or (seq ins) (seq ret))]
               [rule ins ret])]
    (println (str (count hits) " producer rule(s) for " want "\n"))
    (doseq [[rule ins ret] hits]
      (println rule)
      (when (seq ins) (println "  inserts: " (str/join ", " (map type->str ins))))
      (when (seq ret) (println "  retracts:" (str/join ", " (map type->str ret)))))))

(defn- consumers
  "Rules with `raw` on their LHS. Reads production-index.edn — `:lhs-types` is a
  scan column, so this never opens the condition trees or the RHS."
  [index raw]
  (let [want (norm-type raw)
        hits (for [[rule r] (sort (:rules index))
                   :let [ts (filter #(or (= (str %) want)
                                         (str/includes? (str %) want))
                                    (:lhs-types r))]
                   :when (seq ts)]
               [rule ts])]
    (println (str (count hits) " consumer rule(s) with " want " on the LHS\n"))
    (doseq [[rule ts] hits]
      (println rule)
      (println "  lhs-types:" (str/join ", " ts)))))

;; ---------------------------------------------------------------------------
;; rule / edges
;; ---------------------------------------------------------------------------

(defn- find-key-name
  "The key of `m` the caller meant: an exact hit, else the one key containing
  `name*` as a substring. nil when there is no unique answer, having said why."
  [m name*]
  (if (contains? m name*)
    name*
    (let [cands (filter #(str/includes? % name*) (keys m))]
      (cond
        (= 1 (count cands)) (first cands)
        (seq cands) (do (println "Ambiguous —" (count cands) "matches:")
                        (doseq [c (sort cands)] (println " " c))
                        nil)
        :else (do (println "No match for" name*) nil)))))

(defn- find-key [m name*]
  (when-let [k (find-key-name m name*)] (get m k)))

(defn- rule
  "One rule's whole annotation. Callsites arrive complete whichever file this
  read — a layer holds them outright, and the merge resolves to the layer that
  does."
  [anns name*]
  (when-let [k (find-key-name anns name*)]
    (pprint/pprint (get anns k))))

(defn- edges
  "One production's dep-graph neighbors, both directions.

  Only `:upstream` is on disk. `:downstream` is its exact transpose — a node is
  downstream of exactly the nodes listing it upstream — so it is inverted here
  over the whole graph rather than stored twice. See `:slim :recover`."
  [g name*]
  (let [g (or g {})]
    (when-let [node (find-key-name g name*)]
      (let [upstream (into (sorted-set) (:upstream (get g node)))
            downstream (into (sorted-set)
                             (keep (fn [[n e]] (when (some #{node} (:upstream e)) n)))
                             g)]
        (println "upstream (" (count upstream) "):")
        (doseq [u upstream] (println " " u))
        (println "downstream (" (count downstream) "):")
        (doseq [d downstream] (println " " d))))))

;; ---------------------------------------------------------------------------
;; curated — what the overlay actually changed
;; ---------------------------------------------------------------------------

(defn- entry-types [e]
  (set (concat (:clara-rules/insert-types e) (:clara-rules/retract-types e))))

(defn- entry-resolutions [e]
  (into {} (for [[dyn-k _ label] dims
                 :let [d (dyn-k e)]
                 :when d]
             [label (:resolution d)])))

(defn- curated [auto agent]
  (let [changed (remove (fn [[rule e]] (= e (get auto rule))) (sort agent))]
    (println (str (count agent) " rule(s) staged in the overlay, "
                  (count changed) " curated\n"))
    (doseq [[rule e] changed
            :let [base (get auto rule)]]
      (println rule)
      (when-not base
        (println "  !! not present in auto-gen — merge would ADD this rule"))
      (let [new-types (remove (entry-types base) (entry-types e))]
        (when (seq new-types)
          (println "  + types:" (str/join ", " (map type->str new-types)))))
      (doseq [[label res] (entry-resolutions e)
              :let [was (get (entry-resolutions base) label)]
              :when (not= was res)]
        (println (str "  " label " :resolution " was " -> " res)))
      (doseq [{:keys [detected]} (map (fn [[d _ _]] {:detected d}) dims)
              cs (:callsites (get e detected))
              :when (contains? resolved-statuses (:status cs))
              :let [ev (:note (:resolution-evidence cs))]
              :when ev]
        (println (str "    evidence: " (trunc ev 140)))))))

;; ---------------------------------------------------------------------------
;; layers — the fold and its provenance
;; ---------------------------------------------------------------------------

(defn- layers
  "What contributed to the merge, and — per key — which layer claimed each value.
  `merged` is the RAW merged-annotations.edn (envelope intact)."
  [merged name*]
  (cond
    (not (map? merged)) (die "merged-annotations.edn is not a map")

    (nil? (:layers merged))
    (println (str "No :layers key — this is a bare annotations map, not a merge.\n"
                  "Rebuild it: (ex/rebuild-merged!) from a REPL with a session."))

    :else
    (do
      (println "layers folded (lowest precedence first):")
      (doseq [{:keys [id source]} (:layers merged)]
        (printf "  %-42s %s%n" id (trunc source 80)))
      (when-not (some #(= :props (:id %)) (:layers merged))
        (println (str "\n  !! no :props layer — this fold ran without a session, so "
                      "annotations\n     authored in defrule props maps are absent")))
      (println)
      (let [prov (:provenance merged)]
        (if name*
          (when-let [p (find-key prov name*)]
            (doseq [[k origin] (sort-by (comp str key) p)]
              (printf "  %-46s %s%n" k (pr-str origin))))
          (do
            (println "origins across" (count prov) "rules"
                     "(:derived = computed by the merge, not claimed by any layer):")
            (doseq [[origin n] (sort-by (comp - val)
                                        (frequencies (mapcat vals (vals prov))))]
              (printf "  %5d  %s%n" n (pr-str origin)))
            (println "\nAdd a rule name for one rule's per-key origins.")))))))

;; ---------------------------------------------------------------------------

(let [args *command-line-args*
      which (or (second (drop-while #(not= "--file" %) args)) nil)
      [target cmd arg] (remove #{"--file" which} args)]
  (when-not target
    (die (str "usage: bb annotations_report.bb <dir|file.edn> "
              "[summary|gaps|types|producers <t>|consumers <t>|rule <n>|edges <n>|curated"
              "|layers [<n>]] "
              "[--file auto|agent|merged]")))
  (let [cmd (or cmd "summary")
        ;; `gaps` is about the deterministic baseline, so it defaults to auto.
        which (or which (if (= "gaps" cmd) "auto" "merged"))
        paths (resolve-paths target which)
        dir (:dir paths)
        anns (delay (read-annotations (:annotations paths) (str which " annotations") dir))
        auto (delay (read-annotations (:auto paths) (artifact-files "auto") dir))
        agent (delay (read-annotations (:agent paths) (artifact-files "agent") dir))
        merged (delay (read-merged paths))
        analysis-part (->analysis-part dir)]
    (case cmd
      "summary" (summary @anns)
      "gaps" (gaps @anns)
      "types" (types @anns)
      "producers" (if arg (producers @anns arg) (die "producers needs a fact type"))
      "consumers" (if arg (consumers (analysis-part :index) arg)
                      (die "consumers needs a fact type"))
      "rule" (if arg (rule @anns arg) (die "rule needs a rule name"))
      "edges" (if arg (edges (analysis-part :dep-graph) arg) (die "edges needs a rule name"))
      "curated" (curated @auto @agent)
      "layers" (layers @merged arg)
      (die "Unknown subcommand:" cmd))))
