;; Canonical editor resolve-form template. Slurped (never loaded) by
;; `clara.server.tools.graph.shared.tokens/editor-token-resolve-form`, which
;; fills the two slots in order: caller-ns string, token string. clojure.core
;; plus `String` interop only — no explorer dependency, so it evals on a plain
;; project repl. Symlinked beside the Emacs/Neovim transports, which read it
;; at load; keep every copy byte-identical (the JVM sync test enforces it).
(let [ns-sym (symbol %s)
      the-ns (find-ns ns-sym)
      token-text %s
      form (binding [*read-eval* false *ns* (or the-ns *ns*)]
             (try (read-string token-text) (catch Exception _ nil)))]
  (cond
    (and (nil? the-ns) (String/.startsWith token-text "::")) nil
    (symbol? form)
    (let [n (name form)
          ns-part (namespace form)
          target (cond (String/.endsWith n ".")
                       (let [s (subs n 0 (dec (count n)))]
                         (if ns-part (symbol ns-part s) (symbol s)))
                       (and (= n "new") ns-part)
                       (symbol ns-part)
                       :else form)
          v (when the-ns
              (try (ns-resolve the-ns target) (catch Exception _ nil)))]
      (cond (class? v) (Class/.getName v)
            (var? v) (str (symbol (str (ns-name (:ns (meta v)))) (name target)))
            :else (str form)))
    (keyword? form) (str form)
    (nil? form) nil
    :else token-text))
