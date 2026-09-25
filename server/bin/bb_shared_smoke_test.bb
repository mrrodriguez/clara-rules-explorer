#!/usr/bin/env bb
;; bb smoke test for the `shared.*` namespaces. Every namespace marked
;; `:clara-rules-explorer/bb-loaded true` must `require` under babashka with
;; only `server/src` plus the schema version from `deps.edn` on the classpath.
;; The marker is the JVM side's promise that a namespace is bb-safe; this turns
;; that promise into a test instead of a docstring comment.
;;
;;   bb bin/bb_shared_smoke_test.bb
(require '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.tools.reader :as reader])

(load-file (str (fs/file (fs/parent *file*) "bootstrap.bb")))

(def ^:private server-root (fs/parent (fs/parent (fs/canonicalize *file*))))

(defn- source-file? [f]
  (and (.isFile ^java.io.File f)
       (let [n (.getName ^java.io.File f)]
         (or (str/ends-with? n ".clj")
             (str/ends-with? n ".cljc")))))

(defn- bb-loaded-nses
  "Namespaces under `server/src` whose ns form carries
   `:clara-rules-explorer/bb-loaded true`, sorted by name."
  []
  (->> (file-seq (io/file (str (fs/file server-root "src"))))
       (filter source-file?)
       (keep (fn [f]
               (try
                 (let [form (reader/read-string
                             {:read-cond :allow :features #{:clj :bb} :eof ::eof}
                             (slurp f))]
                   (when (and (seq? form) (= 'ns (first form)))
                     (let [ns-sym (second form)]
                       (when (true? (:clara-rules-explorer/bb-loaded (meta ns-sym)))
                         ns-sym))))
                 (catch Throwable _ nil))))
       (sort-by str)
       vec))

(defn- exit! [code msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit code))

(let [nses (bb-loaded-nses)]
  (when (empty? nses)
    (exit! 1 "bb smoke test: no :clara-rules-explorer/bb-loaded namespaces found under server/src"))
  (println "Requiring" (count nses) "bb-loaded namespaces:")
  (doseq [ns-sym nses]
    (print "  " ns-sym " ... ")
    (flush)
    (try
      (require ns-sym)
      (println "ok")
      (catch Throwable e
        (exit! 1 (str "  FAILED: " (or (.getMessage ^Throwable e) (str e))))))))
