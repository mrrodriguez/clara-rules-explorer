#!/usr/bin/env bb
;; Offline triage of the persisted annotation artifacts — the ANNOTATION-side
;; reader: layers, callsites, resolution, curation, provenance. Each subcommand
;; reads only the artifact parts it needs.
;;
;;   bb annotations_report.bb <dir|file.edn> [subcommand [arg]] [--file auto|memory|agent|merged]
;;
;; The subcommand menu, signatures, and options are enumerated by the
;; `subcommands` and `usage-line` vars below, and printed by the `help`
;; subcommand. The on-disk layout those subcommands read (fact-types.edn,
;; production-index.edn, merged-annotations references) is documented in
;; docs/persisted-artifacts.md.
;;
;; For anything structural the annotation artifacts don't hold — the Rete graph,
;; :lhs-form, working memory — start the explorer server and query /v1.
;;

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

;; Put `explorer/src` on the classpath (and schema, if a script wants it), then
;; `require` the JVM source namespaces directly instead of `load-file`ing a
;; symlinked copy of `layout`. `bootstrap.bb` is what makes `require` work.
(load-file (str (fs/file (fs/parent (fs/canonicalize *file*)) "bootstrap.bb")))

(require '[clara.explorer.artifacts.layout :as layout]
         '[clara.explorer.artifacts.hierarchy :as hierarchy]
         '[clara.explorer.artifacts.cross-unit :as cross-unit]
         '[clara.explorer.artifacts.diff :as diff]
         '[clara.explorer.artifacts.status :as status])

(def ^:private dims
  [[:clara-rules/dynamic-insert-types-detected :clara-rules/insert-types "insert"]
   [:clara-rules/dynamic-retract-types-detected :clara-rules/retract-types "retract"]])

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit 1))

(def ^:private artifact-files
  "The `--file` values, mapped to the filenames `layout/artifact-files` gives
  their roles. The annotation layers an annotation-reading subcommand may be
  pointed at — auto, memory, agent — plus the merged fold."
  (into {} (map (fn [k] [(name k) (layout/artifact-files k)]))
        [:auto :memory :agent :merged]))

(def ^:private analysis-dir-name
  "The analysis is a DIRECTORY split by access pattern — a scan opens
  production-index.edn (~1.9MB) rather than the whole ~11.9MB value."
  (:rulebase-analysis layout/artifact-files))

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
  "Every layer file in `dir` that exists, in fold order, as [layer-id
  annotations]. What merged-annotations.edn's references resolve against. The
  fold and extraction live in `layout/read-layer-stack`; here the reader is
  the loud one — a missing layer is skipped, a malformed one dies."
  [dir]
  (layout/read-layer-stack
   dir
   (fn [f]
     (when (fs/exists? f)
       (read-edn f (fs/file-name f))))))

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

(defn- type->name
  "A type as its canonical name — the string fact-types.edn and
  production-index.edn key it by. A keyword keeps its leading colon (the files
  store `:foo/bar` as `\":foo/bar\"`), a symbol prints bare, a string is already a
  name, a vector is a tuple type. `type->str` is the display form; this is the
  matching form, so a keyword fact and its file key are one string."
  [t]
  (if (vector? t) (pr-str t) (str t)))

;; ---------------------------------------------------------------------------
;; the fact-type hierarchy, read from fact-types.edn
;; ---------------------------------------------------------------------------

