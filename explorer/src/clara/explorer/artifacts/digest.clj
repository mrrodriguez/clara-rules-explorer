(ns clara.explorer.artifacts.digest
  "`rulebase-analysis-digest.edn`: the one analysis artifact small enough to read whole.

  `merged-rulebase-analysis/` is ~12MB even after
  `clara.explorer.artifacts.slim` and
  `clara.explorer.artifacts.parts` are done with it, and no
  amount of trimming makes a per-rule artifact for a 3,371-rule ruleset fit in a
  context window. Size was never the thing to fix — *having somewhere to start*
  was. This is that: ~16KB answering how big, which namespaces, what is unlinked,
  what is still unresolved, and where to go next.

  ## Counting only

  Every value here is `count`, `frequencies`, `filter`, `sort`, or `group-by`
  over a field the analysis already computed — `:ns`, and the boolean
  `:source-rule` / `:sink-rule` / `:unlinked-rule` / `:no-output-types` flags —
  plus `graph-core/get-rulebase-counts` verbatim.
  Nothing interprets a fact type, a condition kind, or a hierarchy.

  That is the same line `clara.explorer.artifacts.slim` holds, and for
  the same reason: the moment this namespace starts deciding how to *read* a value
  rather than how many there are, it has forked the analysis's semantics
  and will drift from them silently."
  (:require
   [clara.explorer.artifacts.schema :as schema]
   [clara.explorer.core :as graph-core]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(def ^:private flag-keys
  "The per-rule booleans the analysis sets. Counted, never recomputed."
  [:source-rule :sink-rule :unlinked-rule :no-output-types])

(s/defn ^:private get-names-with-flag :- #{schema/RuleName}
  [rules :- {schema/RuleName {s/Keyword s/Any}}
   k :- s/Keyword]
  (into (sorted-set) (keep (fn [[rule-name r]] (when (get r k) rule-name))) rules))

(s/defn ->rulebase-analysis-digest :- schema/RulebaseAnalysisDigest
  "A digest of the **pre-slim** `analysis`.

  Pre-slim on purpose. `:nodes` is one of the counts, and a digest whose
  correctness depended on what `clara.explorer.artifacts.slim`
  happened to leave behind would be exactly the coupling both namespaces exist to
  avoid. `clara.explorer.artifacts.flow/merge-persisted!` therefore
  builds this from the analysis it just derived, before slimming it.

  `:unlinked-rules` and `:no-output-rules` carry names rather than counts because
  they are the two work lists a reader acts on; everything else is a count.

  Every section defaults to empty.
  `schema/RulebaseAnalysis` makes them all optional and
  `clara.explorer.artifacts.slim` is explicitly required to leave a
  partial analysis partial, so a digest of one has to answer with zeros rather
  than dying — an absent `:queries` otherwise reaches `(comp nil :name)` and
  throws an NPE that names neither the key nor the caller."
  ([{:keys [rules queries dep-graph unresolved]
     :or {rules {} queries {} dep-graph {} unresolved []}
     :as analysis} :- schema/RulebaseAnalysis]
   {:summary (graph-core/get-rulebase-counts analysis)
    :counts {:node-count (count (:nodes analysis))
             :dep-edge-count (reduce + 0 (map (comp count :upstream) (vals dep-graph)))
             :unresolved-count (count unresolved)}
    :namespaces (into (sorted-map)
                      (map (fn [[ns-name productions]]
                             [ns-name {:rules (count (filter (comp rules :name) productions))
                                       :queries (count (filter (comp queries :name) productions))}]))
                      (group-by :ns (concat (vals rules) (vals queries))))
    :flag-counts (into (sorted-map)
                       (map (fn [k] [k (count (filter #(get % k) (vals rules)))]))
                       flag-keys)
    :unlinked-rules (get-names-with-flag rules :unlinked-rule)
    :no-output-rules (get-names-with-flag rules :no-output-types)
    :unresolved (vec unresolved)}))
