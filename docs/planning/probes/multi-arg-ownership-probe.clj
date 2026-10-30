;; Probe: `find-owning-boundary-arg` collapses ownership to the first argument
;; when one boundary call has several arguments reaching different constructors.
;;
;; Demonstrates §4 of
;; docs/planning/edge-cases-with-arg-span-detection-problem.md:
;; `arg-span-set` destructures only `:usage` from its traced arg and seeds the
;; fixpoint from the boundary usage span, so every argument of the same boundary
;; call computes an identical span set (and identical `:var-syms`).
;; `find-owning-boundary-arg`'s `some` therefore returns the first argument for
;; every constructor reachable from that call — including constructors reached
;; only through a *different* argument.
;;
;; Consequence: only arg 0 is marked `:owned`.  Arg 1 is handed to
;; `resolve-boundary-callsites`, whose generic resolver typically types nothing,
;; so it lands as a `:none` callsite and the dimension's `:resolution` is
;; dragged to `:partial` for an insert that was fully constructor-resolved.
;;
;; Run from explorer/:
;;   clojure -M -e '(load-file "../docs/planning/probes/multi-arg-ownership-probe.clj")'

(require '[clj-kondo.core :as kondo]
         '[clojure.string :as str]
         '[clara.explorer.analyze.callsite :as callsite]
         '[clara.explorer.analyze.index :as index]
         '[clara.explorer.analyze.utils :as u])

(def source
  (str "(ns probe.helpers\n"
       "  (:require [clara.rules :refer [insert!]]))\n\n"
       "(defn f1 [m] (->fact :a m))\n"
       "(defn f2 [m] (->fact :b m))\n\n"
       "(defn r [m]\n"
       "  (insert! (f1 m) (f2 m)))\n"))

(defn analyze [src filename]
  (let [lines (str/split-lines src)]
    {:analysis (:analysis
                (with-in-str src
                  (kondo/run! {:lint ["-"]
                               :lang :clj
                               :filename filename
                               :config {:analysis {:var-definitions true
                                                   :var-usages true
                                                   :java-class-usages true
                                                   :locals true
                                                   :local-usages true}}})))
     :src src
     :lines lines}))

(defn ->ctx [{:keys [analysis src lines]}]
  (let [idx (assoc (index/->analysis-index {:analysis analysis
                                            :get-source (fn [_ _] src)})
                   :get-lines (fn [_ _] lines))]
    (assoc idx :classify-local-binding (callsite/->local-binding-classifier idx))))

(defn boundary-usage [analysis nm]
  (->> (:var-usages analysis)
       (filter #(and (= nm (:name %)) (:from-var %)))
       first))

(let [{:keys [analysis] :as a} (analyze source "probe/helpers.clj")
      ctx (->ctx a)
      usage (boundary-usage analysis 'insert!)
      inserter-var (u/var-usage-caller usage)
      traced-args (callsite/trace-boundary-args [usage] ctx)
      span-set-by-idx (into {} (map (juxt :idx #(#'callsite/arg-span-set % ctx))) traced-args)
      ctor-usages (->> (:var-usages analysis)
                       (filter #(= '->fact (:name %)))
                       (sort-by (juxt :row :col))
                       vec)]

  (println "boundary usage:" (pr-str (select-keys usage [:name :row :col :end-row :end-col])))
  (println "traced args:"
           (pr-str (mapv (juxt :idx :arg) traced-args)))
  (println)
  (println "arg-span-set per argument (identical — seeded from the same :usage):")
  (doseq [arg traced-args]
    (let [ss (get span-set-by-idx (:idx arg))]
      (println (format "  idx=%d spans=%s var-syms=%s"
                       (:idx arg)
                       (pr-str (:spans ss))
                       (pr-str (sort-by str (:var-syms ss)))))))
  (println "  identical?" (apply = (vals span-set-by-idx)))
  (println)

  (println "->fact constructor usages:")
  (doseq [[i cu] (map-indexed vector ctor-usages)]
    (println (format "  ctor-%d in %s at [%s %s]"
                     i (u/var-usage-caller cu) (:row cu) (:col cu))))
  (println)

  (doseq [[i cu] (map-indexed vector ctor-usages)]
    (let [path (#'callsite/ctor-call-path (:graph ctx) inserter-var cu)
          intermediates (set (rest path))]
      (println (format "ctor-%d — call path %s, intermediates %s"
                       i (pr-str (mapv str path)) (pr-str intermediates)))
      (doseq [arg traced-args]
        (println (format "  arg %d reaches ctor-%d? %s"
                         (:idx arg) i
                         (#'callsite/arg-reaches-ctor?
                          {:traced-arg arg
                           :ctor-usage cu
                           :intermediates intermediates
                           :span-set (get span-set-by-idx (:idx arg)
                                          {:spans [] :var-syms #{}})}))))
      (let [owner (#'callsite/find-owning-boundary-arg
                   {:ctor-usage cu
                    :intermediates intermediates
                    :traced-args traced-args
                    :span-set-by-idx span-set-by-idx})]
        (println (format "  find-owning-boundary-arg -> idx %s" (:idx owner))))
      (println)))

  ;; Only the owning idx(s) are removed from the boundary pass; everything else
  ;; falls through to the generic resolver, which here types nothing.
  (let [owned-idxs (into #{}
                         (keep (fn [cu]
                                 (let [path (#'callsite/ctor-call-path (:graph ctx) inserter-var cu)
                                       owner (#'callsite/find-owning-boundary-arg
                                              {:ctor-usage cu
                                               :intermediates (set (rest path))
                                               :traced-args traced-args
                                               :span-set-by-idx span-set-by-idx})]
                                   (:idx owner))))
                         ctor-usages)
        unowned (remove #(contains? owned-idxs (:idx %)) traced-args)
        boundary-result (callsite/resolve-boundary-callsites
                         (vec unowned)
                         (assoc ctx
                                :direction :insert
                                :rule nil
                                :rule-var inserter-var
                                :rule-to-boundary-path-for nil
                                :callsite-resolver-fn (fn [_] nil)))]
    (println "owned arg idxs:" (pr-str owned-idxs))
    (println "left for the boundary pass:"
             (pr-str (mapv (juxt :idx :arg) unowned)))
    (println "boundary-pass callsite statuses:"
             (pr-str (mapv :status (:callsites boundary-result))))
    (println "boundary-pass resolution:" (pr-str (:resolution boundary-result)))
    (println)
    (println "Both constructors are attributed to arg 0 because both arguments share")
    (println "one span set, so `some` in find-owning-boundary-arg stops at the first.")
    (println "Arg 1 — the argument that actually calls f2 — is left to the boundary")
    (println "pass, resolves to :none, and drags the aggregate to :partial even though")
    (println "both inserts were fully resolved by the constructor pass.")
    (println)
    (println "Per-argument spans (the plan's fix) would make ctor-0 own idx 0 and")
    (println "ctor-1 own idx 1, leaving nothing for the boundary pass.")))
