(ns clara.server.tools.graph.analyze.ctor
  "Record/Java constructor resolution against live namespaces.

   Recognizes constructor forms (`->X`/`map->X` record ctors, `X.`/`new X`/
   `X/new` Java ctors) and resolves them to fact-type tokens (fq class-name
   symbols) via the live namespace — only what we know the instance type of.
   Shared by the static inference path (`analyze.index` type maps) and the
   dynamic callsite chain (`analyze.callsite`)."
  (:require [clojure.string :as str]))

(defn constructor-fn-name?
  "True if the given fn-name string looks like a record constructor (`->X` or `map->X`)."
  [fn-name]
  (or (str/starts-with? fn-name "map->")
      (str/starts-with? fn-name "->")))

(defn- resolvable-fact-class [sym]
  (try
    (when (class? (resolve sym))
      sym)
    (catch Throwable _ nil)))

(defn resolve-record-type
  "Resolves `class-sym` in the given live namespace to a fact-type token:
   a Class (imported or fq class name) ⇒ its fq class-name symbol; a `->X`/`map->X`
   record ctor var ⇒ the fq class-name symbol of the record, but only when the
   derived class actually loads (rejects constructor-named helper fns such as
   a custom `->fact` builder).  Returns nil when unresolvable."
  [ns-sym class-sym]
  (try
    (if-let [resolved (ns-resolve (find-ns ns-sym) class-sym)]
      (cond
        (class? resolved)
        (symbol (.getName ^Class resolved))

        (var? resolved)
        (let [v-meta (meta resolved)
              ns-str (-> v-meta :ns ns-name name)
              fn-name (-> v-meta :name name)
              class-name (cond
                           (str/starts-with? fn-name "->") (subs fn-name 2)
                           (str/starts-with? fn-name "map->") (subs fn-name 5))]
          (when class-name
            (let [fq-sym (-> ns-str
                             (str/replace "-" "_")
                             (str  "." class-name)
                             symbol)]
              (resolvable-fact-class fq-sym))))

        :else nil)
      nil)
    (catch Exception _ nil)))

(defn resolve-ctor-form
  "a seq arg whose head is a record ctor (->X/map->X)
   or a Java ctor (X., new X, X/new), resolved against the live caller ns.
   Returns a set of one fq class-name token, or nil.

   `resolve-record-type-fn` is the (per-run memoized) record-type resolver
   from the index — see `index/AnalysisIndex`."
  [resolve-record-type-fn caller-ns-sym arg-form]
  (let [head (first arg-form)]
    (when (symbol? head)
      (or
       ;; Step 1: record constructor (->X …) / (map->X …)
       (when (constructor-fn-name? (name head))
         (some-> (resolve-record-type-fn caller-ns-sym head) hash-set))
       ;; Step 2: Java constructors
       (cond
         ;; (new X …)
         (contains? '#{new clojure.core/new} head)
         (when (symbol? (second arg-form))
           (some-> (resolve-record-type-fn caller-ns-sym (second arg-form)) hash-set))

         ;; (X. …)
         (str/ends-with? (name head) ".")
         (let [class-name (subs (name head) 0 (dec (count (name head))))
               class-sym (if (namespace head)
                           (symbol (namespace head) class-name)
                           (symbol class-name))]
           (some-> (resolve-record-type-fn caller-ns-sym class-sym) hash-set))

         ;; (X/new …) — namespace part is the class name
         (and (namespace head) (= "new" (name head)))
         (some-> (resolve-record-type-fn caller-ns-sym (symbol (namespace head))) hash-set)

         :else nil)))))
