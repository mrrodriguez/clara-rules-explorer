;; Probe: `find-owning-boundary-arg` collapses ownership to the first argument
;; when one boundary call has several arguments reaching different constructors.
;;
;; Demonstrates §2 of
;; docs/planning/edge-cases-with-arg-span-detection-problem.md: `arg-span-set`
;; is seeded from the whole boundary usage, not the individual argument, so all
;; arguments of the same call share one span set (and one `:var-syms` set), and
;; `find-owning-boundary-arg`'s `some` returns the first argument for every
;; constructor reachable from that call.
;;
;; Run from explorer/:
;;   clojure -M -e '(load-file "../docs/planning/probes/multi-arg-ownership-probe.clj")'

(require '[clj-kondo.core :as kondo]
         '[clojure.string :as str]
         '[clara.explorer.analyze.callsite :as callsite]
         '[clara.explorer.analyze.index :as index])

(def source
  (str "(ns probe.helpers\n"
       "  (:require [clara.rules :refer [insert!]]))\n\n"
       "(defn f1 [m] (->fact :a m))\n"
       "(defn f2 [m] (->fact :b m))\n\n"
       "(defn r [m]\n"
       "  (insert! (f1 m) (f2 m)))\n"))

(defn analyze [src]
  (with-in-str src
    (:analysis
     (kondo/run! {:lint ["-"]
                  :lang :clj
                  :filename "probe/helpers.clj"
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

(def lines (str/split-lines source))
(def get-lines (fn [ns-sym _filename]
                 (when (= ns-sym 'probe.helpers) lines)))

(def ctx {:var-usages-by-filename (#'index/usages-by-filename (:var-usages analysis))
          :local-usages-by-filename (#'index/usages-by-filename (:local-usages analysis))
          :locals-by-id (into {} (map (juxt (juxt :filename :id) identity)) (:locals analysis))
          :get-lines get-lines})

;; Two boundary arguments of the SAME boundary call — same :usage, different :idx.
(def traced-args [{:idx 0 :usage boundary-usage :alias-context nil}
                  {:idx 1 :usage boundary-usage :alias-context nil}])

(def span-set-by-idx
  (into {} (map (juxt :idx #(#'callsite/arg-span-set % ctx))) traced-args))

(println "boundary usage span:" (select-keys boundary-usage [:row :col :end-row :end-col]))
(println)
(println "arg-span-set for each argument (identical — seeded from the same :usage):")
(doseq [arg traced-args]
  (let [ss (get span-set-by-idx (:idx arg))]
    (println (format "  idx=%d spans=%d var-syms=%s"
                     (:idx arg)
                     (count (:spans ss))
                     (sort-by str (:var-syms ss))))))
(println)

(def ctor-usages
  (->> (:var-usages analysis)
       (filter #(= '->fact (:name %)))
       (sort-by (juxt :row :col))
       vec))

(println "->fact constructor usages:")
(doseq [[i cu] (map-indexed vector ctor-usages)]
  (println (format "  ctor-%d in %s/%s at [%s %s]"
                   i (:from cu) (:from-var cu) (:row cu) (:col cu))))
(println)

(doseq [[i cu] (map-indexed vector ctor-usages)]
  (let [helper-sym (symbol "probe.helpers" (name (:from-var cu)))
        intermediates #{helper-sym}]
    (println (format "ctor-%d (%s) — intermediates %s" i helper-sym intermediates))
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

(println "Both constructors are owned by arg 0: the `some` in find-owning-boundary-arg")
(println "returns the first argument whose (shared) span set reaches the constructor,")
(println "so a multi-argument boundary call collapses ownership onto its first argument.")
