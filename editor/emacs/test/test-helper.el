;;; test-helper.el --- test stubs for clara-explorer -*- lexical-binding: t; -*-

;; Stubs so the 35 structural/EDN tests run without a live CIDER nREPL.
;; Three tiers:
;;   Tier 1 — plain `emacs -Q --batch` (stubbed deps): `make test-unit` fallback.
;;            `cider' / `parseedn' / `clojure-mode' are `provide'd here,
;;            syntax table is minimal, nREPL dict is a small stub.
;;   Tier 2 — `eldev test` (real deps): Eldev installs real `cider "1.12"`,
;;            `parseedn "1.2"`, `clojure-mode "5.18"` from `Eldev`. This file's
;;            `unless (featurep ...)` guards become no-ops so real impls win,
;;            and the new `*-real-*` tests exercise them.
;;   Tier 3 — live server + nREPL integration: deferred (see
;;            `docs/planning/explorer-server-emacs-testing.md`). Requires a
;;            running `clara.server.graph` JVM + `cider-connect-clj`.
;;
;; Real deps are preferred when present; stubs exist only for the
;; bare-batch fallback and for `server/bin/ci/check-elisp.sh` byte-compile.

(require 'cl-lib)

;; ---------------------------------------------------------------------------
;; Feature stubs — only when the real package is not already loaded (Eldev).
;; ---------------------------------------------------------------------------

(unless (or (featurep 'cider) (ignore-errors (require 'cider nil t)))
  (provide 'cider))
(unless (or (featurep 'parseedn) (ignore-errors (require 'parseedn nil t)))
  (provide 'parseedn))
(unless (or (featurep 'clojure-mode) (ignore-errors (require 'clojure-mode nil t)))
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

;; ---------------------------------------------------------------------------
;; Autoloads expected by check-elisp / batch byte-compile.
;; When real packages are present these are ignored (functions already fbound).
;; ---------------------------------------------------------------------------

(mapc (lambda (s) (autoload s "cider"))
      '(cider-connected-p cider-current-repl cider-current-ns
        cider-symbol-at-point cider-find-var cider-find-ns
        cider-nrepl-sync-request:eval))
(autoload 'parseedn-read-str "parseedn")
(autoload 'nrepl-dict-get "nrepl-dict")
(autoload 'nrepl-dict-put "nrepl-dict")

;; ---------------------------------------------------------------------------
;; Minimal nREPL dict stub — only defined when real `nrepl-dict' is absent.
;; Real cider (Eldev) provides a richer impl; this handles the mock dict
;; shapes used in `cl-letf' tests: hash-table, alist, or `(dict "k" "v" ...)`
;; plist.
;; ---------------------------------------------------------------------------

(unless (fboundp 'nrepl-dict-get)
  (defun nrepl-dict-get (dict key)
    "Lookup KEY in DICT.  Supports hash-table, alist, and `(dict ...)` plist."
    (cond
     ((null dict) nil)
     ((hash-table-p dict) (gethash key dict))
     ((and (listp dict) (eq (car dict) 'dict))
      (plist-get (cdr dict) key))
     ((and (listp dict) (consp (car dict)))
      ;; alist of (key . val) with string keys
      (cdr (assoc key dict)))
     ((listp dict)
      ;; flat plist (\"k\" \"v\" ...) without leading `dict`
      (plist-get dict key))
     (t nil))))

(unless (fboundp 'nrepl-dict-put)
  (defun nrepl-dict-put (dict key val)
    "Put KEY VAL into DICT (stub: returns new dict)."
    (cond
     ((hash-table-p dict) (puthash key val dict) dict)
     ((and (listp dict) (eq (car dict) 'dict))
      (cons 'dict (plist-put (cdr dict) key val)))
     (t (cons key (cons val dict))))))

;; ---------------------------------------------------------------------------
;; Minimal parseedn stub — only when real parseedn is absent.
;; The real library (parseedn 20231203) returns hash-tables for EDN maps.
;; For bare-batch runs we provide a tiny subset sufficient for the smoke
;; tests; full EDN round-trip coverage is gated behind `skip-unless`
;; and runs only under Eldev with the real library.
;; ---------------------------------------------------------------------------

(unless (fboundp 'parseedn-read-str)
  (defun parseedn-read-str (str)
    "Minimal EDN reader for stubbed tests: delegates to `read' with keyword fixup."
    (let ((result (car (read-from-string str))))
      ;; `read' returns symbols for EDN keywords (`:kw` -> `:kw` symbol) which
      ;; is close enough for stub tier; real parseedn returns same.
      result)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defun test-helper--mock-cider-symbol (text)
  "Return a mock `cider-symbol-at-point' that returns TEXT (or nil)."
  (lambda (&optional _arg) text))

(defun test-helper--real-deps-p ()
  "Non-nil when running under Eldev with real cider/parseedn/clojure-mode."
  (and (featurep 'cider) (featurep 'parseedn) (featurep 'clojure-mode)
       (fboundp 'cider-symbol-at-point)
       (fboundp 'parseedn-read-str)
       ;; autoload objects are not real impls
       (not (autoloadp (symbol-function 'cider-symbol-at-point)))
       (not (autoloadp (symbol-function 'parseedn-read-str)))))

(defun test-helper--parseedn-real-p ()
  "Non-nil when `parseedn-read-str' is the real library, not the stub."
  (and (featurep 'parseedn)
       (fboundp 'parseedn-read-str)
       (not (autoloadp (symbol-function 'parseedn-read-str)))
       ;; real parseedn returns hash-table for \"{}\"; stub may return nil/list
       (ignore-errors
         (let ((v (parseedn-read-str "{}")))
           (hash-table-p v)))))

(defun test-helper--report-tier ()
  "Print one-line tier banner for batch runs (visible in `make test-unit')."
  (let* ((real (test-helper--real-deps-p))
         (tier (if real "Tier 2 \u2014 real cider/parseedn/clojure-mode (Eldev)"
                 "Tier 1 \u2014 stubbed cider/parseedn/clojure-mode (plain emacs -Q --batch)"))
         (skipped-msg (if real "0 skipped (all real-deps tests active)"
                        "5 skipped (real-deps tests gated via skip-unless)")))
    (message "clara-explorer tests: %s \u2014 %s" tier skipped-msg)
    (when (and (not real) noninteractive)
      (message "  hint: run `eldev test` or `make test-unit` with eldev on PATH to exercise Tier 2 (real parseedn hash-table + cider-symbol)"))))

;; Emit banner eagerly in batch (both Eldev and plain fallback). In
;; interactive `M-x eval-buffer` it also prints once, which is harmless.
(when noninteractive
  (test-helper--report-tier))

(provide 'test-helper)
;;; test-helper.el ends here
