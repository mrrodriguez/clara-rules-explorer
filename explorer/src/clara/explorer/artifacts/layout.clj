(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.layout
  "What the persisted artifacts are called, and how to read back the one of them
  that is not stored literally.

  The one definition every reader shares: the JVM artifact namespaces and the
  babashka report load this namespace rather than restating its contents, so a
  filename, the layer fold order, or the `merged-annotations.edn` decode cannot
  drift between the two. What belongs here is narrow — a name or a pure function
  both sides need, in plain Clojure. Schema validation, IO, and anything touching
  a session stay in
  `clara.explorer.artifacts.store` /
  `clara.explorer.artifacts.compact` /
  `clara.explorer.artifacts.parts`, which wrap what is here.

  The JVM side re-exposes these under the namespace that owns the concept —
  `clara.explorer.artifacts.store/artifact-files`,
  `clara.explorer.artifacts.parts/part-files`,
  `clara.explorer.artifacts.schema/detection-keys-by-dimension` — so
  callers keep reading the name in its natural home. Those are aliases of these;
  this namespace is the definition."
  (:require
   [clojure.string :as str]))

;; ===========================================================================
;; filenames
;; ===========================================================================

(def artifact-files
  "Artifact filenames, by role. The roles are
  `clara.explorer.artifacts.schema/ArtifactKey`.

  `:rulebase-analysis` is a DIRECTORY, not a file — see `part-files`.

  The last two are registry-level, not per-unit: a federated index and its
  digest are written beside the units, not inside one."
  {:auto "auto-gen-annotations.edn"
   :memory "memory-annotations.edn"
   :agent "agent-annotations.edn"
   :merged "merged-annotations.edn"
   :rulebase-analysis "merged-rulebase-analysis"
   :rulebase-analysis-digest "rulebase-analysis-digest.edn"
   :manifest "rules-inspect-manifest.edn"
   :registry-index "registry-index.edn"
   :registry-digest "registry-digest.edn"})

(def unit-artifact-files
  "The artifact roles a single unit has — `artifact-files` minus the
  registry-level pair, which is written beside units rather than inside one.
  `clara.explorer.artifacts.registry` reads presence off this subset,
  and `clara.explorer.artifacts.manifest` lists it, so a registry
  index never shows up as a unit's own artifact."
  (select-keys artifact-files
               [:auto :memory :agent :merged :rulebase-analysis
                :rulebase-analysis-digest :manifest]))

(def part-files
  "`clara.explorer.artifacts.schema/AnalysisPartKey` → its filename
  inside the `merged-rulebase-analysis/` directory. See
  `clara.explorer.artifacts.parts` for why the analysis is split this
  way."
  (array-map
   :index "production-index.edn"
   :conditions "production-conditions.edn"
   :details "production-details.edn"
   :fact-types "fact-types.edn"
   :dep-graph "dep-graph.edn"
   :meta "meta.edn"))

;; ===========================================================================
;; layer identity
;; ===========================================================================

(def generated-layer-id
  "`:id` of the deterministically generated layer. The explorer's own
  `--generate-analysis` stamps the same keyword on its output, and it is the
  honest statement of provenance: everything in that file came out of
  `clara.explorer.analyze`. Nothing in the library privileges the id —
  it is a marker for humans and tooling."
  :clara.explorer.analyze/generated)

(def memory-layer-id
  "`:id` of the memory-derived layer: fact types observed in a fired session's
  working memory that static analysis did not find. Its own layer rather than an
  edit to the generated one, so `:provenance` can say which types were *proven at
  runtime* versus read out of source.

  `:memory` rather than `:working-memory` to match the explorer's own
  `clara.explorer.analyze/memory`, the artifact role key, and
  `memory-annotations.edn` — one word for the channel, everywhere."
  :memory)

(def agent-layer-id
  "`:id` of the agent-curated overlay layer. Appears in the merged artifact's
  `:provenance` and on each callsite entry it claimed, as `:from-layer`."
  :agent)

(def layer-artifacts
  "The artifacts that are layer files
  (`clara.explorer.artifacts.schema/LayerArtifactKey`), and the `:id`
  each one carries. The keys of this map are exactly the fold order, lowest
  precedence first: generated, then working-memory enrichment over it, then the
  curated overlay over both."
  (array-map :auto generated-layer-id
             :memory memory-layer-id
             :agent agent-layer-id))

(def detection-keys-by-dimension
  "Detection dimension → the annotation key holding its detection map. Three
  callers walk callsites by dimension —
  `clara.explorer.artifacts.overlay`,
  `clara.explorer.artifacts.compact`, and the babashka report — and
  all three must agree on which keys those are."
  {:insert :clara-rules/dynamic-insert-types-detected
   :retract :clara-rules/dynamic-retract-types-detected})

