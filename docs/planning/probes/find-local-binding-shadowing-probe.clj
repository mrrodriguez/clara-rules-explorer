;; Probe: `find-local-binding` order dependence under in-call shadowing.
;;
;; Demonstrates §1 of
;; docs/planning/edge-cases-with-arg-span-detection-problem.md: the span handed
;; to `callsite/find-local-binding` is the *whole boundary call*, so when the
;; same symbol is shadowed earlier in the call, the first matching local usage
;; in the span can belong to the wrong binding.
;;
;; Run from explorer/:
;;   clojure -M -e '(load-file "../docs/planning/probes/find-local-binding-shadowing-probe.clj")'

(require '[clj-kondo.core :as kondo]
         '[clojure.string :as str]
         '[clara.explorer.analyze.callsite :as callsite])

(def source
  (str "(ns probe.shadow\n"
       "  (:require [clara.rules :refer [insert!]]))\n\n"
       "(defn f []\n"
       "  (let [x (->fact :outer)]\n"     ; outer binding of x
       "    (insert! (let [x (->fact :inner)] x)\n"  ; inner (shadowing) binding
       "             x)))\n"))           ; outer x again — the argument we care about

(defn analyze [src]
  (with-in-str src
    (:analysis
     (kondo/run! {:lint ["-"]
                  :lang :clj
                  :filename "probe/shadow.clj"
                  :config {:analysis {:var-definitions true
                                      :var-usages true
                                      :java-class-usages true
                                      :locals true
                                      :local-usages true}}}))))

(def analysis (analyze source))

(def boundary-usage
  (->> (:var-usages analysis)
       (filter #(and (= 'insert! (:name %)) (:from-var %)))
       first))

(def span {:filename (:filename boundary-usage)
           :start [(:row boundary-usage) (:col boundary-usage)]
           :end [(:end-row boundary-usage) (:end-col boundary-usage)]})

(def local-usages-by-name (group-by (juxt :filename :name) (:local-usages analysis)))
(def locals-by-id (into {} (map (juxt (juxt :filename :id) identity)) (:locals analysis)))
(def ctx {:local-usages-by-name local-usages-by-name
          :locals-by-id locals-by-id})

(defn within-span? [u]
  (let [[row col] (:start span)
        [end-row end-col] (:end span)]
    (and (= (:filename span) (:filename u))
         (<= row (:row u) end-row)
         (or (not= row (:row u)) (<= col (:col u)))
         (or (not= end-row (:row u)) (< (:col u) end-col)))))

(def x-usages-in-span
  (->> (get local-usages-by-name [(:filename boundary-usage) 'x])
       (filter within-span?)))

(println "boundary usage span:" span)
(println)
(println "x :locals bindings (source order):")
(doseq [b (sort-by :row (:locals analysis))]
  (when (= 'x (:name b))
    (println (format "  id=%s at [%s %s]" (:id b) (:row b) (:col b)))))
(println)
(println "x :local-usages inside the boundary span (source order):")
(doseq [u x-usages-in-span]
  (println (format "  id=%s at [%s %s]" (:id u) (:row u) (:col u))))
(println)
(println "find-local-binding picks ->"
         (select-keys (#'callsite/find-local-binding ctx span 'x) [:name :id :row :col]))
(println "last-in-span would pick  ->"
         (select-keys (last x-usages-in-span) [:name :id :row :col]))
(println)
(println "The outer argument is the LAST x usage; find-local-binding picked the FIRST"
         "(the inner, shadowing binding). Reversing the argument order makes 'first'"
         "right and 'last' wrong — neither order is universally correct.")
