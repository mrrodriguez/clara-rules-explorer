((nil . ((cider-clojure-cli-aliases . ":dev:test")
          (eval . (let ((clara-home (getenv "CLARA_HOME")))
                    (when (and clara-home (not (string-empty-p clara-home)))
                      (cider-add-to-alist 'cider-jack-in-dependencies
                                          "com.github.gateless/clara-rules"
                                          `(("local/root" . ,clara-home))))))))
 (clojure-mode . ((eval . (progn
                            ;; Apheleia/cljfmt setup
                            (require 'apheleia)
                            (setf (alist-get 'cljfmt apheleia-formatters)
                                  '("cljfmt" "fix" "-"))
                            (setq-local apheleia-formatter 'cljfmt)
                            (apheleia-mode 1))))))
