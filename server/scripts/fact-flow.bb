#!/usr/bin/env bb
;; fact-flow.bb
;;
;; Distill a clara-rules-explorer annotations EDN (the output of
;; `clojure -M -m clara.server.graph.main -g <files>`, or
;; `generate-annotations-from-analysis`) into a fact-flow graph JSON.
;;
;; WHAT THIS DOES (and does not) reconstruct
;; -----------------------------------------
;; The annotations EDN is keyed by RULE and records, per rule, the fact types
;; its RHS inserts/retracts -- almost always as dynamic `->fact` callsite
;; `:source-str` strings. That is a rule -> produces view.
;;
;; The PRODUCER side of the graph is therefore a pure function of the EDN and is
;; reconstructed exactly here:
;;   :produced  {short-ns -> [fact-type ...]}      (per-namespace outputs)
;;   :prodOf    {fact-type -> [short-ns ...]}      (inverted producer index)
;;
;; The CONSUMER side (what each rule matches on its LHS) is NOT in the EDN: the
;; explorer strips the LHS during clj-kondo analysis by design, so consumers
;; must come from the source. When `--src <dir>` is given this script does a
;; best-effort LHS scan of the rule `.clj` files to add:
;;   :consumed  :consOf  :entry  :terminal  :crossNsEdges
;; That scan is HEURISTIC (regex over paren-matched `defrule` LHS regions); it
;; does not expand `derive+` hierarchies and may include a few destructuring
;; keywords. Treat :entry especially as an over-approximation. Without `--src`,
;; only the exact producer views are emitted.
;;
;; Boundary artifact: annotations EDNs may contain empty-map entries for the
;; clara.rules insert!/retract! fns themselves (they self-classify as inserters
;; but emit no fact type). Those are dropped here.
;;
;; USAGE
;;   ./fact-flow.bb ANNOTATIONS.edn [--src SRC_DIR] [--ns-prefix PREFIX] \
;;                  [-o OUT.json]
;;
;;   ANNOTATIONS.edn   required; the -g output.
;;   --src SRC_DIR     optional; root of rule .clj sources -> adds consumer views.
;;   --ns-prefix P     optional; dotted ns prefix to strip for short names.
;;                     Defaults to the longest common prefix of rule namespaces.
;;   -o OUT.json       optional; write here instead of stdout.

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.set :as set]
         '[cheshire.core :as json]
         '[babashka.fs :as fs])

(defn- die [msg] (binding [*out* *err*] (println msg)) (System/exit 1))

(def ^:private boundary-ns #{"clara.rules" "clara.rules.engine"})

;; ---------------------------------------------------------------------------
;; arg parsing
;; ---------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [a args, opts {}]
    (if (empty? a)
      opts
      (let [[x & more] a]
        (case x
          "--src"       (recur (rest more) (assoc opts :src (first more)))
          "--ns-prefix" (recur (rest more) (assoc opts :ns-prefix (first more)))
          "-o"          (recur (rest more) (assoc opts :out (first more)))
          ("-h" "--help") (assoc opts :help true)
          (recur more (assoc opts :anno x)))))))

;; ---------------------------------------------------------------------------
;; producer extraction (exact, from the EDN)
;; ---------------------------------------------------------------------------

