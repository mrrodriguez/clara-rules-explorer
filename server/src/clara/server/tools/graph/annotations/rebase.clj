(ns clara.server.tools.graph.annotations.rebase
  "Layer rebasing across namespace renames.

   Renaming or moving a namespace dangles every curated callsite in it —
   correct, but tedious for a bulk rename.  `rebase-layer` remaps a layer
   across a known old→new namespace mapping and recomputes callsite ids, so
   the rebased layer overlays freshly generated discovery again."
  (:require [clojure.string :as str]
            [clara.server.tools.graph.annotations.callsite :as ann.callsite]
            [clara.server.tools.graph.annotations.merge :as ann.merge]))

(defn- ns-path
  "Classpath-style path prefix for a namespace name: acme.my-ns → acme/my_ns."
  [ns-str]
  (-> ns-str
      (str/replace "-" "_")
      (str/replace "." "/")))

(defn- rebase-qualified
  "Remaps one qualified name (symbol, keyword, or string) across ns-mapping
   {old-ns-str new-ns-str}.  A bare namespace name (no `/`) remaps whole.
   Unmapped names pass through unchanged."
  [ns-mapping x]
  (if (or (symbol? x) (keyword? x) (string? x))
    (let [s (str (symbol x))
          i (str/index-of s "/")]
      (if-not i
        (if-let [new-ns (get ns-mapping s)]
          (cond
            (symbol? x) (symbol new-ns)
            (keyword? x) (keyword new-ns)
            :else new-ns)
          x)
        (let [ns-part (subs s 0 i)]
          (if-let [new-ns (get ns-mapping ns-part)]
            (cond
              (symbol? x) (symbol new-ns (subs s (inc i)))
              (keyword? x) (keyword new-ns (subs s (inc i)))
              :else (str new-ns "/" (subs s (inc i))))
            x))))
    x))

(defn- rebase-filename
  "Remaps a source filename whose path derives from a mapped namespace
   (acme/pricing.clj or acme/pricing/sub.clj for acme.pricing)."
  [ns-mapping filename]
  (if-not (string? filename)
    filename
    (reduce (fn [f [old-ns new-ns]]
              (let [old-path (ns-path old-ns)]
                (if (and (str/starts-with? f old-path)
                         (< (count old-path) (count f))
                         (let [c (nth f (count old-path))]
                           (or (= c \/) (= c \.))))
                  (str/replace-first f old-path (ns-path new-ns))
                  f)))
            filename
            ns-mapping)))

(defn- update-some
  [m k f]
  (if (contains? m k) (update m k f) m))

(defn- rebase-via-path
  "Remaps each entry of a `:boundary-to-constructor-path`/`:rule-to-boundary-path` via chain."
  [ns-mapping stack]
  (mapv #(update-some % :var-name-sym (fn [v] (rebase-qualified ns-mapping v)))
        stack))

(defn- rebase-callsite
  [ns-mapping cs]
  (let [rebased (-> cs
                    (update-some :ns-name-sym #(rebase-qualified ns-mapping %))
                    (update-some :constructor-sym #(rebase-qualified ns-mapping %))
                    (update-some :filename #(rebase-filename ns-mapping %))
                    (update-some :resolved-types (fn [ts] (mapv #(rebase-qualified ns-mapping %) ts)))
                    (update-some :fact-type #(rebase-qualified ns-mapping %))
                    (update-some :via (fn [via]
                                        (-> via
                                            (update-some :boundary-var-name-sym
                                                         #(rebase-qualified ns-mapping %))
                                            (update-some :boundary-in-var
                                                         #(rebase-qualified ns-mapping %))
                                            (update-some :boundary-to-constructor-path
                                                         (partial rebase-via-path ns-mapping))
                                            (update-some :rule-to-boundary-path
                                                         (partial rebase-via-path ns-mapping))))))]
    ;; entries with a basis get fresh ids from the remapped content;
    ;; id-only references (no witness) keep their id and will dangle —
    ;; re-confirmation is the honest answer for those
    (if (ann.callsite/has-id-basis? rebased)
      (dissoc rebased :callsite-id)
      rebased)))

(defn rebase-layer
  "Remaps a layer across a known old→new namespace mapping, so renaming or moving a namespace does
  not dangle every curated callsite in it. `ns-mapping` is {old-ns new-ns} (symbols or strings).
  Rule-name keys, callsite discovery fields (`:ns-name-sym`, `:constructor-sym`, `:filename`,
  `:via`), and symbol/keyword fact-type tokens are remapped; callsite ids and duplicate-group
  ordinals are then recomputed from the remapped basis. Unmapped namespaces pass through unchanged."
  [layer-map ns-mapping]
  (let [ns-mapping (into {}
                         (map (fn [[old-ns new-ns]] [(str (symbol old-ns))
                                                     (str (symbol new-ns))]))
                         ns-mapping)]
    (-> layer-map
        (update :annotations
                (fn [anns]
                  (into {}
                        (map (fn [[rule-name rule-ann]]
                               [(rebase-qualified ns-mapping rule-name)
                                (reduce (fn [ra k]
                                          (let [dm (get ra k)]
                                            (if (contains? dm :callsites)
                                              (assoc ra k
                                                     (assoc dm :callsites
                                                            (ann.callsite/derive-callsite-ids
                                                             (mapv #(rebase-callsite ns-mapping %)
                                                                   (:callsites dm)))))
                                              ra)))
                                        rule-ann
                                        ann.callsite/detection-keys)]))
                        anns)))
        ann.merge/layer)))
