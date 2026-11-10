(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.status-report
  "Text rendering of a `clara.explorer.artifacts.status/unit-status` report:
  reason fragments, per-source cells, ordered field pairs with aligned
  printing, and `--source-checkout` parsing."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clojure.string :as str]))

;; ===========================================================================
;; usage errors
;; ===========================================================================

(defn- ->invalid-args-ex
  "A `--source-checkout` usage failure. Carries\n  `:clara.explorer.artifacts.status/invalid-args`, so a host command line tells a\n  usage error apart from a failed comparison without matching message text."
  [message data]
  (ex-info message (assoc data :clara.explorer.artifacts.status/invalid-args true)))

(defn parse-source-checkouts
  "`--source-checkout <repo>=<path>` values as `{repo path}`. Throws a usage\n  error (see `->invalid-args-ex`) on a value without `=`, on an empty repo or\n  path, and on a repo named twice. Any command line parser can call this."
  [values]
  (reduce (fn [acc value]
            (let [s (str value)
                  i (str/index-of s "=")]
              (when (nil? i)
                (throw (->invalid-args-ex
                        (format "--source-checkout needs <repo>=<path>, got %s" value)
                        {:value (str value)})))
              (let [repo (subs s 0 i)
                    path (subs s (inc i))]
                (when (or (str/blank? repo) (str/blank? path))
                  (throw (->invalid-args-ex
                          (format "--source-checkout needs both a repo and a path, got %s" value)
                          {:value (str value)})))
                (when (contains? acc repo)
                  (throw (->invalid-args-ex
                          (format "--source-checkout given twice for %s" repo)
                          {:repo repo})))
                (assoc acc repo path))))
          {}
          values))

;; ===========================================================================
;; reason text
;; ===========================================================================

(defmulti reason-summary
  "One reason as a `--edn`-free fragment of the verdict line. The library defines\n  a method for each of its checks; a host adds a method for each of its own.\n  `:default` prints the reason as data. `:unit-stale` and `:unit-unknown`\n  recurse over the nested reasons, leaving out the informational ones."
  :check)

(defmethod reason-summary :sha-drift
  [{:keys [recorded current]}]
  (format "sha-drift %s -> %s" (layout/->short-sha recorded) (layout/->short-sha current)))

(defmethod reason-summary :remote-mismatch
  [{:keys [recorded current]}]
  (format "remote-mismatch (recorded %s, checkout %s)" recorded current))

(defmethod reason-summary :generated-dirty
  [_]
  "generated-dirty (the unit describes no single commit)")

(defmethod reason-summary :checkout-dirty
  [_]
  "checkout-dirty (informational)")

(defmethod reason-summary :age-exceeded
  [{:keys [updated max-age-days]}]
  (format "age-exceeded (updated %s, older than %s days)" updated max-age-days))

(defmethod reason-summary :sha-not-compared
  [_]
  "sha not compared")

(defmethod reason-summary :ref-unresolvable
  [{:keys [ref checkout]}]
  (format "ref %s unresolvable in %s" ref checkout))

(defmethod reason-summary :checkout-not-a-repo
  [{:keys [checkout]}]
  (format "%s is not a git checkout" checkout))

(defmethod reason-summary :checkout-off-ref
  [{:keys [recorded current]}]
  (format "checkout on %s, unit recorded %s" current recorded))

(defmethod reason-summary :source-sha-drift
  [{:keys [unit-key recorded current]}]
  (format "%s: composition sha-drift %s -> %s"
          unit-key (layout/->short-sha recorded) (layout/->short-sha current)))

(defmethod reason-summary :source-missing
  [{:keys [unit-key]}]
  (format "%s: composition missing" unit-key))

(defmethod reason-summary :source-cycle
  [_]
  "composition cycle (not followed)")

(defmethod reason-summary :unit-report-missing
  [_]
  "no report from the source status function")

(defmethod reason-summary :aggregate-no-sources
  [_]
  "aggregate without per-source shas (no verdict)")

(defn- informative-reasons
  "Nested `reasons` without the informational entries."
  [reasons]
  (remove :informational reasons))

(defmethod reason-summary :unit-stale
  [{:keys [unit-key reasons]}]
  (format "%s: unit %s"
          unit-key
          (str/join "; " (map reason-summary (informative-reasons reasons)))))

(defmethod reason-summary :unit-unknown
  [{:keys [unit-key reasons]}]
  (format "%s: unit %s"
          unit-key
          (str/join "; " (map reason-summary (informative-reasons reasons)))))

(defmethod reason-summary :default
  [reason]
  (pr-str reason))

;; ===========================================================================
;; sources rows
;; ===========================================================================

(defn- unit-fragment
  "A source unit's own verdict and the reasons behind it, as one cell of a\n  `sources` row: `unit current`, or `unit stale (sha-drift aaa1111 -> bbb2222)`.\n  Informational reasons live in `notes`, not in the cell."
  [{:keys [verdict reasons]}]
  (let [fragments (map reason-summary (informative-reasons reasons))]
    (if (seq fragments)
      (format "unit %s (%s)" (name verdict) (str/join "; " fragments))
      (format "unit %s" (name verdict)))))

(defn- unit-absence
  "Why a source with no unit-status cell has none, read from the report's\n  top-level reasons: `:source-cycle` or `:unit-report-missing`, or nil when the\n  source unit itself is gone (the composition's record says `:source-missing`)."
  [reasons unit-key]
  (->> reasons
       (filter #(= unit-key (:unit-key %)))
       (mapcat :reasons)
       (map :check)
       (filter #{:source-cycle :unit-report-missing})
       first))

(defn ->source-rows
  "A composed report's `:sources` as `[unit-key composition unit]` text cells:\n  the composition's verdict on the sha it recorded for that source, then the\n  source unit's own status verdict with its reasons. A source with no report of\n  its own says which absence it is rather than claiming anything."
  [{:keys [reasons sources]}]
  (mapv (fn [{:keys [unit-key composition unit-status]}]
          (let [absence (unit-absence reasons unit-key)]
            [unit-key
             (format "composition %s" (name (:verdict composition)))
             (cond
               (some? unit-status) (unit-fragment unit-status)
               (= :source-cycle absence) "unit not checked (composition cycle)"
               (= :unit-report-missing absence) "unit report missing"
               :else "unit missing")]))
        (or sources [])))

;; ===========================================================================
;; report fields
;; ===========================================================================

(defn- collect-informational
  "Every informational reason in `report`, top level and nested under each\n  `:sources` entry's `:unit-status` (recursing into compositions)."
  [report]
  (letfn [(from-reasons [reasons]
            (mapcat (fn [reason]
                      (cond
                        (:informational reason) [reason]
                        (contains? #{:unit-stale :unit-unknown} (:check reason))
                        (from-reasons (:reasons reason))
                        :else []))
                    reasons))
          (from-report [r]
            (concat (from-reasons (:reasons r))
                    (mapcat (fn [entry] (when-let [u (:unit-status entry)]
                                          (from-report u)))
                            (:sources r))))]
    (vec (from-report report))))

(defn- verdict-summary
  "The `verdict` cell: for a composed report with `:sources`, the verdict plus\n  one count per top-level reason kind (`stale | unit-stale 2, source-sha-drift\n  1`) — the `sources` rows already name each source and what moved. Otherwise\n  the verdict plus each deciding fragment, informational reasons aside."
  [{:keys [verdict reasons sources]}]
  (let [deciding (informative-reasons reasons)]
    (if (seq sources)
      (if (seq deciding)
        (let [counts (reduce (fn [acc {:keys [check]}]
                               (update acc check (fnil inc 0)))
                             {}
                             deciding)
              order (distinct (map :check deciding))]
          (format "%s | %s"
                  (name verdict)
                  (str/join ", " (map #(format "%s %d" (name %) (get counts %)) order))))
        (name verdict))
      (if (seq deciding)
        (format "%s | %s" (name verdict) (str/join "; " (map reason-summary deciding)))
        (name verdict)))))

(defn- ->unit-label
  [repo variant]
  (str repo (when (seq variant) (str "@" (layout/variant->path variant)))))

(defn- ->kind-label
  [kind variant mode]
  (str (name kind) " unit"
       (when (seq variant) (str " (variant " (layout/variant->path variant) ")"))
       (when mode (str " (mode " mode ")"))))

(defn- ->source-label
  [source updated]
  (format "%s (%s, %s)   updated %s"
          (layout/->short-sha (:sha source))
          (or (:branch source) "no branch")
          (:working-tree source)
          updated))

(defn- ->compared-label
  [{:keys [checkout ref sha]}]
  (format "%s @ %s = %s" checkout ref (if (some? sha) (layout/->short-sha sha) "unresolvable")))

(defn- ->policy-label
  [staleness]
  (str (:policy staleness)
       (when-let [d (:max-age-days staleness)]
         (format " (max %s days)" d))))

(defn ->report-fields
  "The whole report as ordered `[label value]` pairs: `unit`, `kind`, `source`,\n  `compared` (when the report carries one), `policy`, `sources` (one pair per\n  row, the label only on the first), `verdict`, and `notes` (when informational\n  reasons exist). A host splices fields of its own in with\n  `insert-fields-after`; `print-fields!` prints the pairs."
  [{:keys [repo variant kind mode source updated staleness compared] :as report}]
  (let [rows (->source-rows report)
        notes (collect-informational report)]
    (cond-> [["unit" (->unit-label repo variant)]
             ["kind" (->kind-label kind variant mode)]
             ["source" (->source-label source updated)]]
      (some? compared) (conj ["compared" (->compared-label compared)])
      true (conj ["policy" (->policy-label staleness)])
      (seq rows) (into (map-indexed (fn [i cells]
                                      [(if (zero? i) "sources" "")
                                       (str/join "  " cells)])
                                    rows))
      true (conj ["verdict" (verdict-summary report)])
      (seq notes) (conj ["notes" (str/join "; " (map reason-summary notes))]))))

(defn insert-fields-after
  "Splice `new-pairs` (`[[label value] ...]`) into `fields` after the first pair\n  whose label is `label`. Throws when no pair carries that label."
  [fields label new-pairs]
  (let [idx (first (keep-indexed (fn [i [l _]] (when (= label l) i)) fields))]
    (when (nil? idx)
      (throw (ex-info (format "No report field labelled %s" (pr-str label))
                      {:label label})))
    (vec (concat (subvec (vec fields) 0 (inc idx)) new-pairs (subvec (vec fields) (inc idx))))))

(defn print-fields!
  "Print `[label value]` pairs with an aligned label column. `sources` rows (the\n  `sources` pair and its `\"\"` continuations) are aligned as columns too, so a\n  long unit key never misaligns the cells."
  [fields]
  (let [fields (vec fields)
        label-width (reduce (fn [w [label]] (max w (count (str label)))) 0 fields)
        row-fmt (str "%-" label-width "s %s")
        source-idxs (keep-indexed (fn [i [label _]]
                                    (when (or (= "sources" label) (= "" label)) i))
                                  fields)
        source-cells (mapv #(str/split (str (second (nth fields %))) #"  +") source-idxs)
        cell-widths (if (seq source-cells)
                      (reduce (fn [acc cells] (mapv max acc (map count cells)))
                              (repeat (apply max (map count source-cells)) 0)
                              source-cells)
                      [])
        cell-set (set source-idxs)]
    (doseq [[i [label value]] (map-indexed vector fields)]
      (if (and (seq cell-widths) (contains? cell-set i))
        (let [cells (str/split (str value) #"  +")
              padded (map-indexed (fn [ci c] (format (format "%%-%ds" (nth cell-widths ci)) c))
                                  cells)]
          (println (format row-fmt label (str/join "  " padded))))
        (println (format row-fmt label value))))))
