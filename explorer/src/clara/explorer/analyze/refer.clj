(ns clara.explorer.analyze.refer
  "Correction of clj-kondo `:var-usages` misattributed under `:refer :all`.

   When a namespace refers two or more namespaces wholesale (`:refer :all`), clj-kondo attributes
  every name used from any of them to the *first* one listed in the `:require` clause. This includes
  names that namespace does not export. The analyzer keys boundary detection and
  constructor-of-interest matching on the resolved callee, so a usage reported erroneously as, eg.
  `clara.rules/->fact` (no such var) is never matched and its insert goes unresolved.

   This namespace re-attributes such usages from the live namespace's refer table (`ns-refers`),
  which reflects runtime resolution exactly: a local symbol referred from several namespaces
  resolves to one winning var, and a refer-all spec is already expanded to concrete vars. A usage
  whose `:to` namespace is referred but does not provide `:name` is rewritten to the single other
  referred namespace that does; when none qualify the usage is left untouched."
  (:require [clara.explorer.ns-deps :as ns-deps]))

(defn- refer-sets
  "Returns `{target-ns-sym -> #{local-sym}}` — the symbols the live namespace `ns-sym` refers,
  grouped by the namespace each resolves to. Nil when `ns-sym` is not loaded. `clojure.core` refers
  are excluded (they are not misattributed through a `:require` clause)."
  [ns-sym]
  (when-let [nsobj (find-ns ns-sym)]
    (into {}
          (map (fn [{:keys [ns-name-sym refers]}]
                 [ns-name-sym (set refers)]))
          (ns-deps/->ns-required nsobj))))

(defn- re-attribute-usage
  "Returns `usage` with `:to` rewritten to the single other referred namespace that provides `(:name
  usage)`, when `:to` is a referred namespace that does not provide it. Returns `usage` unchanged
  otherwise (unknown-namespace keyword `:to`, no candidate, or the name is already correctly
  attributed)."
  [{to-ns :to name-sym :name :as usage} refer-sets]
  (let [to-refers (get refer-sets to-ns)]
    (if (and (symbol? to-ns)
             (seq to-refers)
             (not (contains? to-refers name-sym)))
      (let [candidates (into []
                             (keep (fn [[target refers]]
                                     (when (and (not= target to-ns)
                                                (contains? refers name-sym))
                                       target)))
                             refer-sets)]
        (cond-> usage
          (= 1 (count candidates)) (assoc :to (first candidates))))
      usage)))

(defn re-attribute-refer-all-usages
  "Rewrites the `:to` namespace of `:var-usages` entries misattributed by
   clj-kondo under `:refer :all` (see the namespace docstring). Returns the
   usages vector with corrected entries; every other entry is returned
   unchanged.

   `usages` is a coll of clj-kondo `:var-usages` maps (`:from`, `:to`,
   `:name` keys). Each usage is corrected against the refer table of its
   `:from` namespace when that namespace is loaded; unloaded namespaces
   contribute no table and their usages pass through untouched."
  [usages]
  (let [from-ns-syms (into #{} (keep :from) usages)
        refer-sets-by-from (into {}
                                 (keep (fn [ns-sym]
                                         (when-let [rs (refer-sets ns-sym)]
                                           [ns-sym rs])))
                                 from-ns-syms)]
    (if (empty? refer-sets-by-from)
      usages
      (mapv (fn [usage]
              (if-let [refer-sets (get refer-sets-by-from (:from usage))]
                (re-attribute-usage usage refer-sets)
                usage))
            usages))))
