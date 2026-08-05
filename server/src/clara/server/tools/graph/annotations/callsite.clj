(ns clara.server.tools.graph.annotations.callsite
  "Callsite format and identity for rule annotations (see
   docs/rule-annotations.md, \"Dynamic Call-Site Capture and Resolution\").

   A callsite entry records one `insert!`/`retract!` argument form found by
   the analyzer, plus its resolution conclusion.  Identity is only needed
   within one rule and one dimension — the rule name and dimension key
   already address the callsites vector.  Ids are ns:ctor:hash:ordinal, where
   the hash covers [ns-name-sym constructor-sym source-str] and the ordinal
   is the index within the duplicate group (siblings sharing the full basis),
   so adding or removing unrelated callsites does not renumber the group."
  (:require [schema.core :as s]
            [clara.server.tools.graph.analyze.callsite :as callsite]))

(s/defschema LayerId (s/cond-pre s/Keyword s/Str))

(s/defschema CallsiteId (s/named s/Str "CallsiteId"))

(s/defschema Resolution
  "Three-valued resolution vocabulary, used per-callsite and per-dimension:
   :full (everything known), :partial (some knowledge, not complete), :none
   (nothing known).  The analyzer emits only :full and :none; :partial is
   reachable through curation."
  (s/enum :none :partial :full))

(s/defschema ResolutionEvidence
  "Why a callsite's conclusion is what it is.  Open map; `:note` is the only
   key the library reads, and only to display it."
  {(s/optional-key :note) s/Str
   s/Any s/Any})

(s/defschema CallsiteEntry
  "One callsite in a layer or merged annotation.  `:callsite-id` is optional
   on input — ids are derived on read for entries that omit one (see
   `derive-callsite-ids`) — and always present after `assign-callsite-ids`.
   `:from-layer` and `:dangling?` are computed by the merge; authored values
   are ignored."
  {(s/optional-key :callsite-id) CallsiteId
   ;; discovery — only the analyzer produces these
   (s/optional-key :source-str) s/Str
   (s/optional-key :ns-name-sym) s/Symbol
   (s/optional-key :filename) s/Str
   (s/optional-key :constructor-sym) s/Symbol
   (s/optional-key :via) callsite/ViaChain
   (s/optional-key :fact-type) s/Any
   (s/optional-key :fact-type-spec) {s/Keyword s/Any}
   ;; conclusion — analyzer or curator
   :status Resolution
   (s/optional-key :resolved-types) [s/Any]
   (s/optional-key :resolution-evidence) ResolutionEvidence
   ;; computed by the merge — never authored
   (s/optional-key :from-layer) LayerId
   (s/optional-key :dangling?) s/Bool})

(s/defschema DetectionMap
  "One dimension's callsite audit trail.  `:resolution` is derived from the
   callsites and never taken from a layer, so it is optional on input and
   always present in merged output.  `:fact-instance-derived-types` is the
   session-enrichment channel (analyze/enrich-annotations-from-session) —
   types observed in working memory rather than resolved from source; such
   maps carry no `:callsites` and merge as opaque values."
  {(s/optional-key :callsites) [CallsiteEntry]
   (s/optional-key :resolution) Resolution
   (s/optional-key :fact-instance-derived-types) [s/Any]})

(def detection-keys
  "The two annotation keys that hold DetectionMaps."
  [:clara-rules/dynamic-insert-types-detected
   :clara-rules/dynamic-retract-types-detected])

(defn aggregate-resolution
  "Dimension-level resolution from a callsite vector: no callsites → nil (the
   dimension is absent), all :full → :full, all :none → :none, otherwise
   :partial.  Quarantined (`:dangling?`) callsites are excluded.  The
   aggregation itself is shared with the analyzer as
   `callsite/resolution-status`."
  [callsites]
  (callsite/resolution-status (remove :dangling? callsites)))

;; ---------------------------------------------------------------------------
;; Callsite identity
;; ---------------------------------------------------------------------------

(defn- sha256-hex
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest digest (.getBytes s "UTF-8"))))))

