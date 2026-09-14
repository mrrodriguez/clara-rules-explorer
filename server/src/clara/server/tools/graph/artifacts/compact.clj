(ns clara.server.tools.graph.artifacts.compact
  "Store `merged-annotations.edn` by reference to its layers instead of by value.

  The merge is overwhelmingly a copy of one layer. Over a 3,426-rule ruleset,
  3,403 rules have a merged annotation *identical* to the generated layer's; over
  a restored 18-ruleset session, 4,523 of 4,591. The fold only genuinely combined
  23 and 68 of them. So the file spends millions of bytes restating what sits
  beside it, and its `:provenance` — 803KB — says the same thing 6,707 times.

  `->compact` replaces each restatement with the layer's id; `<-compact` puts it
  back. Round trip is exact, and
  `clara.server.tools.graph.artifacts.store/read-merged-annotations` runs it, so
  every consumer still sees a whole `schema/MergedAnnotations`.

  **A rule appears in the file once, or not at all.** Either it came whole from
  one layer — then it is covered by `:verbatim`'s default and says nothing further
  about itself, provenance included — or the fold combined it, and it is spelled
  out under `:annotations` with its own `:provenance` entry. The two lists are the
  same list: in both measured artifacts, the rules needing their own provenance
  are precisely the 23 and 68 already inlined.

    repo ruleset    5,623,859 →  34,168 bytes
    session         8,056,468 → 322,523 bytes

  ## Nothing here predicts what the fold does

  A rule is stored by reference only when a layer's annotation, stamped, is `=`
  to the merged one. That is a comparison of two values both in hand at write
  time — not a model of
  `clara.server.tools.graph.annotations.merge/merge-layers`. Anything the fold
  did that this namespace does not know about makes the comparison fail, and a
  failed comparison inlines the rule. There is no case where being wrong about
  the merge produces a wrong file; the worst it can do is produce a bigger one.

  `layout/stamp-annotation` is the one piece that mirrors the fold, and it is
  used as a *candidate* to be tested, never as an answer. Both directions call
  it, so whatever it does, it does symmetrically.

  ## Expansion lives in `layout`

  `<-compact` here is a schema-validating wrapper over
  `layout/expand-merged-annotations`. The decode has to be shared with the
  babashka report script, which cannot load `schema.core` — see that namespace.
  Compaction stays here: it is a write-time concern needing the fold's own layer
  stack, and no reader performs it.

  ## It replaces the evidence split

  There was a pass here that stripped `:via` / `:source-str` off resolved
  callsites in the merge, because the layer beside it kept them. This subsumes
  that: a by-reference rule holds no callsites at all, so there is no evidence in
  it to strip, and the reader gets whole callsites — evidence included — straight
  off the layer. Smaller, and one fewer thing that has to be put back.

  Inlined rules keep their evidence. They are the minority the fold combined, and
  a special case worth 250KB is not worth a second mechanism."
  (:require
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; the candidate
;; ===========================================================================

(s/defn ^:private ->verbatim-layer :- (s/maybe schema/LayerId)
  "The layer that reproduces `merged-annotation` exactly, or nil when none does
  and the value has to be inlined.

  `by-precedence` is `schema/LayerAnnotationsStack` reversed — highest precedence
  first — so two layers holding the same annotation resolve to the one the fold
  would have credited."
  [by-precedence :- schema/LayerAnnotationsStack
   rule-name :- schema/RuleName
   merged-annotation :- schema/RuleAnnotation]
  (some (fn [[layer-id annotations]]
          (when-let [annotation (get annotations rule-name)]
            (when (= merged-annotation (layout/stamp-annotation layer-id annotation))
              layer-id)))
        by-precedence))

(defn- ->dominant
  "The most frequent value of `xs`, ties broken by print order so the choice is
  stable across runs. nil for an empty `xs`."
  [xs]
  (->> (frequencies xs)
       (sort-by (juxt (comp - val) (comp pr-str key)))
       ffirst))

;; ===========================================================================
;; compact
;; ===========================================================================

(s/defn ^:private ->provenance-template :- {s/Keyword schema/Origin}
  "What a by-reference rule's origins look like, per annotation key.

  A rule stored by reference came, whole, from one layer — so every one of its
  keys has that layer as its origin, and the only thing left to record is the
  *shape* the merge writes it in. That shape tracks the key, not the rule: a
  vector for keys merged by union or deep-merge, a bare layer id for keys the
  highest layer simply overwrites. Hence `:clara-rules/insert-types` is
  `[the-layer]` where `:clara-rules/notes` is `the-layer`, in every artifact, with
  no key ever taking both.

  Built from the by-reference rules themselves rather than declared, and only a
  starting point: `->compact-provenance` checks it against every rule and writes
  out any it fails to reproduce."
  [provenance rule-names]
  (let [origins-by-key (volatile! {})]
    (doseq [rule-name rule-names
            [k origin] (get provenance rule-name)]
      (vswap! origins-by-key update k (fnil conj []) origin))
    (into (sorted-map)
          (map (fn [[k origins]] [k (->dominant origins)]))
          @origins-by-key)))

(defn- ->compact-provenance
  "`:provenance` as the template plus every rule the template does not reproduce.

  That split lands exactly on the by-reference/inlined line — in both measured
  artifacts, the rules needing their own provenance are precisely the ones
  already inlined under `:annotations` (23 of 23, 68 of 68). So the file says each
  rule once: named in `:verbatim` and silent here, or spelled out in both.

  Driven off `annotations`, not `provenance`, so a rule carrying no origins at all
  is written as an explicit empty entry rather than silently given the
  template's."
  [provenance annotations template]
  {:verbatim template
   :except (into (sorted-map)
                 (keep (fn [[rule-name annotation]]
                         (let [origins (get provenance rule-name)]
                           (when-not (= origins (layout/expand-rule-provenance template annotation))
                             [rule-name (into (sorted-map) origins)]))))
                 annotations)})

(s/defn ->compact :- schema/CompactMergedAnnotations
  "`merged` with every rule a layer already holds replaced by that layer's id.

  `layers` is the *file-backed* stack in fold order. A rule no layer reproduces —
  because the fold combined two of them, or because it came from the
  file-less `:props` layer — is inlined whole under `:annotations`."
  [merged :- schema/MergedAnnotations
   layers :- schema/LayerAnnotationsStack]
  (let [annotations (:annotations merged)
        ;; Expansion enumerates rules from the layer files, so a layer rule the
        ;; merge does not have would come back from the dead. `merge-layers`
        ;; unions rules and has never dropped one — across every measured
        ;; artifact the two sets are equal — so this refuses to write rather than
        ;; carrying a permanently-empty exclusion list in every file.
        dropped (into (sorted-set)
                      (comp (mapcat (comp keys second)) (remove annotations))
                      layers)
        _ (when (seq dropped)
            (throw (ex-info (str "Cannot compact: the merge is missing rules its layers have. "
                                 "Expansion enumerates rules from the layers, so these would "
                                 "reappear.")
                            {:missing-rules (vec dropped)
                             :missing-count (count dropped)})))
        by-precedence (vec (reverse layers))
        layer-of (into {}
                       (keep (fn [[rule-name annotation]]
                               (when-let [layer-id (->verbatim-layer by-precedence
                                                                     rule-name annotation)]
                                 [rule-name layer-id])))
                       annotations)
        default (->dominant (vals layer-of))]
    {:layers (vec (:layers merged))
     :verbatim {:default default
                :except (into (sorted-map)
                              (remove (fn [[_ layer-id]] (= layer-id default)))
                              layer-of)}
     :annotations (into (sorted-map) (remove (comp layer-of key)) annotations)
     :provenance (let [provenance (:provenance merged)]
                   (->compact-provenance provenance annotations
                                         (->provenance-template provenance
                                                                (keys layer-of))))}))

;; ===========================================================================
;; expand
;; ===========================================================================

(s/defn <-compact :- schema/MergedAnnotations
  "The inverse of `->compact`: references resolved against `layers`, provenance
  unfolded. `layers` must be the same file-backed stack, in the same fold order.

  A reference to a layer that is no longer on disk resolves to nothing and the
  rule drops out, rather than the read failing — the same posture as the rest of
  the store, where a missing artifact is an absent one.

  The work is `layout/expand-merged-annotations`; what this adds is the schema on
  each end. The decode itself has to run under babashka too — see `layout` — so
  it lives in the namespace that has no dependencies, and this is the JVM's
  validated door onto it."
  [compacted :- schema/CompactMergedAnnotations
   layers :- schema/LayerAnnotationsStack]
  (layout/expand-merged-annotations compacted layers))
