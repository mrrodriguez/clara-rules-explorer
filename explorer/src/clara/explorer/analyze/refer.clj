(ns clara.explorer.analyze.refer
  "Correction of clj-kondo `:var-usages` misattributed under `:refer :all`.

   When a namespace refers two or more namespaces wholesale (`:refer :all`), clj-kondo attributes
   every bare name used from any of them to the *first* one listed in the `:require` clause. This
   includes names that namespace does not export. The analyzer keys boundary detection and
   constructor-of-interest matching on the resolved callee, so a usage reported erroneously as, eg.
   `clara.rules/->fact` (no such var) is never matched and its insert goes unresolved.

   This namespace re-attributes *bare* usages from the live namespace's refer table (`ns-refers`),
   which reflects runtime resolution exactly: a local symbol referred from several namespaces
   resolves to one winning var, and a refer-all spec is already expanded to concrete vars. A bare
   usage of `name` is rewritten to the namespace of the var `(get (ns-refers from) name)`. Qualified
   usages (aliased or fully-qualified) are left untouched: kondo attributes them correctly, and only
   they could carry the false-positive a candidate-scan rewrite would introduce.")

(defn- bare-usage?
  "True when a kondo `:var-usage` names a bare local symbol rather than an
   aliased or fully-qualified one. A bare usage has no `:alias`, and its
   `:name-col`/`:name-end-col` span is exactly the symbol name. Qualified
   tokens are longer (they include `ns/`); a `:rename`d usage spans the local
   symbol while `:name` is the resolved original, so neither is bare.

   A hook-derived usage (one emitted from a macro call rather than a written
   symbol) can look bare when its `:name` happens to have the same length as
   the macro token. That is benign: the rewrite is still the runtime truth for
   that bare name, and the pathological case (a qualified hook-emitted symbol
   whose bare name is referred from elsewhere) does not arise in the bundled
   clara-rules config."
  [{:keys [alias name-col name-end-col] name-sym :name}]
  (and (nil? alias)
       (symbol? name-sym)
       (some? name-col)
       (some? name-end-col)
       (= (- name-end-col name-col) (count (name name-sym)))))

(defn- ->refers-by-from
  "`{from-ns-sym -> ns-refers-map}` for every distinct `:from` namespace of a
   bare usage that is loaded.  Only bare usages need re-attribution, and
   `find-ns`/`ns-refers` run once per namespace, not once per usage."
  [usages]
  (into {}
        (comp (filter bare-usage?)
              (keep :from)
              (distinct)
              (keep (fn [from-ns]
                      (when-let [nsobj (find-ns from-ns)]
                        [from-ns (ns-refers nsobj)]))))
        usages))

(defn- re-attribute-usage
  "Returns `usage` with `:to` rewritten to the namespace its live `:from`
   namespace actually resolves a bare usage of `:name` to. Qualified usages
   and unresolvable names pass through unchanged.

   `refers-by-from` is the per-namespace `ns-refers` map (see
   `->refers-by-from`)."
  [refers-by-from {:keys [from name] :as usage}]
  (if (bare-usage? usage)
    (if-let [v (get-in refers-by-from [from name])]
      (assoc usage :to (-> v meta :ns ns-name))
      usage)
    usage))

(defn re-attribute-refer-all-usages
  "Rewrites the `:to` namespace of bare `:var-usages` entries misattributed by
   clj-kondo under `:refer :all` (see the namespace docstring). Returns the
   usages vector with corrected entries; every other entry is returned
   unchanged.

   `usages` is a coll of clj-kondo `:var-usages` maps (`:from`, `:to`,
   `:name`, `:alias`, `:name-col`, `:name-end-col` keys). Each bare usage is
   corrected against the live refer table of its `:from` namespace, looked up
   once per distinct `:from` namespace; unloaded namespaces and unresolvable
   names pass through untouched."
  [usages]
  (let [refers-by-from (->refers-by-from usages)]
    (if (empty? refers-by-from)
      usages
      (mapv (partial re-attribute-usage refers-by-from) usages))))
