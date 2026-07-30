(ns user)

(println "Setting *warn-on-reflection* to true (from dev/user.clj)")
(set! *warn-on-reflection* true)

(defn- try-init-reload []
  (try
    (let [init-fn (requiring-resolve 'clj-reload.core/init)
          classpath-dirs-fn (requiring-resolve 'clj-reload.core/classpath-dirs)]
      (init-fn {:dirs (classpath-dirs-fn)})
      true)
    (catch Exception _
      false)))

(defonce reload-initialized?
  ;; Initialize clj-reload on first load if available.
  (try-init-reload))

(defn reload-nses
  "Trigger clj-reload to unload/reload changed namespaces.
   Called by editor save hooks (e.g. spacemacs my/clj-reload-on-save).
   Prints status to stdout so nREPL captures it in the :out response slot."
  ([]
   (reload-nses nil))
  ([opts]
   (if reload-initialized?
     (let [reload-fn (requiring-resolve 'clj-reload.core/reload)
           {:keys [unloaded loaded]} (reload-fn (merge {:throw true} opts))
           status (format "Reloaded %d namespace%s%s"
                          (count loaded)
                          (if (= 1 (count loaded)) "" "s")
                          (if (seq unloaded)
                            (format " (unloaded %d)" (count unloaded))
                            ""))]
       (println status)
       status)
     (let [msg "clj-reload not available on classpath."]
       (println msg)
       msg))))