;; ===========================================================================
;; decoding merged-annotations.edn
;;
;; Only the EXPANSION half lives here. Compaction needs the fold's own layer
;; stack and is a write-time concern, so it stays in
;; `clara.explorer.artifacts.compact`; every reader, JVM or babashka,
;; needs the inverse.
;; ===========================================================================

(defn stamp-annotation
  "`annotation` as the fold would have emitted it had `layer-id` been the only
  layer to touch the rule: `:from-layer` set on every callsite.

  The fold stamps each callsite with the layer that declared its conclusion, and
  layer files carry no `:from-layer` of their own — over a large real rulebase
  that stamp is the *only* difference between the merge and the generated layer
  for 3,315 of the 3,403 rules that match.

  Used by `clara.explorer.artifacts.compact` in both directions, so
  whatever it does, it does symmetrically."
  [layer-id annotation]
  (reduce (fn [ann detection-key]
            (cond-> ann
              (seq (get-in ann [detection-key :callsites]))
              (update-in [detection-key :callsites]
                         (partial mapv #(assoc % :from-layer layer-id)))))
          annotation
          (vals detection-keys-by-dimension)))

(defn expand-rule-provenance
  "One rule's origins as expansion rebuilds them: the template's entry for each
  key the rule's annotation has. `clara.explorer.artifacts.compact`
  uses this on the way out too, so compaction can check its own work against the
  exact thing expansion will do."
  [template annotation]
  (into (sorted-map)
        (keep (fn [k] (when-let [entry (find template k)] [k (val entry)])))
        (keys annotation)))

(defn expand-annotations
  "The merged `:annotations` map, references resolved against `layers`.

  `verbatim` is the stored `{:default :except}`: a rule not named in `:except`
  came from `:default`. A reference to a layer that is no longer on disk resolves
  to nothing and the rule drops out, rather than the read failing — the same
  posture as the rest of the store, where a missing artifact is an absent one."
  [{:keys [default except]} inlined layers]
  (let [by-id (into {} layers)]
    (into (into (sorted-map)
                (keep (fn [rule-name]
                        (let [layer-id (get except rule-name default)]
                          (when-let [annotation (get-in by-id [layer-id rule-name])]
                            [rule-name (stamp-annotation layer-id annotation)]))))
                (into (sorted-set)
                      (comp (mapcat (comp keys second))
                            (remove inlined))
                      layers))
          inlined)))

(defn expand-provenance
  "Each rule's origins: its own entry when it has one, else rebuilt from the
  per-key template. `:except` entries are whole, never overlays, so there is no
  order to get wrong."
  [{:keys [verbatim except]} annotations]
  (into {}
        (map (fn [[rule-name annotation]]
               [rule-name (or (get except rule-name)
                              (expand-rule-provenance verbatim annotation))]))
        annotations))

;; ===========================================================================
;; unit placement
;;
;; The unit → directory mapping both the JVM registry and the babashka
;; `status` report need: `<root>/<repo>/` for a mainline unit, or
;; `<root>/_variants/<repo>/<axis>=<value>/…/ref=<ref>/` for a variant. Pure
;; `/`-joins — every downstream use goes back through file IO that accepts them
;; on any platform — so both sides share this instead of each joining segments
;; its own way.
;;
;; The variant directory is named by the *encoded* `[axis value]` pairs in
;; `:variant`, always ending in `[:ref …]`. Encoding and decoding live here
;; (public) so a host building its own paths from values — e.g. a composed
;; unit's directory name — uses the same escaping rather than inventing its own.
;; Nothing outside this namespace decodes a path.
;; ===========================================================================

(def variants-subdir
  "The single root-level directory holding every repo's variant units, so
  `<root>/<repo>/` holds exactly the mainline unit and a host that keeps
  variants out of version control ignores one path."
  "_variants")

(def ^:private reserved-value-chars
  "The four characters percent-encoded in a variant value, mapped to their
  encodings. `%` is the escape character, which is what makes decoding
  unambiguous; `/` is the level separator; `@` the unit-key separator; `+` the
  join a host uses to list several values in one segment."
  {\% "%25", \/ "%2F", \@ "%40", \+ "%2B"})

(defn encode-value
  "A variant segment value, percent-encoding the four reserved characters and
  passing everything else through. The value is a non-empty string; a host that
  keeps several sub-values in one segment joins them with `+` first."
  [value]
  (str/escape (str value) reserved-value-chars))

(defn decode-value
  "The inverse of `encode-value`: an encoded segment value back to the original
  string."
  [value]
  (str/replace (str value) #"%25|%2F|%40|%2B"
               {"%25" "%", "%2F" "/", "%40" "@", "%2B" "+"}))

(defn variant->path
  "Encode a `:variant` vector (`[[:region \"eu\"] [:ref \"main\"]]`) as its
  directory path under `_variants/<repo>/`: one `<axis>=<encoded value>` segment
  per pair, `/`-joined. Axis names are keywords here; a reader recovers the
  vector with `path->variant`."
  [variant]
  (->> variant
       (map (fn [[axis value]]
              (format "%s=%s" (name axis) (encode-value value))))
       (str/join "/")))

(defn path->variant
  "The inverse of `variant->path`: a variant directory path back to its
  `:variant` vector. Each segment splits on its first `=`, so a value may itself
  contain `=`."
  [path]
  (into []
        (comp (remove str/blank?)
              (map (fn [segment]
                     (let [i (str/index-of segment "=")]
                       (when-not i
                         (throw (ex-info (format "Variant segment has no '=': %s" segment)
                                         {:segment segment :path (str path)})))
                       [(keyword (subs segment 0 i))
                        (decode-value (subs segment (inc i)))]))))
        (str/split (str path) #"/")))

(defn write-variant
  "The full decoded `:variant` for a write: the caller's host axes plus the
  derived `[:ref …]` pair, or nil when the run is mainline (`canonical?` and the
  ref is the remote's default branch). `git-info` is the analyzed checkout's git
  info, as `clara.explorer.artifacts.shared.git/get-git-info` returns it; the
  ref is its `:branch`, else its `:sha-short`. Throws when there is no checkout
  to read a ref from — a run with no repo names an explicit `:dir` instead."
  [host-variant canonical? git-info]
  (when-not git-info
    (throw (ex-info "Cannot derive a variant from a non-git checkout: pass :dir"
                    {:canonical? canonical?})))
  (let [ref (or (:branch git-info) (:sha-short git-info))]
    (when-not (and canonical? (= ref (:default-branch git-info)))
      (into (vec host-variant) [[:ref ref]]))))

(defn- strip-trailing-slashes
  "`s` with trailing `/`s removed, so joining never doubles a separator."
  [s]
  (str/replace (str s) #"/+$" ""))

(defn ->unit-dir
  "Artifact dir for a unit: explicit `:dir`, else `<:root>/<:repo>` (mainline),
  or `<:root>/_variants/<:repo>/<variant path>` for a variant. `:variant` is the
  full ordered vector of `[axis value]` pairs, always ending in `[:ref …]` — a
  mainline unit passes none, and an explicit `:dir` wins outright over both."
  [{:keys [root dir repo variant]}]
  (cond
    dir (strip-trailing-slashes dir)
    (seq variant) (format "%s/%s/%s/%s"
                          (strip-trailing-slashes root)
                          variants-subdir repo (variant->path variant))
    :else (format "%s/%s" (strip-trailing-slashes root) repo)))

(defn ->default-root
  "Registry-root guess for a unit dir: `dir` with `/<repo>` (or
  `/_variants/<repo>/<variant path>`, when the unit has one) stripped from the
  end. Falls back to `dir` unchanged when it isn't suffixed that way — e.g. a
  unit written to an explicit `:dir` — so composed-source lookups under it
  report `missing` rather than throwing. `repo`/`variant` are the manifest's
  top-level `:repo` and `:variant`."
  [dir {:keys [repo variant]}]
  (let [trimmed (strip-trailing-slashes dir)
        suffix (if (seq variant)
                 (format "/%s/%s/%s" variants-subdir repo (variant->path variant))
                 (format "/%s" repo))]
    (if (and (> (count trimmed) (count suffix))
             (str/ends-with? trimmed suffix))
      (subs trimmed 0 (- (count trimmed) (count suffix)))
      trimmed)))

(defn expand-merged-annotations
  "`merged-annotations.edn` as a whole merge — a
  `clara.explorer.artifacts.schema/MergedAnnotations`, from the
  `clara.explorer.artifacts.schema/CompactMergedAnnotations` that is
  actually on disk.

  `layers` is the file-backed stack in fold order, as `[[layer-id annotations]
  …]`. The file stores a rule by REFERENCE — the id of a layer holding that exact
  annotation — whenever one does, which is ~99% of them, so reading the merge
  costs opening the layers beside it. Callsites come back WHOLE, `:via` and
  `:source-str` included, because they come off the layer.

  `clara.explorer.artifacts.compact/<-compact` is this with schema
  validation on both ends."
  [{:keys [verbatim annotations provenance] :as compacted} layers]
  (let [expanded (expand-annotations verbatim annotations layers)]
    {:layers (:layers compacted)
     :annotations expanded
     :provenance (expand-provenance provenance expanded)}))
