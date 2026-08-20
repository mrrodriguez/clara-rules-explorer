;;; test-helper.el --- test stubs for clara-explorer -*- lexical-binding: t; -*-

;; Stubs so tests run without a live CIDER nREPL.
;; Real cider/parseedn are provided by Eldev when available;
;; when running plain `emacs -Q --batch` we provide them ourselves
;; and autoload the few vars the byte-compiler expects.

(require 'cl-lib)

(unless (featurep 'cider)
  (provide 'cider))
(unless (featurep 'parseedn)
  (provide 'parseedn))
(unless (featurep 'clojure-mode)
  (defun clojure-mode ()
    "Minimal clojure-mode for tests: make { } [ ] and : work with syntax-ppss."
    (setq-local syntax-table (let ((st (make-syntax-table)))
                               (modify-syntax-entry ?\{ "(}" st)
                               (modify-syntax-entry ?\} "){" st)
                               (modify-syntax-entry ?\[ "(]" st)
                               (modify-syntax-entry ?\] ")[" st)
                               (modify-syntax-entry ?: "_" st)
                               (modify-syntax-entry ?. "_" st)
                               (modify-syntax-entry ?/ "_" st)
                               (modify-syntax-entry ?- "_" st)
                               (modify-syntax-entry ?_ "_" st)
                               (modify-syntax-entry ?? "_" st)
                               (modify-syntax-entry ?! "_" st)
                               (modify-syntax-entry ?* "_" st)
                               (modify-syntax-entry ?+ "_" st)
                               (modify-syntax-entry ?% "_" st)
                               (modify-syntax-entry ?< "_" st)
                               (modify-syntax-entry ?> "_" st)
                               (modify-syntax-entry ?= "_" st)
                               (modify-syntax-entry ?\; "<" st)
                               (modify-syntax-entry ?\n ">" st)
                               st))
    (setq-local comment-start ";")
    (setq-local parse-sexp-ignore-comments t))
  (provide 'clojure-mode))

;; Autoloads expected by check-elisp / batch byte-compile
(mapc (lambda (s) (autoload s "cider"))
      '(cider-connected-p cider-current-repl cider-current-ns
        cider-symbol-at-point cider-find-var cider-find-ns
        cider-nrepl-sync-request:eval))
(autoload 'parseedn-read-str "parseedn")
(autoload 'nrepl-dict-get "nrepl-dict")

;; Common mock: bounds-of-thing-at-point 'symbol excludes ":" in fundamental-mode,
;; but cider-symbol-at-point in clojure-mode includes it.  Tests that need
;; keyword precision should mock cider-symbol-at-point directly via cl-letf.

(defun test-helper--mock-cider-symbol (text)
  "Return a mock `cider-symbol-at-point' that returns TEXT (or nil)."
  (lambda (&optional _arg) text))

(provide 'test-helper)
;;; test-helper.el ends here
