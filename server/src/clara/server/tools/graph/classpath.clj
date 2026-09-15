(ns clara.server.tools.graph.classpath
  "Reading and copying classpath *resource directories*.

  Nothing here knows about annotations or clara — it exists because
  `clara.server.tools.graph.kondo-config` has to materialize a clj-kondo config dir out of
  resources that may be either a jar entry (a released dependency) or an exploded directory (a
  local checkout), and that distinction is a classpath concern rather than a clj-kondo one."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.net JarURLConnection URL)
   (java.util.jar JarEntry JarFile)))

(set! *warn-on-reflection* true)

(defn- get-exploded-dir-file-paths
  "`get-resource-file-paths` for a `file:` url — an exploded dir, i.e. a local
  checkout or a `:paths` entry."
  [^URL url]
  (let [^File root (io/file url)
        root-path (.toPath root)]
    (into []
          (comp (filter File/.isFile)
                (map #(str (.relativize root-path (File/.toPath %)))))
          (file-seq root))))

(defn- get-jar-entry-file-paths
  "`get-resource-file-paths` for a `jar:` url — the usual case for a dependency."
  [^URL url base]
  ;; NB: never close the JarFile — it is the classloader's cached handle.
  (let [^JarFile jar (JarURLConnection/.getJarFile (.openConnection url))
        prefix (str base "/")]
    (into []
          (comp (remove JarEntry/.isDirectory)
                (map JarEntry/.getName)
                (filter #(str/starts-with? % prefix))
                (map #(subs % (count prefix))))
          (enumeration-seq (.entries jar)))))

(defn get-resource-file-paths
  "Paths of every file under classpath resource dir `base`, relative to `base`.
  Nil when `base` is not on the classpath at all — distinct from `[]`, which
  means the dir is there and empty."
  [base]
  (when-let [^URL url (io/resource base)]
    (condp = (.getProtocol url)
      "file" (get-exploded-dir-file-paths url)
      "jar" (get-jar-entry-file-paths url base)
      nil)))

(defn read-edn-resource
  "Read classpath resource `path` as EDN, or nil when it is not on the classpath."
  [path]
  (some-> (io/resource path) slurp edn/read-string))

(defn- copy-resource!
  "Copy classpath resource `base`/`rel` to `dest-dir`/`rel`, creating parents.
  True when the resource was on the classpath and got copied."
  [base rel dest-dir]
  (boolean
   (when-let [res (io/resource (str base "/" rel))]
     (let [dest (io/file dest-dir rel)]
       (io/make-parents dest)
       (with-open [in (io/input-stream res)]
         (io/copy in dest))
       true))))

(defn copy-resources!
  "Copy classpath resources `base`/`rel`, for each rel in `rels`, into
  `dest-dir` — preserving the relative layout. Resources that are not on the
  classpath are skipped rather than throwing, so a partial `rels` still yields a
  usable dir. Returns the count actually copied, which is how a caller tells
  \"nothing was there\" from \"some of it was\"."
  [base rels dest-dir]
  (reduce (fn [n rel]
            (cond-> n (copy-resource! base rel dest-dir) inc))
          0
          rels))