(defn- ->ancestors
  "fact-types.edn as `{type-name #{ancestor-name}}`. The file's `:ancestors` are
  already transitively closed — they are what Clojure's `ancestors` returned when
  it was written, and compose re-closes across units — so the transpose below is
  complete without re-closing here."
  [fact-types]
  (into {}
        (map (fn [[name entry]]
               [name (into #{} (map str) (:ancestors entry))]))
        fact-types))

(defn- resolve-type-names
  "The known fact-type names `raw` means, as a set of canonical strings, resolved
  against `fact-types` (the fact-types.edn map). Exact first, then the
  colon-preceded spelling — a keyword fact written `foo/bar` for its `:foo/bar`
  key — then substring. A substring that lands on one name prints what it
  resolved to; several are closed over and listed. nil only when nothing matches."
  [fact-types raw]
  (let [want (norm-type raw)
        as-colon (str ":" want)]
    (cond
      (contains? fact-types want) #{want}
      (contains? fact-types as-colon) #{as-colon}
      :else
      (let [cands (into #{} (filter #(str/includes? % want)) (keys fact-types))]
        (cond
          (empty? cands) (do (println "No known fact type matches" want) nil)
          (= 1 (count cands)) (let [c (first cands)]
                                (println "Resolved" raw "->" c)
                                #{c})
          :else (do (println (str "Resolved " want " to " (count cands) " fact types:"))
                    (doseq [c (sort cands)] (println " " c))
                    (println)
                    cands))))))

(defn- rule-match
  "One rule's matched types, split `{:exact {type #{action}} :via {type #{action}}}`,
  over `by-action` (`{action [type …]}`). Types are canonicalized with
  `type->name`; `resolved` is the exact set and `closure` is that plus the
  hierarchy reach. A type is `:via` when it is reached through the hierarchy, not
  by naming."
  [resolved closure by-action]
  (reduce-kv
   (fn [m action types]
     (reduce (fn [m t]
               (let [t (type->name t)]
                 (cond
                   (resolved t) (update-in m [:exact t] (fnil conj #{}) action)
                   (closure t) (update-in m [:via t] (fnil conj #{}) action)
                   :else m)))
             m types))
   {:exact {} :via {}}
   by-action))

(defn- print-type-actions
  "`{type-name #{action-keyword}}`, one action per line at `indent`, for a rule
  already printed above it."
  [indent types]
  (doseq [action [:inserts :retracts :lhs-types]
          :let [ts (sort (keep (fn [[t as]] (when (contains? as action) t)) types))]
          :when (seq ts)]
    (println (str indent (name action) ": " (str/join ", " ts)))))

(defn- rule-matches
  "`[[rule {:exact … :via …}] …]` — every `[rule entry]` of `entries` whose
  `by-action` types name `resolved` exactly or reach it via `closure`."
  [entries by-action resolved closure]
  (into []
        (keep (fn [[rule entry]]
                (let [m (rule-match resolved closure (by-action entry))]
                  (when (or (seq (:exact m)) (seq (:via m)))
                    [rule m]))))
        entries))

(defn- print-type-match-block
  "One resolved type's block: its name, then the `exact` and `via` sections,
  `via-word` naming the direction (`descendant` / `ancestor`). `matches` is
  `[[rule {:exact … :via … :unit …}] …]`, already scoped to this one type."
  [type-name matches via-word]
  (let [exact-matches (filterv #(seq (:exact (second %))) matches)
        via-matches (filterv #(seq (:via (second %))) matches)]
    (println type-name)
    (when (and (empty? exact-matches) (empty? via-matches))
      (println "  (no matches)"))
    (when (seq exact-matches)
      (println "  exact")
      (doseq [[rule m] exact-matches]
        (println (str "    " rule))
        (when-let [u (:unit m)] (println (str "      unit: " u)))
        (print-type-actions "      " (:exact m))))
    (doseq [via-type (sort (into #{} (mapcat (comp keys :via second) via-matches)))]
      (println (str "  via " via-word " " via-type))
      (doseq [[rule m] via-matches
              :when (contains? (:via m) via-type)]
        (println (str "    " rule))
        (when-let [u (:unit m)] (println (str "      unit: " u)))
        (print-type-actions "      " (select-keys (:via m) [via-type]))))))

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

(defn- producers
  "Rules inserting (or retracting) `raw` or any descendant of it. Reads
  fact-types.edn for the hierarchy; the exact/via split keeps the literal answer
  visible without hiding the closure —
  `clara.explorer.artifacts.slim`'s `:inserted-by-rules` runs the same
  closure over `:insert-types`. When `raw` resolves to several types, each gets
  its own block so a `via` rule is attributed to the type it descends from."
  [anns fact-types raw]
  (when-let [resolved (resolve-type-names fact-types raw)]
    (let [descendants (hierarchy/->descendants (->ancestors fact-types))
          entries (sort anns)
          by-action (fn [a] {:inserts (:clara-rules/insert-types a)
                             :retracts (:clara-rules/retract-types a)})
          matches (fn [rs closure] (rule-matches entries by-action rs closure))
          overall (matches resolved (hierarchy/descendant-closure descendants resolved))
          n-exact (count (filter #(seq (:exact (second %))) overall))
          n-via (count (filter #(seq (:via (second %))) overall))]
      (println (str (count overall) " producer rule(s) for " raw))
      (println (format "  (%d exact, %d via descendants)" n-exact n-via))
      (doseq [name (sort resolved)]
        (println)
        (print-type-match-block name
                                (matches #{name}
                                         (hierarchy/descendant-closure descendants #{name}))
                                "descendant")))))

(defn- consumers
  "Rules whose `:lhs-types` hold `raw` or any ancestor of it. Reads
  production-index.edn and fact-types.edn; the exact/via split keeps the literal
  answer visible without hiding the closure —
  `clara.explorer.artifacts.slim`'s `:used-by-rules` runs the same
  closure over `:lhs-types`, and it runs the OPPOSITE way from `producers`.
  When `raw` resolves to several types, each gets its own block so a `via` rule
  is attributed to the type its LHS type ascends to."
  [index fact-types raw]
  (when-let [resolved (resolve-type-names fact-types raw)]
    (let [ancestors (->ancestors fact-types)
          entries (sort (:rules index))
          unit-of (into {} (map (fn [[rule r]] [rule (:unit r)])) entries)
          by-action (fn [r] {:lhs-types (:lhs-types r)})
          matches (fn [rs closure]
                    (mapv (fn [[rule m]]
                            [rule (assoc m :unit (get unit-of rule))])
                          (rule-matches entries by-action rs closure)))
          overall (matches resolved (hierarchy/ancestor-closure ancestors resolved))
          n-exact (count (filter #(seq (:exact (second %))) overall))
          n-via (count (filter #(seq (:via (second %))) overall))]
      (println (str (count overall) " consumer rule(s) with " raw " on the LHS"))
      (println (format "  (%d exact, %d via ancestors)" n-exact n-via))
      (doseq [name (sort resolved)]
        (println)
        (print-type-match-block name
                                (matches #{name}
                                         (hierarchy/ancestor-closure ancestors #{name}))
                                "ancestor")))))

(defn- hierarchy
  "One fact type's place in the hierarchy: its ancestors and its descendants,
  read from fact-types.edn. Descendants are the transpose of the recorded
  `:ancestors` — the same direction
  `clara.explorer.artifacts.hierarchy/->descendants` gives."
  [fact-types raw]
  (when-let [resolved (resolve-type-names fact-types raw)]
    (let [ancestors (->ancestors fact-types)
          descendants (hierarchy/->descendants ancestors)]
      (doseq [name (sort resolved)
              :let [as (sort (get ancestors name #{}))
                    ds (sort (get descendants name #{}))]]
        (println name)
        (println (str "  ancestors (" (count as) ")"))
        (doseq [a as] (println (str "    " a)))
        (println (str "  descendants (" (count ds) ")"))
        (doseq [d ds] (println (str "    " d)))
        (println)))))

;; ---------------------------------------------------------------------------
;; rule / edges
;; ---------------------------------------------------------------------------

(defn- find-key-name
  "The key of `m` the caller meant: an exact hit, else the one key containing
  `name*` as a substring. nil when there is no unique answer, having said why.
  A unique substring match prints the fully-qualified key it resolved to, so an
  unqualified name is never answered from an invisible choice. Matching is
  `layout/resolve-key`; this report's job is only how to say the answer."
  [m name*]
  (let [{:keys [exact candidates]} (layout/resolve-key m name*)]
    (cond
      exact exact
      (= 1 (count candidates)) (let [k (first candidates)]
                                 (println "Resolved" name* "->" k)
                                 k)
      (seq candidates) (do (println "Ambiguous —" (count candidates) "matches:")
                           (doseq [c candidates] (println " " c))
                           nil)
      :else (do (println "No match for" name*) nil))))

(defn- find-key [m name*]
  (when-let [k (find-key-name m name*)] (get m k)))

(defn- print-production-annotation
  "One production's annotation — the merged annotation map, callsites whole.
  `name` is already resolved over rules and queries; a production with no
  annotation reads as a note. `:unit` — which component a composed production
  came from — lives on the production entry in `productions`."
  [anns productions name]
  (when-let [u (get-in productions [name :unit])]
    (println "unit:" u))
  (if-let [a (get anns name)]
    (pprint/pprint a)
    (println "no annotation for" name)))

(defn- edges
  "One production's dep-graph neighbors, both directions.

  Only `:upstream` is on disk. `:downstream` is its exact transpose — a node is
  downstream of exactly the nodes listing it upstream — so it is inverted here
  over the whole graph rather than stored twice (see `clara.explorer.artifacts.slim`'s header comment)."
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

;; ---------------------------------------------------------------------------
;; aligned text output
;; ---------------------------------------------------------------------------

(def ^:private field-width
  "Label-column width for `print-field`. Single owner so callers never
  hand-count padding spaces."
  10)

(defn- print-field
  "Print one `label  value` row, padding `label` to `field-width`. Pass an
  empty label for continuation rows."
  [label value]
  (println (format (str "%-" field-width "s %s") label value)))

(def ^:private status-usage-rows
  "The `status` flag rows as `[syntax desc-first & desc-rest]`. Continuation
  lines align under the description column via `print-flag-row`, so a longer
  flag never means recounting spaces."
  [["status --checkout PATH [--ref REF]"
    "compare a source unit against"
    "the checkout at PATH (REF defaults"
    "to HEAD)"]
   ["status --root PATH"
    "registry root a composed unit's"
    "sources are read from (default:"
    "stripped from the unit dir)"]
   ["status --edn"
    "print the result map, not the text"
    "report"]])

(defn- print-flag-row
  "Print one `[syntax desc-first & desc-rest]` row: syntax plus the first
  description line together, remaining lines aligned under the description."
  [syntax desc-first & desc-rest]
  (let [head (str "  " syntax "  ")
        pad (apply str (repeat (count head) " "))]
    (println (str head desc-first))
    (doseq [line desc-rest]
      (println (str pad line)))))

(def ^:private usage-line
  "The invocation skeleton, printed first by `help`."
  "usage: bb annotations_report.bb <dir|file.edn> [subcommand [arg]] [--file auto|memory|agent|merged] [--checkout PATH [--ref REF]] [--root PATH] [--edn] [--part lhs|rhs|props]")

(def ^:private subcommands
  "The subcommand menu as `[name signature description]`, in dispatch order;
  `help` prints it."
  [["summary" "(default)" "counts + resolution tallies"]
   ["gaps" "" "rules with :resolution not :full + unresolved callsites (AUTO layer)"]
   ["types" "" "every resolved insert-type, with producer count"]
   ["producers" "<type>" "rules inserting <type> or a descendant"]
   ["consumers" "<type>" "rules with <type> or an ancestor on LHS"]
   ["hierarchy" "<type>" "that type's ancestors and descendants"]
   ["production" "<fq-name>" "one production's record; --part lhs|rhs|props, --annotations"]
   ["edges" "<fq-name>" "dep-graph upstream/downstream"]
   ["units" "" "composed unit: source units + coverage"]
   ["unit-edges" "[<unit>]" "cross-unit producer->consumer edges (composed only)"]
   ["entry-points" "[<unit>]" "consumed types no rule produces"]
   ["curated" "" "what the agent overlay changed vs auto-gen"]
   ["layers" "[<fq-name>]" "the fold: layers + per-key provenance"]
   ["status" "[--checkout]" "is this unit current? (needs only the manifest)"]
   ["diff" "<after-dir>" "production-level diff of this unit vs <after-dir> (--edn, --production NAME)"]
   ["help" "" "this help"]])

(defn- help []
  (println usage-line)
  (println)
  (println "subcommands:")
  (doseq [[cmd sig desc] subcommands]
    (println (format "  %-12s %-13s %s" cmd sig desc)))
  (println)
  (println "<type> may be written :foo/bar or foo/bar; producers/consumers/")
  (println "hierarchy resolve it exact-first, then substring. <fq-name> likewise")
  (println "falls back to substring search.")
  (println)
  (println "--file auto|memory|agent|merged picks the annotations file for the")
  (println "annotation-reading subcommands (default merged; gaps defaults to auto).")
  (println)
  (println "status checks one unit directory (a source, variant, or composed unit)")
  (println "reading only its rules-inspect-manifest.edn:")
  (doseq [row status-usage-rows]
    (apply print-flag-row row)))

;; ---------------------------------------------------------------------------
;; production — one production's joined record
;; ---------------------------------------------------------------------------

(defn- production-part-field
  "The production field a `--part` value names: `:lhs` and `:props` map to
  themselves, `:rhs` to `:rhs-form`."
  [part]
  (case part
    :lhs :lhs
    :rhs :rhs-form
    :props :props))

(defn- print-production-part
  "One analysis field of a production record: the string `:rhs-form` raw,
  the data fields pretty-printed."
  [field record]
  (let [v (get record field)]
    (if (string? v) (println v) (pprint/pprint v))))

(defn- print-production
  "One production's joined record as text: the index fields, `:resolution`,
  and `:doc`, then `:lhs` one condition per entry, then `:rhs-form`."
  [record]
  (print-field "kind" (name (:kind record)))
  (print-field "ns" (str (:ns record)))
  (when-let [u (:unit record)]
    (print-field "unit" (str u)))
  (print-field "lhs-types" (str/join ", " (map str (:lhs-types record))))
  (print-field "insert-types" (str/join ", " (map str (:insert-types record))))
  (print-field "retract-types" (str/join ", " (map str (:retract-types record))))
  (print-field "resolution" (pr-str (:resolution record)))
  (print-field "doc" (str (:doc record)))
  (println "lhs:")
  (doseq [c (:lhs record)]
    (pprint/pprint c))
  (println "rhs-form:")
  (println (:rhs-form record)))

(defn- production-report
  "`production <fq-name>` over unit dir `dir`: one production's joined record
  from `diff/read-production` — or, with `--annotations`, the merged annotation
  for that name. `--part lhs|rhs|props` prints one analysis field; `--edn`
  prints the whole record; otherwise the text view prints the index fields,
  `:lhs`, and `:rhs-form`. `--file` picks the annotation file for
  `--annotations`."
  [dir name* opts anns productions]
  (let [part (some-> (:part opts) keyword)
        field (some-> part production-part-field)
        annotations? (:annotations opts)]
    (when (and part annotations?)
      (die "--part and --annotations are mutually exclusive"))
    (when (and (:file opts) (not annotations?))
      (die "--file needs --annotations"))
    (when (and part (not (contains? #{:lhs :rhs :props} part)))
      (die "--part must be lhs, rhs, or props"))
    (if annotations?
      (print-production-annotation @anns productions name*)
      (let [needed (cond
                     field #{field}
                     (:edn opts) :all
                     :else #{:kind :ns :unit :lhs-types :insert-types :retract-types
                             :resolution :doc :lhs :rhs-form})
            record (try (diff/read-production dir name* needed)
                        (catch Exception e (die (ex-message e))))]
        (cond
          field (print-production-part field record)
          (:edn opts) (pprint/pprint record)
          :else (print-production record))))))

;; ---------------------------------------------------------------------------
;; status — is this unit current?
;; ---------------------------------------------------------------------------

(def ^:private cli-spec
  "The flags every subcommand recognizes, as `babashka.cli` sees them.
  `:coerce :string` makes a value flag error on a missing value — including when
  the next token is itself a flag (`--checkout --edn`) — while staying optional
  when absent. `babashka.cli` ships with babashka, so parsing stays offline."
  {:file {:coerce :string}
   :checkout {:coerce :string}
   :ref {:coerce :string}
   :root {:coerce :string}
   :production {:coerce :string}
   :part {:coerce :string}
   :annotations {:coerce :boolean}
   :edn {:coerce :boolean}})

(defn- parse-args
  "`{:opts {flag-key value} :positionals […]}` — `babashka.cli` strips known
  flags (and their values), rejects unknown flags, and errors on a missing flag
  value; everything else is positional (babashka.cli returns the leftovers in
  the result's `:org.babashka/cli` metadata)."
  [args]
  (try
    (let [result (cli/parse-opts args {:spec cli-spec
                                       :restrict true
                                       :no-keyword-opts true})]
      {:opts result
       :positionals (get-in (meta result) [:org.babashka/cli :args])})
    (catch Exception e
      (die (ex-message e)))))

(defn- reject-flags
  "Die when `opts` holds a flag outside `allowed` (a set of flag keys)."
  [opts allowed]
  (when-let [bad (seq (remove allowed (keys opts)))]
    (die "Flag(s) not used by this subcommand:"
         (str/join ", " (map #(str "--" (name %)) (sort bad))))))

(defn- reason-summary
  "One reason as a `--edn`-free fragment of the verdict line."
  [{:keys [check recorded current updated max-age-days ref checkout source]}]
  (case check
    :sha-drift (format "sha-drift %s -> %s" (layout/->short-sha recorded) (layout/->short-sha current))
    :remote-mismatch (format "remote-mismatch (recorded %s, checkout %s)" recorded current)
    :generated-dirty "generated-dirty (the unit describes no single commit)"
    :checkout-dirty "checkout-dirty (informational)"
    :age-exceeded (format "age-exceeded (updated %s, older than %s days)" updated max-age-days)
    :sha-not-compared "sha not compared (no --checkout)"
    :ref-unresolvable (format "ref %s unresolvable in %s" ref checkout)
    :checkout-not-a-repo (format "%s is not a git checkout" checkout)
    :source-sha-drift (format "%s: sha-drift %s -> %s"
                              source (layout/->short-sha recorded) (layout/->short-sha current))
    :source-missing (format "%s: missing" source)
    :aggregate-no-sources "aggregate without per-source shas (no verdict)"
    (str check)))

(defn- print-status
  "The `status/unit-status` result map as aligned `label  value` lines."
  [{:keys [repo variant kind mode source updated staleness verdict reasons sources]}]
  (print-field "unit" (str repo (when (seq variant) (str "@" (layout/variant->path variant)))))
  (print-field "kind" (str (name kind) " unit"
                             (when (seq variant) (str " (variant " (layout/variant->path variant) ")"))
                             (when mode (str " (mode " mode ")"))))
  (print-field "source" (format "%s (%s, %s)   updated %s"
                                  (layout/->short-sha (:sha source))
                                  (or (:branch source) "no branch")
                                  (:working-tree source)
                                  updated))
  (print-field "policy" (str (:policy staleness)
                               (when-let [d (:max-age-days staleness)]
                                 (format " (max %s days)" d))))
  (doseq [[i {:keys [source recorded current verdict]}] (map-indexed vector sources)]
    (print-field (if (zero? i) "sources" "")
                  (str source ": " (name verdict)
                       (when (= :sha-drift verdict)
                         (format " %s -> %s" (layout/->short-sha recorded) (layout/->short-sha current))))))
  (print-field "verdict" (str (name verdict)
                                (when (seq reasons)
                                  (str " | " (str/join "; " (map reason-summary reasons)))))))

(defn- status
  "`status [--checkout PATH [--ref REF]] [--root PATH] [--edn]` over unit dir
  `dir`: presentation over `status/unit-status`, which reads
  rules-inspect-manifest.edn (and, for a composed unit, its sources'
  manifests) and nothing else."
  [dir opts]
  (when (and (:ref opts) (nil? (:checkout opts)))
    (die "--ref needs --checkout"))
  (let [result (try
                  (status/unit-status
                   (cond-> {:dir dir}
                     (:checkout opts) (assoc :checkout (:checkout opts))
                     (:ref opts) (assoc :ref (:ref opts))
                     (:root opts) (assoc :root (:root opts))))
                  (catch Exception e
                    (die (ex-message e))))]
    (if (:edn opts)
      (pprint/pprint result)
      (print-status result))))

;; ---------------------------------------------------------------------------
;; cross-unit readings over a (composed) analysis
;; ---------------------------------------------------------------------------

(defn- manifest-of
  "The unit manifest, read only when a cross-unit subcommand needs it."
  [dir]
  (read-edn (fs/file dir (layout/artifact-files :manifest)) (layout/artifact-files :manifest)))

(defn- unit-key-of [u]
  (str (:repo u) (when (seq (:variant u)) (str "@" (layout/variant->path (:variant u))))))

(defn- unit-keys-of [manifest]
  (mapv unit-key-of (get-in manifest [:analysis-run :units])))

(defn- resolve-unit
  "The full unit key `arg` names: an exact key, else the `:repo` that exactly
  one source unit has. Anything else dies, listing the keys."
  [unit-keys arg]
  (cond
    (some #{arg} unit-keys) arg
    :else
    (let [matches (filterv #(= arg (first (str/split % #"@"))) unit-keys)]
      (case (count matches)
        0 (die "No unit matches" arg "— units:" (str/join ", " unit-keys))
        1 (do (println "Resolved" arg "->" (first matches)) (first matches))
        (die "Ambiguous unit" arg "—" (count matches) "units:" (str/join ", " matches))))))

(defn- print-unit-edges
  [edges]
  (doseq [[[p c] {:keys [via rules]}] edges]
    (println (format "%s -> %s  (%d rules)  via %s" p c rules (str/join ", " (sort via))))))

(defn- units-report
  "`units`: one line per source unit (key, sha, production count), then the
  manifest's :coverage one line per key. Composed units only."
  [dir index manifest opts]
  (when (not= :compose (get-in manifest [:analysis-run :mode]))
    (die "units needs a composed unit — this unit's :analysis-run :mode is"
         (pr-str (get-in manifest [:analysis-run :mode]))))
  (let [units (get-in manifest [:analysis-run :units])
        counts (frequencies (keep :unit (concat (vals (:rules index)) (vals (:queries index)))))
        summary (mapv (fn [u]
                        (let [k (unit-key-of u)]
                          {:unit k :sha (:sha u) :productions (get counts k 0)}))
                      units)
        coverage (:coverage manifest)]
    (if (:edn opts)
      (pprint/pprint {:units summary :coverage coverage})
      (do
        (doseq [{:keys [unit sha productions]} summary]
          (println (format "%s  %s  %d productions" unit (or sha "") productions)))
        (println)
        (if (every? (comp empty? val) coverage)
          (println "every namespace in scope is covered")
          (doseq [[k v] (sort-by key coverage)]
            (println (str (name k) ": " (if (coll? v) (str/join ", " v) (str v))))))))))

(defn- unit-edges-report
  "`unit-edges [<unit>]`: cross-unit producer->consumer edges. Composed units
  only; with `<unit>`, only that unit's edges in `feeds` / `fed by` groups."
  [dir index dep-graph fact-types manifest opts arg]
  (when (not= :compose (get-in manifest [:analysis-run :mode]))
    (die "unit-edges needs a composed unit — this unit's :analysis-run :mode is"
         (pr-str (get-in manifest [:analysis-run :mode]))))
  (let [edges (cross-unit/unit-edges index dep-graph fact-types)
        selected (when arg (resolve-unit (unit-keys-of manifest) arg))]
    (cond
      (:edn opts) (pprint/pprint (if selected
                                   {:feeds (into (sorted-map)
                                                 (filter (fn [[[p _] _]] (= p selected)))
                                                 edges)
                                    :fed-by (into (sorted-map)
                                                  (filter (fn [[[_ c] _]] (= c selected)))
                                                  edges)}
                                   edges))
      selected (do
                 (println "feeds:")
                 (print-unit-edges (into (sorted-map) (filter (fn [[[p _] _]] (= p selected))) edges))
                 (println "fed by:")
                 (print-unit-edges (into (sorted-map) (filter (fn [[[_ c] _]] (= c selected))) edges)))
      :else (print-unit-edges edges))))

(defn- entry-points-report
  "`entry-points [<unit>]`: types some production matches that no rule in the
  unit produces, grouped by consuming unit with a consumer count."
  [dir index fact-types manifest opts arg]
  (let [eps (cross-unit/entry-points index fact-types)
        selected (when arg (resolve-unit (unit-keys-of @manifest) arg))
        view (cond
               selected {selected (get eps selected {})}
               (and (contains? eps nil) (= 1 (count eps))) (get eps nil)
               :else eps)]
    (if (:edn opts)
      (pprint/pprint view)
      (if (every? number? (vals view))
        (doseq [[ft n] view]
          (println (str ft "  (" n " production" (when (> n 1) "s") ")")))
        (doseq [[unit types] view]
          (println unit)
          (doseq [[ft n] types]
            (println (str "  " ft "  (" n " production" (when (> n 1) "s") ")"))))))))

(defn- diff-production-report
  "`diff <after-dir> --production NAME`: one production's before/after. Reads
  only the production files of each unit — never the dep-graph, fact-types,
  manifest, or shape — and never runs the full `diff`. As EDN with `--edn`."
  [before-dir after-dir opts]
  (let [before (try {:productions (diff/read-productions before-dir)}
                     (catch Exception e (die (ex-message e))))
        after (try {:productions (diff/read-productions after-dir)}
                    (catch Exception e (die (ex-message e))))
        detail (try (diff/rule-detail before after (:production opts))
                     (catch Exception e (die (ex-message e))))]
    (if (:edn opts)
      (pprint/pprint detail)
      (println (diff/rule-detail-text detail)))))

(defn- diff-report-full
  "`diff <after-dir> [--edn] [--production NAME]` over unit dir `before-dir`: presentation
   over `diff/diff`, which reads both units' `merged-rulebase-analysis/`
   and merged annotations and nothing else; `--edn` prints the `diff` value.
   (`--production` never reaches here — the dispatcher sends it to
   `diff-production-report`.)"
  [before-dir after-dir opts]
  (let [before (try (diff/read-unit before-dir)
                     (catch Exception e (die (ex-message e))))
        after (try (diff/read-unit after-dir)
                    (catch Exception e (die (ex-message e))))
        d (try (diff/diff before after)
                (catch Exception e (die (ex-message e))))]
    (if (:edn opts)
      (pprint/pprint d)
      (println (diff/->text d)))))

(defn- diff-report
  "`diff <after-dir> [--edn] [--production NAME]`: `--production` prints one
  production's before/after via `diff-production-report` — which reads only the
  production files and never runs the full `diff` — while the full report
  reads both units whole."
  [before-dir after-dir opts]
  (if (:production opts)
    (diff-production-report before-dir after-dir opts)
    (diff-report-full before-dir after-dir opts)))

(let [{:keys [opts positionals]} (parse-args *command-line-args*)
      [target cmd arg] positionals]
  (cond
    (or (= "help" target) (= "help" cmd)) (help)

    (nil? target) (help)

    (= (or cmd "summary") "status")
    (do (reject-flags opts #{:checkout :ref :root :edn})
        (if arg
          (die "status takes no positional arg")
          (status target opts)))

    (= cmd "diff")
    (do (reject-flags opts #{:edn :production})
        (if (nil? arg)
          (die "diff needs two unit dirs: bb annotations_report.bb <before-dir> diff <after-dir>")
          (diff-report target arg opts)))

    :else
    (let [cmd (or cmd "summary")]
      (reject-flags opts (cond
                           (= cmd "production") #{:edn :part :annotations :file}
                           (#{"units" "unit-edges" "entry-points"} cmd) #{:edn}
                           :else #{:file}))
      (let [;; `gaps` is about the deterministic baseline, so it defaults to auto.
            which (or (:file opts) (if (= "gaps" cmd) "auto" "merged"))
            paths (resolve-paths target which)
            dir (:dir paths)
            anns (delay (read-annotations (:annotations paths) (str which " annotations") dir))
            auto (delay (read-annotations (:auto paths) (artifact-files "auto") dir))
            agent (delay (read-annotations (:agent paths) (artifact-files "agent") dir))
            merged (delay (read-merged paths))
            analysis-part (->analysis-part dir)
            index (delay (analysis-part :index))
            fact-types (delay (analysis-part :fact-types))
            manifest (delay (manifest-of dir))]
        (case cmd
          "summary" (summary @anns)
          "gaps" (gaps @anns)
          "types" (types @anns)
          "producers" (if arg (producers @anns @fact-types arg) (die "producers needs a fact type"))
          "consumers" (if arg (consumers @index @fact-types arg)
                          (die "consumers needs a fact type"))
          "hierarchy" (if arg (hierarchy @fact-types arg) (die "hierarchy needs a fact type"))
          "production" (if arg
                         (let [idx @index
                               all (merge (:queries idx) (:rules idx))
                               resolved (or (find-key-name all arg) (System/exit 1))]
                           (production-report dir resolved opts anns all))
                         (die "production needs a production name"))
          "edges" (if arg (edges (analysis-part :dep-graph) arg) (die "edges needs a rule name"))
          "units" (units-report dir @index @manifest opts)
          "unit-edges" (unit-edges-report dir @index (analysis-part :dep-graph) @fact-types @manifest opts arg)
          "entry-points" (entry-points-report dir @index @fact-types manifest opts arg)
          "curated" (curated @auto @agent)
          "layers" (layers @merged arg)
          (die "Unknown subcommand:" cmd))))))