(def ^:private ->fact-re
  ;; capture the first token after (->fact : a [vector], a :keyword, or a symbol
  #"\(->fact\s+(\[[^\]]*\]|:[^\s\]\),]+|[A-Za-z_*+!?<>=.-][^\s\]\),]*)")

(defn- fact-type-from-source-str
  "Return a normalized fact-type string from a `->fact` callsite string, or nil
   when the type is a computed symbol (unresolvable statically)."
  [s]
  (when-let [[_ tok] (re-find ->fact-re s)]
    (cond
      (str/starts-with? tok ":") tok
      (str/starts-with? tok "[") (-> tok (str/replace #"\s+" " ") str/trim)
      :else nil)))

(defn- types-from-annotation
  "All fact-type strings a rule annotation declares it produces, combining any
   static :clara-rules/insert-types and dynamic `->fact` callsites."
  [ann]
  (let [statics   (map str (:clara-rules/insert-types ann))
        callsites (get-in ann [:clara-rules/dynamic-insert-types-detected :callsites])
        dynamics  (keep (comp fact-type-from-source-str :source-str) callsites)]
    (into (set statics) dynamics)))

(defn- rule-annotations
  "Drop boundary-fn artifacts and empty annotations; keep real rule entries."
  [raw]
  (into {}
        (remove (fn [[k v]]
                  (or (not (map? v))
                      (empty? v)
                      (boundary-ns (namespace k)))))
        raw))

(defn- longest-common-dot-prefix [nses]
  (if (empty? nses)
    ""
    (let [segs (map #(str/split % #"\.") nses)
          n    (apply min (map count segs))]
      (loop [i 0, acc []]
        (if (>= i n)
          (str/join "." acc)
          (let [seg (nth (first segs) i)]
            (if (every? #(= seg (nth % i)) segs)
              (recur (inc i) (conj acc seg))
              (str/join "." acc))))))))

(defn- shortener [prefix]
  (let [p (when (seq prefix) (str prefix "."))]
    (fn [full-ns]
      (if (and p (str/starts-with? full-ns p))
        (subs full-ns (count p))
        full-ns))))

;; ---------------------------------------------------------------------------
;; consumer scan (heuristic, from source) -- only when --src is given
;; ---------------------------------------------------------------------------

(defn- ns-name-of-file [text]
  (when-let [[_ nm] (re-find #"\(ns\s+([A-Za-z0-9_.\-]+)" text)]
    (str/replace nm "_" "-")))

(defn- match-form
  "Given text and index of an opening paren, return [inner-string end-index]."
  [text open]
  (let [len (count text)]
    (loop [j (inc open), depth 1, in-str? false, esc? false]
      (if (>= j len)
        [(subs text (inc open)) len]
        (let [c (.charAt text j)]
          (cond
            esc?                   (recur (inc j) depth in-str? false)
            (and in-str? (= c \\)) (recur (inc j) depth true true)
            in-str?                (recur (inc j) depth (not= c \") false)
            (= c \")               (recur (inc j) depth true false)
            (= c \;)               (recur (or (str/index-of text "\n" j) len)
                                          depth false false)
            (= c \()               (recur (inc j) (inc depth) false false)
            (= c \))               (if (= depth 1)
                                     [(subs text (inc open) j) (inc j)]
                                     (recur (inc j) (dec depth) false false))
            :else                  (recur (inc j) depth false false)))))))

(defn- defrule-lhs-regions [text]
  (loop [i 0, out []]
    (if-let [start (str/index-of text "(defrule" i)]
      (let [[inner end] (match-form text start)
            ;; LHS = everything up to the top-level => token
            arrow (loop [j 0, depth 0, in-str? false, esc? false]
                    (if (>= j (count inner))
                      (count inner)
                      (let [c (.charAt inner j)]
                        (cond
                          esc?                   (recur (inc j) depth in-str? false)
                          (and in-str? (= c \\)) (recur (inc j) depth true true)
                          in-str?                (recur (inc j) depth (not= c \") false)
                          (= c \")               (recur (inc j) depth true false)
                          (= c \;)               (recur (or (str/index-of inner "\n" j)
                                                            (count inner))
                                                        depth false false)
                          (#{\( \[ \{} c)        (recur (inc j) (inc depth) false false)
                          (#{\) \] \}} c)        (recur (inc j) (dec depth) false false)
                          (and (zero? depth)
                               (= c \=)
                               (< (inc j) (count inner))
                               (= (.charAt inner (inc j)) \>))
                          j
                          :else                  (recur (inc j) depth false false)))))]
        (recur end (conj out (subs inner 0 arrow))))
      out)))

(def ^:private ns-kw-re #":[A-Za-z][\w.*+!?<>=-]*/[\w.*+!?<>=<-]+")
(def ^:private head-kw-re #"\[\s*(:[A-Za-z][\w.*+!?<>=-]*)")

(defn- lhs-fact-types [lhs]
  (set/union
   (set (re-seq ns-kw-re lhs))
   (set (map second (re-seq head-kw-re lhs)))))

(defn- consumed-from-src [src-dir shorten]
  (reduce
   (fn [acc file]
     (let [text (slurp (str file))
           full (ns-name-of-file text)]
       (if-not full
         acc
         (let [short (shorten full)
               types (into #{} (mapcat lhs-fact-types) (defrule-lhs-regions text))]
           (update acc short (fnil into #{}) types)))))
   {}
   (filter #(str/ends-with? (str %) ".clj")
           (fs/glob src-dir "**/*.clj"))))

;; ---------------------------------------------------------------------------
;; assembly
;; ---------------------------------------------------------------------------

(defn- invert [ns->types]
  (reduce-kv (fn [acc ns types]
               (reduce (fn [a t] (update a t (fnil conj #{}) ns)) acc types))
             {} ns->types))

(defn- sort-vals [m] (into (sorted-map) (map (fn [[k v]] [k (vec (sort v))])) m))

(defn- build [opts]
  (let [raw    (edn/read-string (slurp (:anno opts)))
        rules  (rule-annotations raw)
        _      (when (empty? rules) (die "No rule annotations found in EDN."))
        full-nses (distinct (map (comp namespace key) rules))
        prefix (or (:ns-prefix opts) (longest-common-dot-prefix full-nses))
        shorten (shortener prefix)
        produced-full (reduce (fn [acc [sym ann]]
                                (update acc (namespace sym)
                                        (fnil into #{}) (types-from-annotation ann)))
                              {} rules)
        produced (reduce-kv (fn [acc ns ts] (update acc (shorten ns) (fnil into #{}) ts))
                            {} produced-full)
        base {:produced (sort-vals produced)
              :prodOf   (sort-vals (invert produced))}]
    (if-let [src (:src opts)]
      (let [consumed  (consumed-from-src src shorten)
            prod-set  (set (mapcat val produced))
            cons-set  (set (mapcat val consumed))]
        (assoc base
               :consumed (sort-vals consumed)
               :consOf   (sort-vals (invert consumed))
               :entry    (vec (sort (set/difference cons-set prod-set)))
               :terminal (vec (sort (set/difference prod-set cons-set)))
               :crossNsEdges
               (sort-vals
                (reduce-kv
                 (fn [acc t producers]
                   (reduce
                    (fn [a p]
                      (reduce (fn [a2 c]
                                (if (= p c) a2
                                    (update a2 (str p " -> " c) (fnil conj #{}) t)))
                              a (get (invert consumed) t [])))
                    acc producers))
                 {} (invert produced)))))
      base)))

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(let [opts (parse-args *command-line-args*)]
  (cond
    (:help opts)
    (println (str "Usage: fact-flow.bb ANNOTATIONS.edn [--src SRC_DIR]"
                  " [--ns-prefix PREFIX] [-o OUT.json]"))

    (not (:anno opts))
    (die "Error: annotations EDN path required. See --help.")

    (not (fs/exists? (:anno opts)))
    (die (str "Error: file not found: " (:anno opts)))

    :else
    (let [result (build opts)
          out    (json/generate-string result {:pretty true})]
      (if-let [f (:out opts)]
        (do (spit f out) (binding [*out* *err*] (println (str "Wrote " f))))
        (println out)))))
