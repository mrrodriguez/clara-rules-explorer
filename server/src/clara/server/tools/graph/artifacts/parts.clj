(ns clara.server.tools.graph.artifacts.parts
  "Split a slim `rulebase-analysis` into the files its readers actually open.

  One 11.9MB value answers every question at the price of the largest one. Its
  readers do not divide by production — they divide by *which fields* they need,
  and there are three such groups:

    scan            every production, a narrow projection. Types, producers,
                    consumers, a summary — anything that walks the whole
                    rulebase looking at a few fields. 1.9MB.
    condition scan  every production's `:lhs`, and nothing else. A polarity or
                    `:bindings` reader, which classifies every condition of
                    every rule. 4.3MB.
    detail          one named production, the rest of its record. 3.0MB.

  So `->parts` writes six files instead of one, and a scan reads 1.9MB where it
  used to read 15.0MB.

  ## `:lhs` is scanned, not detail

  The one field whose access pattern is not obvious from its shape. It is the
  biggest per-production value and no offline report subcommand touches it,
  which reads as detail — but a condition-level analysis classifies every
  condition of every rule, which is a full-corpus scan. Filed with `:rhs-form`
  it would cost that reader 3.0MB it never looks at, so it gets
  `production-conditions.edn` to itself.

  ## Unknown keys: one rule per level

  A field added to a *production* lands in the scanned index: `condition-keys`
  and `detail-keys` are closed, the index is everything else, so the file grows
  rather than the field vanishing — the direction
  `clara.server.tools.graph.artifacts.slim` also chooses to fail in.

  A new *top-level* key has no such home. There are exactly six files and none
  of them is \"the rest\", so `->parts` refuses one it cannot place rather than
  dropping it silently — the same posture as `->flat-productions` refusing a
  rule/query name collision. The fix is always a decision in `slim`: drop the
  key deliberately, with a `:slim :recover` entry saying where to get it, or
  give it a part here.

  `<-parts` is the exact inverse, and `artifacts.parts-test` pins the round
  trip."
  (:require
   [clara.server.tools.graph.artifacts.layout :as layout]
   [clara.server.tools.graph.artifacts.schema :as schema]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(def part-files
  "`schema/AnalysisPartKey` → its filename under the analysis directory;
  `clara.server.tools.graph.artifacts.store` reads them from here. Defined in
  `layout/part-files`, which the babashka report script also loads, so the two
  cannot disagree on a filename."
  layout/part-files)

(def condition-keys
  "The production keys that go to `production-conditions.edn`."
  #{:lhs})

(def detail-keys
  "The production keys that go to `production-details.edn` — the RHS and the
  documentation. Everything a reader wants only once it has picked a
  production."
  #{:rhs-form :doc :props :notes :params})

(def ^:private production-maps
  "The two top-level maps holding productions. `:rules` and `:queries` are kept
  apart in the index because that distinction is the `:type` a cross-reference
  used to carry, and nothing else on the record says which a production is."
  [:rules :queries])

(def ^:private graph-keys
  "Top-level keys that get a file of their own, whole."
  [:fact-types :dep-graph])

(def ^:private meta-keys
  "Top-level keys that are neither productions nor a graph — small, and read
  together with the index."
  #{:slim :unresolved})

(def placeable-top-level-keys
  "Every top-level key `->parts` has a file for. A slim analysis carrying
  anything else is refused — see the ns docstring."
  (into meta-keys (concat production-maps graph-keys)))

;; ===========================================================================
;; split
;; ===========================================================================

(defn- ->projection
  "`{production-name projection}` over one production map, keeping only entries
  the projection is non-empty for."
  [productions select]
  (into (sorted-map)
        (keep (fn [[production-name record]]
                (let [projected (select record)]
                  (when (seq projected) [production-name projected]))))
        productions))

(defn- ->flat-productions
  "`:rules` and `:queries` as one map. Production names are fully-qualified var
  names and so are unique across the two, which is what lets
  `production-conditions.edn` and `production-details.edn` be flat and keep
  `production-index.edn` as the only file recording which of the two a
  production is.

  Unique in every artifact measured, but not enforced anywhere — and a collision
  would not fail, it would hand one production the other's `:lhs`. Cheap to rule
  out, so it is ruled out, the same way
  `clara.server.tools.graph.artifacts.compact` refuses to write a merge missing
  a layer's rules."
  [analysis]
  (let [[rules queries] (map analysis production-maps)
        collisions (filterv rules (keys queries))]
    (when (seq collisions)
      (throw (ex-info (str "Cannot split the analysis: a name is both a rule and a query. "
                           "The conditions and details files are keyed by production name "
                           "alone, so one would silently overwrite the other.")
                      {:colliding-names (vec (sort collisions))
                       :collision-count (count collisions)})))
    (merge rules queries)))

(defn- ->flat-projection
  "`select` over `:rules` and `:queries` together."
  [analysis select]
  (->projection (->flat-productions analysis) select))

(defn- reject-unplaceable-keys!
  "Throw when `analysis` carries a top-level key no part would hold.

  There are six files and none of them is a catch-all, so such a key is written
  nowhere and `<-parts` cannot give it back — which would make both this
  namespace's round-trip claim and `slim`'s `:slim :dropped` / `:slim :recover`
  promise untrue at the same time, silently. Failing at the first regeneration
  instead is the whole point."
  [analysis]
  (let [unplaceable (into (sorted-set) (remove placeable-top-level-keys) (keys analysis))]
    (when (seq unplaceable)
      (throw (ex-info (str "Cannot split the analysis: no part holds "
                           (pr-str (vec unplaceable)) ". Every top-level key needs a file or a "
                           "deliberate drop — add it to "
                           "clara.server.tools.graph.artifacts.slim/dropped-top-level-keys with a "
                           "`recovery` entry, or give it a part here.")
                      {:unplaceable-keys (vec unplaceable)
                       :placeable-keys (vec (sort placeable-top-level-keys))})))))

(s/defn ->parts :- schema/AnalysisParts
  "`analysis` as the map of parts
  `clara.server.tools.graph.artifacts.store/write-analysis-parts!` writes.

  **nil is not `{}` here.** A section the analysis does not have at all comes
  back nil; one it has and is empty comes back `{}`. That is the only thing
  separating them once they are files, and `<-parts` reads it to rebuild exactly
  the key set it was given — `clara.server.tools.graph.artifacts.slim` goes to
  the trouble of leaving a partial analysis partial, so this must not quietly
  complete it. The writer turns a nil part into an empty file, so the directory
  still always has the same six files and a reader never branches on which
  exist.

  A top-level key no part holds is refused rather than dropped — see the ns
  docstring.

  Sorted at every level. Map iteration order is already a function of the key
  set rather than of insertion, so this buys no determinism that was missing —
  it makes the files bisectable and greppable, which a 4MB EDN file otherwise
  is not."
  [analysis :- schema/RulebaseAnalysis]
  (reject-unplaceable-keys! analysis)
  (let [strip #(apply dissoc % (concat condition-keys detail-keys))
        section (fn [k] (when (contains? analysis k) (into (sorted-map) (get analysis k))))]
    {:index (into (sorted-map)
                  (keep (fn [k]
                          (when (contains? analysis k)
                            [k (->projection (get analysis k) strip)])))
                  production-maps)
     :conditions (->flat-projection analysis #(select-keys % condition-keys))
     :details (->flat-projection analysis #(select-keys % detail-keys))
     :fact-types (section :fact-types)
     :dep-graph (section :dep-graph)
     :meta (into (sorted-map) (select-keys analysis meta-keys))}))

;; ===========================================================================
;; rejoin
;; ===========================================================================

(s/defn <-parts :- schema/RulebaseAnalysis
  "The whole analysis back, from the parts `->parts` produced — exactly, key set
  included. A nil section is one the analysis never had and is not rebuilt; an
  empty one is rebuilt empty.

  Nothing in the write path needs it — `merged-rulebase-analysis` is write-only,
  and every external reader opens the one part its question lives in. It exists
  so the split is testable as a round trip, and so a caller that genuinely wants
  the whole value has one honest way to get it rather than six ad hoc ones."
  [{:keys [index conditions details fact-types dep-graph meta] :as _parts}
   :- schema/AnalysisParts]
  (let [rejoin (fn [[production-name record]]
                 [production-name (merge record
                                         (get conditions production-name)
                                         (get details production-name))])]
    (-> (into {} (keep (fn [[k v]] (when (some? v) [k v])))
              {:fact-types fact-types :dep-graph dep-graph})
        (merge meta)
        (merge (update-vals index #(into (sorted-map) (map rejoin) %))))))
