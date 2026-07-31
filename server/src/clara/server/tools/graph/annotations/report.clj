(ns clara.server.tools.graph.annotations.report
  "Reporting and validation over merged annotations (see
   docs/rule-annotations.md, \"Annotation Merging\").

   `unresolved-report` is the work list: what is still unresolved, computed
   from the merged value alone.  `validate-layers` is pure lint over a layer
   stack: structural mistakes, dangling references, ambiguous duplicate-group
   references, no-op entries, and derivation drops."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [schema.core :as s]
            [clara.server.tools.graph.annotations.merge :as ann.merge]))

(def ^:private dimension-keys
  "Report/validation dimension names for the two detection keys."
  {:insert :clara-rules/dynamic-insert-types-detected
   :retract :clara-rules/dynamic-retract-types-detected})

;; ---------------------------------------------------------------------------
;; The work list
;; ---------------------------------------------------------------------------

(defn- report-callsite
  "The work-list view of one callsite."
  [cs]
  (into {:callsite-id (:callsite-id cs)
         :status (:status cs)}
        (comp (map (fn [k] (when (contains? cs k) [k (get cs k)])))
              (filter some?))
        [:source-str :ns-name-sym :constructor-sym]))

(defn unresolved-report
  "The work list: what is still unresolved, computed from the merged value
   alone — everything it reports is a property of the merged entries (a
   dimension's resolution, and whether a callsite has a discovered form).
   `:rules` is the reading material for whoever does the resolving — rules
   with at least one non-:full, non-quarantined callsite — and their output
   is a new sparse layer, not an edit to this report.  `:dangling` names the
   layer responsible for each quarantined entry."
  [merged]
  (let [annotations (:annotations merged)
        rules (into (sorted-map)
                    (keep (fn [[rule-name rule-ann]]
                            (let [dims (into {}
                                             (keep (fn [[dim k]]
                                                     (when-let [dm (get rule-ann k)]
                                                       (let [work (into []
                                                                        (comp (remove :dangling?)
                                                                              (remove #(= :full (:status %)))
                                                                              (map report-callsite))
                                                                        (:callsites dm))]
                                                         (when (seq work)
                                                           [dim {:resolution (:resolution dm)
                                                                 :callsites work}])))))
                                             dimension-keys)]
                              (when (seq dims)
                                [rule-name dims]))))
                    annotations)
        dangling (into []
                       (comp (mapcat (fn [[rule-name rule-ann]]
                                       (keep (fn [[dim k]]
                                               (when-let [dm (get rule-ann k)]
                                                 [rule-name dim dm]))
                                             dimension-keys)))
                             (mapcat (fn [[rule-name dim dm]]
                                       (keep (fn [cs]
                                               (when (:dangling? cs)
                                                 (cond-> {:rule rule-name
                                                          :dimension dim
                                                          :callsite-id (:callsite-id cs)
                                                          :from-layer (:from-layer cs)}
                                                   (:resolution-evidence cs)
                                                   (assoc :resolution-evidence
                                                          (:resolution-evidence cs)))))
                                             (:callsites dm)))))
                       annotations)
        by-resolution (frequencies (mapcat (fn [[_ dims]]
                                             (mapcat (fn [[_ {:keys [callsites]}]]
                                                       (map :status callsites))
                                                     dims))
                                           rules))]
    {:summary {:rules (count rules)
               :callsites (reduce + 0 (vals by-resolution))
               :by-resolution by-resolution
               :dangling (count dangling)}
     :rules rules
     :dangling dangling}))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- finding
  [severity type m]
  (merge {:severity severity :type type} m))

(defn- lint-unknown-rule
  [known-rule-names layer-id rule-name]
  (when (and known-rule-names (not (contains? known-rule-names rule-name)))
    [(finding :error :unknown-rule
              {:layer layer-id
               :rule rule-name
               :message (format "rule %s matches no rule in the rulebase — a typo would merge in as a phantom entry"
                                rule-name)})]))

(defn- lint-callsite-structure
  "Per-callsite structural checks: conclusions with no types, and hand-written
   derived fields."
  [layer-id rule-name dim cs]
  (concat
   (when (and (#{:full :partial} (:status cs))
              (empty? (:resolved-types cs)))
     [(finding :error :resolved-without-types
               {:layer layer-id
                :rule rule-name
                :dimension dim
                :callsite-id (:callsite-id cs)
                :message (format "status %s with no :resolved-types"
                                 (:status cs))})])
   (keep (fn [derived-k]
           (when (contains? cs derived-k)
             (finding :warn :authored-derived-field
                      {:layer layer-id
                       :rule rule-name
                       :dimension dim
                       :callsite-id (:callsite-id cs)
                       :message (format "%s is computed by the merge, never authored — the value is ignored"
                                        derived-k)})))
         [:from-layer :dangling?])))

(defn- discovered-entry?
  "A callsite entry written by the analyzer carries :filename; a curating
   layer's witness restates only :source-str."
  [cs]
  (contains? cs :filename))

(defn- lint-detection-structure
  [layer-id rule-name dim dm]
  (concat
   ;; a discovering (analyzer-written) layer legitimately carries the
   ;; :resolution it derived; anyone else's authored value is ignored
   (when (and (map? dm)
              (contains? dm :resolution)
              (not (every? discovered-entry? (:callsites dm))))
     [(finding :warn :authored-derived-field
               {:layer layer-id
                :rule rule-name
                :dimension dim
                :message "detection-map :resolution is derived, never authored — the value is ignored"})])
   (mapcat #(lint-callsite-structure layer-id rule-name dim %)
           (:callsites dm))))

(defn- lint-layer-structure
  "Per-layer checks that need no merge state: unknown rules, conclusions with
   no types, and hand-written derived fields."
  [known-rule-names layer]
  (let [layer-id (:id layer)]
    (into []
          (mapcat (fn [[rule-name rule-ann]]
                    (concat (lint-unknown-rule known-rule-names layer-id rule-name)
                            (mapcat (fn [[dim k]]
                                      (lint-detection-structure layer-id rule-name dim
                                                                (get rule-ann k)))
                                    dimension-keys))))
          (:annotations layer))))

(defn- id-group-prefix
  "Strips the trailing ordinal segment from a callsite id, leaving the
   duplicate-group prefix."
  [callsite-id]
  (some-> callsite-id (str/split #":") (->> (drop-last) (str/join ":"))))

(defn- annotation-callsites
  "All callsite entries of an annotations-shaped map (a layer's or a merged
   result's `:annotations`), as [rule-name dimension callsite] tuples."
  [annotations]
  (into []
        (mapcat (fn [[rule-name rule-ann]]
                  (mapcat (fn [[dim k]]
                            (map (fn [cs] [rule-name dim cs])
                                 (:callsites (get rule-ann k))))
                          dimension-keys)))
        annotations))

(defn- lint-ambiguous-references
  "Warns when a layer references a callsite id whose duplicate group has more
   than one member — the ordinal is positional, so the reference may
   mis-attribute.  Group sizes are computed from the discovered callsites in
   the merged output."
  [layers merged]
  (let [group-sizes (frequencies
                     (into []
                           (comp (filter (fn [[_ _ cs]] (and (:source-str cs)
                                                             (not (:dangling? cs)))))
                                 (map (fn [[_ _ cs]] (id-group-prefix (:callsite-id cs)))))
                           (annotation-callsites (:annotations merged))))
        ambiguous (into #{}
                        (keep (fn [[prefix n]] (when (> n 1) prefix)))
                        group-sizes)]
    (into []
          (mapcat (fn [layer]
                    (keep (fn [[rule-name dim cs]]
                            (when (and (not (discovered-entry? cs))
                                       (contains? ambiguous (id-group-prefix (:callsite-id cs))))
                              (finding :warn :ambiguous-callsite-reference
                                       {:layer (:id layer)
                                        :rule rule-name
                                        :dimension dim
                                        :callsite-id (:callsite-id cs)
                                        :message "referenced id belongs to a duplicate group with more than one member — its ordinal is positional"})))
                          (annotation-callsites (:annotations layer)))))
          layers)))

(defn- lint-dangling
  "Warns on quarantined callsites in the merged output."
  [merged]
  (into []
        (keep (fn [[rule-name dim cs]]
                (when (:dangling? cs)
                  (finding :warn :dangling-callsite
                           {:layer (:from-layer cs)
                            :rule rule-name
                            :dimension dim
                            :callsite-id (:callsite-id cs)
                            :message "no discovered form matches this entry — the assertion annotates nothing"}))))
        (annotation-callsites (:annotations merged))))

(defn- lint-no-op-entries
  "Warns when a layer's assertion is identical to the merged state beneath
   it: restating a value changes nothing.  Provenance is ignored — only the
   annotation payload is compared."
  [layers]
  (second
   (reduce (fn [[acc findings] layer]
             (let [layer-id (:id layer)
                   before (:annotations acc)
                   after (:annotations (ann.merge/fold-layer acc layer))
                   layer-findings
                   (into []
                         (mapcat (fn [[rule-name rule-ann]]
                                   (keep (fn [[k v]]
                                           (when (and (not= :clara-rules/merge-props k)
                                                      (some? v)
                                                      (contains? (get before rule-name) k)
                                                      (= v (get-in before [rule-name k])))
                                             (finding :warn :no-op-entry
                                                      {:layer layer-id
                                                       :rule rule-name
                                                       :message (format "%s restates the merged value beneath it — the entry changes nothing"
                                                                        k)})))
                                         rule-ann)))
                         (:annotations layer))]
               [{:annotations after :provenance {}}
                (into findings layer-findings)]))
           [{:annotations (sorted-map) :provenance {}} []]
           layers)))

(defn- lint-derivation-drops
  "Under :from-callsites, warns on each authored type dropped because the
   dimension's callsites do not support it.  Compares the no-derivation merge
   (merged authored types) against the derived merge."
  [layers]
  (let [authored (ann.merge/merge-layers layers {:type-derivation :none})
        derived (:annotations (ann.merge/merge-layers layers {:type-derivation :from-callsites}))]
    (into []
          (comp (mapcat (fn [[rule-name rule-ann]]
                          (keep (fn [[dim types-k dm-k]]
                                  (when (get rule-ann dm-k)
                                    (let [dropped (set/difference (set (get rule-ann types-k))
                                                                  (set (get-in derived [rule-name types-k])))]
                                      (when (seq dropped)
                                        [rule-name dim types-k dropped]))))
                                ann.merge/dimension-derivation-keys)))
                (mapcat (fn [[rule-name dim types-k dropped]]
                          (let [origin (get-in authored [:provenance rule-name types-k])
                                layer-id (cond
                                           (keyword? origin) origin
                                           (and (vector? origin) (= 1 (count origin))) (first origin))]
                            (map (fn [t]
                                   (finding :warn :derivation-dropped-authored-type
                                            (cond-> {:rule rule-name
                                                     :dimension dim
                                                     :message (format "authored type %s dropped — the dimension's callsites do not support it under :from-callsites"
                                                                      t)}
                                              layer-id (assoc :layer layer-id))))
                                 dropped)))))
          (:annotations authored))))

(defn validate-layers
  "Pure lint over a layer stack.  Returns a vector of findings:
   {:severity :error|:warn :type … :layer … :rule … :dimension …
    :callsite-id … :message …}.

   `:known-rule-names` is optional so validation works offline from artifacts
   alone; supply it from a live session or an analysis file to enable
   `:unknown-rule`.  `:type-derivation :from-callsites` additionally enables
   `:derivation-dropped-authored-type`."
  ([layers] (validate-layers layers {}))
  ([layers {:keys [known-rule-names type-derivation]
            :or {type-derivation :additive}}]
   (let [layers (mapv #(s/validate ann.merge/Layer (ann.merge/normalize-layer %)) layers)
         merged (ann.merge/merge-layers layers {:type-derivation type-derivation})]
     (into []
           (comp cat (distinct))
           [(lint-no-op-entries layers)
            (mapcat #(lint-layer-structure known-rule-names %) layers)
            (lint-ambiguous-references layers merged)
            (lint-dangling merged)
            (when (= :from-callsites type-derivation)
              (lint-derivation-drops layers))]))))
