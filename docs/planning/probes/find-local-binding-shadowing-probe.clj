;; Probe: in-call shadowing — the argument is *refused*, not mis-resolved.
;;
;; Companion to docs/planning/edge-cases-with-arg-span-detection-problem.md §3.
;;
;; History: the plan originally recorded that `find-local-binding` returned the
;; first matching local usage in the span, so a shadowed name resolved to the
;; wrong binding.  That function no longer exists.  The current code
;; (`find-local-binding-ids` + `find-unique-local-binding`) collects every kondo
;; local id the name resolves to inside the span it is given, and refuses —
;; `:resolve-local` answers `{:kind :unknown :reason :shadowed}` — when there is
;; more than one.
;;
;; What still fails: `trace-boundary-args` scopes every argument to the *whole*
;; boundary call, so the shadowed name is ambiguous even for the argument that
;; unambiguously names the outer binding.  The argument is refused instead of
;; resolved.  Per-argument spans (the plan's proposed fix) would scope the
;; lookup to the argument's own source region — the last section of this probe
;; shows the answer that produces.
;;
;; Run from explorer/:
;;   clojure -M -e '(load-file "../docs/planning/probes/find-local-binding-shadowing-probe.clj")'

(require '[clj-kondo.core :as kondo]
         '[clojure.string :as str]
         '[clara.explorer.analyze.callsite :as callsite]
         '[clara.explorer.analyze.index :as index])

(def source
  (str "(ns probe.shadow\n"
       "  (:require [clara.rules :refer [insert!]]))\n\n"
       "(defn f []\n"
       "  (let [x (->fact :outer)]\n"                 ; outer binding of x
       "    (insert! (let [x (->fact :inner)] x)\n"  ; inner (shadowing) binding
       "             x)))\n"))                        ; outer x — the argument we care about

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

(defn usage-span [usage]
  {:filename (:filename usage)
   :start [(:row usage) (:col usage)]
   :end [(:end-row usage) (:end-col usage)]})

(let [{:keys [analysis] :as a} (analyze source "probe/shadow.clj")
      ctx (->ctx a)
      usage (boundary-usage analysis 'insert!)
      call-span (usage-span usage)
      x-usages-in-span (->> (get (:local-usages-by-name ctx) [(:filename usage) 'x])
                            (filter #(#'callsite/span-contains-pos? call-span
                                                                    (:filename %)
                                                                    (:row %)
                                                                    (:col %)))
                            (sort-by (juxt :row :col))
                            vec)]

  (println "boundary usage span:" (pr-str call-span))
  (println)
  (println "x :locals bindings (source order):")
  (doseq [b (sort-by (juxt :row :col) (:locals analysis))
          :when (= 'x (:name b))]
    (println (format "  id=%s at [%s %s]" (:id b) (:row b) (:col b))))
  (println)
  (println "x :local-usages inside the boundary span (source order):")
  (doseq [u x-usages-in-span]
    (println (format "  id=%s at [%s %s]" (:id u) (:row u) (:col u))))
  (println)

  ;; --- current behaviour: the whole boundary call is the lookup span ---
  (println "whole-call span lookup (what `trace-boundary-args` uses today):")
  (println "  find-local-binding-ids ->" (pr-str (#'callsite/find-local-binding-ids ctx call-span 'x)))
  (println "  find-unique-local-binding ->"
           (pr-str (some-> (#'callsite/find-unique-local-binding ctx call-span 'x)
                           (select-keys [:name :id :row :col]))))
  (println "  => more than one id, so the lookup refuses (`:shadowed`)")
  (println)

  ;; --- what per-argument spans would produce ---
  (let [arg-x-span (let [{:keys [row col end-col]} (last x-usages-in-span)]
                     {:filename (:filename usage)
                      :start [row col]
                      :end [row end-col]})]
    (println "argument-scoped span for the outer `x` (the plan's fix):" (pr-str arg-x-span))
    (println "  find-local-binding-ids ->"
             (pr-str (#'callsite/find-local-binding-ids ctx arg-x-span 'x)))
    (println "  find-unique-local-binding ->"
             (pr-str (some-> (#'callsite/find-unique-local-binding ctx arg-x-span 'x)
                             (select-keys [:name :id :row :col]))))
    (println "  => one id: the outer binding at [5 9]")
    (println))

  ;; --- the end-to-end consequence today ---
  (let [traced (callsite/trace-boundary-args [usage] ctx)
        seen (atom [])
        resolver (fn [{:keys [arg-form resolve-local]}]
                   (let [local (when (symbol? arg-form) (resolve-local arg-form))]
                     (swap! seen conj [arg-form (select-keys local [:kind :reason])])
                     (when (= :let-init (:kind local)) [:from-resolved-local])))
        result (callsite/resolve-boundary-callsites
                traced
                (assoc ctx
                       :direction :insert
                       :rule nil
                       :rule-var 'probe.shadow/f
                       :rule-to-boundary-path-for nil
                       :callsite-resolver-fn resolver))]
    (println "current pipeline (`trace-boundary-args` + `resolve-boundary-callsites`):")
    (doseq [ta traced]
      (println (format "  arg idx=%d arg=%s traced-span=%s"
                       (:idx ta) (pr-str (:arg ta)) (pr-str (:traced-span ta)))))
    (println "  resolver saw:" (pr-str @seen))
    (println "  callsite statuses:" (pr-str (mapv :status (:callsites result))))
    (println "  dimension resolution:" (pr-str (:resolution result)))
    (println)
    (println "The outer `x` argument should resolve to the outer binding (`:outer`),")
    (println "not be refused. Per-argument spans close that gap.")
    (println "The mis-attribution the plan originally recorded — resolving to the")
    (println "inner binding — no longer occurs: the lookup refuses instead.")))
