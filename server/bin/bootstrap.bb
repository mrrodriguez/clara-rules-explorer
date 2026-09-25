#!/usr/bin/env bb
;; Add `server/src` and the prismatic/schema version pinned in `server/deps.edn`
;; to a babashka script's classpath, so `shared.*` namespaces (and later the
;; pure `editor_client.bb` composition) can be `require`d under bb.
;;
;; Load this with `load-file` (not `require`) — it is what makes `require` work
;; in the first place:
;;
;;   (load-file (str (fs/file (fs/parent *file*) "bootstrap.bb")))
;;
;; Only the namespaces a script actually requires are loaded, which is what keeps
;; the bb client free of the JVM toolchain (`clara.rules.engine`, ring/Jetty).
(require '[babashka.classpath :as cp]
         '[babashka.deps :as deps]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn])

(let [server-root (fs/parent (fs/parent (fs/canonicalize *file*)))
      deps (:deps (edn/read-string (slurp (str (fs/path server-root "deps.edn")))))]
  ;; Schema is the one external dependency shared namespaces are allowed to
  ;; pull in (see `shared.schema`); it is a plain Clojure library bb loads.
  (deps/add-deps {:deps (select-keys deps '[prismatic/schema])})
  (cp/add-classpath (str (fs/path server-root "src"))))
