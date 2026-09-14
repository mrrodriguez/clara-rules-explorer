(ns clara.server.tools.graph.artifacts.digest
  "`rulebase-analysis-digest.edn`: the one analysis artifact small enough to read whole.

  `merged-rulebase-analysis/` is ~12MB even after
  `clara.server.tools.graph.artifacts.slim` and
  `clara.server.tools.graph.artifacts.parts` are done with it, and no
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

  That is the same line `clara.server.tools.graph.artifacts.slim` holds, and for
  the same reason: the moment this namespace starts deciding how to *read* a value
  rather than how many there are, it has forked the analysis's semantics
  and will drift from them silently."
  (:require
   [clara.server.tools.graph.artifacts.schema :as schema]
   [clara.server.tools.graph.core :as graph-core]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(def ^:private flag-keys
  "The per-rule booleans the analysis sets. Counted, never recomputed."
  [:source-rule :sink-rule :unlinked-rule :no-output-types])

(s/defn ^:private get-names-with-flag :- #{schema/RuleName}
  [rules :- {schema/RuleName {s/Keyword s/Any}}
   k :- s/Keyword]
  (into (sorted-set) (keep (fn [[rule-name r]] (when (get r k) rule-name))) rules))

(def ^:private more-note
  "The orienting paragraph written into `:more`, for a reader who has the files
  and not the classpath. Everything it says about what is missing and why points
  at `clara.server.tools.graph.artifacts.slim`'s `:slim :recover`, which is the
  one place those rules are stated."
  (str "merged-rulebase-analysis/ is a DIRECTORY split by what a reader opens: "
       "production-index.edn (:name :ns :lhs-types :insert-types :retract-types + flags — "
       "the scan projection, and the one most questions need), "
       "production-conditions.edn (:lhs), production-details.edn (:rhs-form :doc :props), "
       "fact-types.edn, dep-graph.edn (:upstream), meta.edn (:slim :unresolved). "
       "Open the one part your question lives in — never cat the directory. "
       "Everything is keyed by and cross-referenced by :name; ids and expanded "
       "reference records are not on disk. Neither is any reverse direction — "
       ":downstream, :descendants, :used-by-*, :inserted-by-rules and "
       ":retracted-by-rules are each the inverse of a key that IS kept. "
       ":slim :recover in meta.edn names the reconstruction for every one of "
       "them; GET /v1/fact-types/:fq-name serves them ready-made. "
       "merged-annotations.edn is stored by REFERENCE to the layer files beside it — "
       "a rule one layer accounts for whole is named, not restated — so read it with "
       "clara.server.tools.graph.artifacts.store/read-merged-annotations, which expands it. "
       ":nodes, :lhs-form and anything needing working memory are answered only by serving the "
       "analysis over a live session: GET "
       "/v1/rules/:fq-name | /v1/fact-types/:fq-name | /v1/rulebase-analysis | /v1/session/*."))

(s/defn ->rulebase-analysis-digest :- schema/RulebaseAnalysisDigest
  "A digest of the **pre-slim** `analysis`.

  Pre-slim on purpose. `:nodes` is one of the counts, and a digest whose
  correctness depended on what `clara.server.tools.graph.artifacts.slim`
  happened to leave behind would be exactly the coupling both namespaces exist to
  avoid. `clara.server.tools.graph.artifacts.flow/merge-persisted!` therefore
  builds this from the analysis it just derived, before slimming it.

  `:unlinked-rules` and `:no-output-rules` carry names rather than counts because
  they are the two work lists a reader acts on; everything else is a count.

  `:session-hint` is appended to `:more`. The rest of that paragraph is true of
  any artifact set, but *how you get a live session to answer what the files
  omit* is the caller's flow and nobody else's — a host that drives this from a
  REPL façade says so in its own terms, and one that does not says nothing.

  Every section defaults to empty.
  `schema/RulebaseAnalysis` makes them all optional and
  `clara.server.tools.graph.artifacts.slim` is explicitly required to leave a
  partial analysis partial, so a digest of one has to answer with zeros rather
  than dying — an absent `:queries` otherwise reaches `(comp nil :name)` and
  throws an NPE that names neither the key nor the caller."
  ([analysis :- schema/RulebaseAnalysis]
   (->rulebase-analysis-digest analysis nil))
  ([{:keys [rules queries dep-graph unresolved]
     :or {rules {} queries {} dep-graph {} unresolved []}
     :as analysis} :- schema/RulebaseAnalysis
    session-hint :- (s/maybe s/Str)]
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
    :unresolved (vec unresolved)
    :more (cond-> more-note
            (seq session-hint) (str " " session-hint))}))