(defn- ctor-short-name
  [ctor-sym]
  (if ctor-sym (name ctor-sym) "-"))

(defn- callsite-basis
  "The id basis: namespace, constructor, and source text.  Deliberately
   excluded: :status/:resolved-types (what curation changes), :via (shifts
   when an unrelated helper is renamed), :row/:col (churn on every edit
   above), :filename (equivalent to :ns-name-sym, less legible)."
  [c]
  [(:ns-name-sym c) (:constructor-sym c) (:source-str c)])

(defn callsite-id
  "Content-hash identity for a callsite: ns:ctor:hash — the namespace, the
   constructor's short name (`-` for boundary-path callsites), and the first
   8 hex chars of SHA-256 over (pr-str [ns-name-sym constructor-sym
   source-str]).  No ordinal: ordinals require the sibling group — see
   `assign-callsite-ids`."
  [c]
  (let [[ns-name-sym constructor-sym _] (callsite-basis c)]
    (format "%s:%s:%s"
            ns-name-sym
            (ctor-short-name constructor-sym)
            (subs (sha256-hex (pr-str (callsite-basis c))) 0 8))))

(defn assign-callsite-ids
  "Assigns :callsite-id to every entry of one rule+dimension callsite vector
   — the only place ordinals can be computed.  Entries sharing the full basis
   (namespace, constructor, source text) form a duplicate group and receive
   0-based ordinals *within the group*, so adding or removing unrelated
   callsites in the same rule does not renumber it.  Collisions are detected
   at emission and resolved by lengthening the hash: ids are unique by
   construction, not by probability.  Entries must carry :ns-name-sym and
   :source-str."
  [callsites]
  (let [hashes (mapv (comp sha256-hex pr-str callsite-basis) callsites)
        prefixes (mapv (fn [[ns-name-sym constructor-sym _]]
                         (format "%s:%s" ns-name-sym (ctor-short-name constructor-sym)))
                       (map callsite-basis callsites))
        ;; occurrence index within the basis group (hash == basis, up to the
        ;; 64-hex collision that the lengthening loop below would also catch)
        ordinals (second (reduce (fn [[counts out] h]
                                   (let [o (get counts h 0)]
                                     [(assoc counts h (inc o)) (conj out o)]))
                                 [{} []]
                                 hashes))]
    (loop [len 8]
      (let [ids (mapv (fn [prefix h o]
                        (format "%s:%s:%d" prefix (subs h 0 (min len (count h))) o))
                      prefixes hashes ordinals)]
        (if (or (empty? ids) (apply distinct? ids) (>= len 64))
          (mapv (fn [c id] (assoc c :callsite-id id)) callsites ids)
          (recur (inc len)))))))

(defn has-id-basis?
  "True when a callsite entry carries enough of the id basis to derive one."
  [c]
  (and (:ns-name-sym c) (:source-str c)))

(defn derive-callsite-ids
  "Derives ids for entries that omit one (hand-written layers need not
   compute hashes as long as they supply enough of the basis).  Entries
   carrying an id keep it; ordinals are computed over all basis-carrying
   siblings so a pre-assigned sibling still occupies its group position."
  [callsites]
  (if (every? :callsite-id callsites)
    callsites
    (let [new-ids (into clojure.lang.PersistentQueue/EMPTY
                        (map :callsite-id)
                        (assign-callsite-ids (filterv has-id-basis? callsites)))]
      (first (reduce (fn [[out q] c]
                       (cond
                         (not (has-id-basis? c)) [(conj out c) q]
                         (:callsite-id c) [(conj out c) (pop q)]
                         :else [(conj out (assoc c :callsite-id (peek q))) (pop q)]))
                     [[] new-ids]
                     callsites)))))

(defn derive-ids-in-rule-annotation
  "Derives callsite ids for both detection dimensions of one rule annotation."
  [rule-ann]
  (reduce (fn [ra k]
            (if-let [callsites (:callsites (get ra k))]
              (assoc-in ra [k :callsites] (derive-callsite-ids callsites))
              ra))
          rule-ann
          detection-keys))
