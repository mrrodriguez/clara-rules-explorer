(ns user
  (:require [clj-reload.core :as reload]))

(println "Setting *warn-on-reflection* to true (from dev/user.clj)")
(set! *warn-on-reflection* true)

(defonce reload-initialized?
  ;; Initialize clj-reload on first load.
  (do
    (reload/init {:dirs (reload/classpath-dirs)})
    true))

(defn reload-nses
  "Trigger clj-reload to unload/reload changed namespaces.
   Called by editor save hooks (e.g. spacemacs my/clj-reload-on-save).
   Prints status to stdout so nREPL captures it in the :out response slot."
  ([]
   (reload-nses nil))
  ([opts]
   (let [{:keys [unloaded loaded]} (reload/reload (merge {:throw true} opts))
         status (format "Reloaded %d namespace%s%s"
                        (count loaded)
                        (if (= 1 (count loaded)) "" "s")
                        (if (seq unloaded)
                          (format " (unloaded %d)" (count unloaded))
                          ""))]
     (println status)
     status)))
