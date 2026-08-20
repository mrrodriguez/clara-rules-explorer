;;; clara-explorer.el --- Navigate Clara rules fact types from CIDER -*- lexical-binding: t; -*-

;; Copyright (C) 2026 clara-rules-explorer contributors

;; Author: clara-rules-explorer contributors
;; Keywords: clojure, tools
;; Package-Requires: ((emacs "28.1") (cider "1.12") (parseedn "1.2") (clojure-mode "5.18"))

;;; Commentary:
;;
;; Editor navigation over a live CIDER REPL running the Clara Rules Explorer
;; server.  Point at a fact type in a defrule/defquery and jump to its producer
;; (LHS) or consumer (RHS), using the dependency graph the server has already
;; computed.
;;
;; All semantics live in `clara.server.graph.client/navigate` (Clojure); this
;; file is structural navigation + transport + UX glue.  No machine-specific
;; paths, home directories, or ports are hard-coded anywhere.
;;
;; Spike workflow: open this file, M-x eval-buffer, then:
;;   M-x clara-explorer-navigate-producer   (LHS type -> producer)
;;   M-x clara-explorer-navigate-consumer   (RHS type -> consumer)
;;   M-x clara-explorer-refresh             (re-warm annotations/analysis)
;;   M-x clara-explorer-swap-session        (swap in a rebuilt session)

;;; Code:

(when load-file-name
  (add-to-list 'load-path (file-name-directory load-file-name)))

(require 'cl-lib)

(unless (featurep 'cider)
  (user-error "clara-explorer requires cider"))
(unless (featurep 'parseedn)
  (user-error "clara-explorer requires parseedn — add to dotspacemacs-additional-packages"))

;; ---------------------------------------------------------------------------
;; EDN transport (§9.3, §9.8)
;; ---------------------------------------------------------------------------

;; parseedn contract (parseedn 20231203): EDN maps -> elisp hash-tables
;; (:test 'equal), EDN vectors -> elisp vectors, EDN keywords -> elisp
;; keywords, `false' -> nil, `true' -> t.  The accessors and target handling
;; below rely on exactly this contract, not on any historical alist form.

(defun clara-explorer--edn-get (key map)
  "Look up KEY in a parseedn map (a hash-table)."
  (gethash key map))

(defun clara-explorer--edn-value (x)
  "Print an elisp value X as an EDN literal string."
  (cond
   ((null x) "nil")
   ((eq x t) "true")
   ((stringp x) (prin1-to-string (substring-no-properties x)))
   ((symbolp x) (substring-no-properties (symbol-name x)))
   (t (prin1-to-string x))))

(defun clara-explorer--edn-map (entries)
  "Build a Clojure map literal from ENTRIES (a plist of :key value ...).
   Entries with nil values are omitted, so optional keys collapse naturally."
  (let ((parts '()))
    (while entries
      (let ((k (car entries))
            (v (cadr entries)))
        (unless (null v)
          (push (format "%s %s" (symbol-name k) (clara-explorer--edn-value v))
                parts)))
      (setq entries (cddr entries)))
    (concat "{" (mapconcat #'identity (nreverse parts) " ") "}")))

(defun clara-explorer--err-summary (err)
  "Return a concise summary of an nREPL stack-trace string ERR: the leading
   message line plus any \"Caused by:\" root-cause line."
  (when (and (stringp err) (not (string-empty-p err)))
    (let* ((lines (split-string err "\n" t " "))
           (head (car lines))
           (cause (cl-find-if (lambda (l) (string-prefix-p "Caused by:" l)) lines)))
      (concat head (when cause (concat " | " cause))))))

(defun clara-explorer--eval-edn (code conn)
  "Eval Clojure CODE (a string) over CONN and parse the printed EDN result.
   On failure, logs the full nREPL stack trace (plus CODE) to the *Messages*
   buffer and signals a concise `user-error' with the exception class and
   root-cause message, so a navigation failure is never reduced to a bare
   exception class."
  (let* ((resp (cider-nrepl-sync-request:eval code conn))
         (val  (nrepl-dict-get resp "value"))
         (ex   (nrepl-dict-get resp "ex"))
         (root (nrepl-dict-get resp "root-ex"))
         (err  (nrepl-dict-get resp "err")))
    (cond
     (val (parseedn-read-str val))
     ((or ex err)
      (when (and err (not (string-empty-p err)))
        (message "clara-explorer nREPL error:\n%s\n--- code ---\n%s" err code))
      (user-error "clara-explorer: %s%s"
                  (or ex root "eval failed")
                  (if-let ((summary (clara-explorer--err-summary err)))
                      (format " — %s" summary)
                    "")))
     (t nil))))

;; ---------------------------------------------------------------------------
;; Structural navigation (§9.2)
;; ---------------------------------------------------------------------------

(defun clara-explorer--symbol-name (sym)
  "The unqualified name portion of a symbol string like \"r/defrule\"."
  (if (string-match "/\\([^/]*\\)$" sym)
      (match-string 1 sym)
    sym))

(defun clara-explorer--def-head-p (head)
  "Non-nil when HEAD names a defrule/defquery form (alias-prefix agnostic)."
  (and head
       (let ((n (clara-explorer--symbol-name head)))
         (or (string= n "defrule")
             (string= n "defquery")))))

(defun clara-explorer--production-kind (head)
  "Return `rule' or `query' for a defrule/defquery head string."
  (if (string= (clara-explorer--symbol-name head) "defrule")
      'rule
    'query))

(defun clara-explorer--list-head (start)
  "Return the head symbol (as a string) of the list starting at START, or nil."
  (save-excursion
    (goto-char start)
    (when (looking-at-p "(")
      (forward-char 1)
      (skip-chars-forward " \t\n,")
      (let ((head-start (point)))
        (condition-case nil
            (progn
              (forward-sexp 1)
              (buffer-substring-no-properties head-start (point)))
          (error nil))))))

(defun clara-explorer--enclosing-production ()
  "Return (NAME KIND FORM-START) of the enclosing defrule/defquery form, or nil.
   NAME is the unqualified production name, KIND is `rule' or `query', and
   FORM-START is the buffer position of the opening paren."
  (save-excursion
    (catch 'found
      (condition-case nil
          (while t
            (backward-up-list)
            (let ((start (point)))
              (when (looking-at-p "(")
                (let ((head (clara-explorer--list-head start)))
                  (when (clara-explorer--def-head-p head)
                    (goto-char start)
                    (forward-char 1)
                    (skip-chars-forward " \t\n,")
                    (forward-sexp 1)          ; skip the head
                    (skip-chars-forward " \t\n,")
                    (let ((name-start (point)))
                      (forward-sexp 1)
                      (throw 'found
                             (list (buffer-substring-no-properties name-start (point))
                                   (clara-explorer--production-kind head)
                                   start))))))))
        (error nil)))))

(defun clara-explorer--top-level-=> (form-start)
  "Buffer position of the top-level => in the form at FORM-START, or nil."
  (save-excursion
    (goto-char form-start)
    (down-list 1)
    (let ((end (save-excursion (forward-list 1) (point)))
          found)
      (while (and (not found) (< (point) end))
        (forward-sexp 1)
        (skip-chars-forward " \t\n,")
        (when (and (< (point) end)
                   (looking-at-p "=>"))
          (setq found (point))))
      found)))

(defun clara-explorer--side-at-point (form-start)
  "Return :lhs or :rhs based on whether point is before/after the top-level =>.
   Queries (no =>) return :lhs."
  (let ((=>-pos (clara-explorer--top-level-=> form-start)))
    (cond
     ((and =>-pos (< (point) =>-pos)) :lhs)
     (=>-pos :rhs)
     (t :lhs))))

(defun clara-explorer--token-at-point ()
  "The fact-type token at point, or nil."
  (let ((tok (cider-symbol-at-point 'look-back)))
    (when tok
      (substring-no-properties tok))))

(defun clara-explorer--context ()
  "Gather navigation context at point.
   Returns a plist (:production FQ-OR-NIL :kind RULE|QUERY|NIL :side LHS|RHS|NIL
                    :caller-ns \"...\" :token \"...\")."
  (let* ((enclosing (clara-explorer--enclosing-production))
         (name (nth 0 enclosing))
         (kind (nth 1 enclosing))
         (form-start (nth 2 enclosing))
         (caller-ns (when (cider-connected-p)
                      (let ((ns (cider-current-ns)))
                        (when ns
                          (substring-no-properties ns)))))
         (production (and name caller-ns
                          (substring-no-properties (format "%s/%s" caller-ns name))))
         (side (and form-start (clara-explorer--side-at-point form-start)))
         (token (clara-explorer--token-at-point)))
    (list :production production :kind kind :side side
          :caller-ns caller-ns :token token)))

;; ---------------------------------------------------------------------------
;; Remote navigation (§9.3, §9.4)
;; ---------------------------------------------------------------------------

(defun clara-explorer--navigate-code (production side caller-ns token)
  "Build the Clojure form string that calls `client/navigate`.
   Uses `requiring-resolve` so the namespace is loaded at runtime rather than
   resolved at compile time (which would fail if the namespace is not yet
   loaded in the REPL)."
  (format "((requiring-resolve 'clara.server.graph.client/navigate) %s)"
          (clara-explorer--edn-map
           (list :production production
                 :side side
                 :caller-ns caller-ns
                 :token token))))

(defun clara-explorer--direction-word (direction)
  "Human word for a NavigateResult direction keyword."
  (pcase direction
    (:producer "producer")
    (:consumer "consumer")
    (:type "consumer")
    (_ "target")))

(defun clara-explorer--target-label (target)
  "Fully-qualified target name, plus \" (retract)\" when :via :retract."
  (let ((name (clara-explorer--edn-get :name target))
        (via (clara-explorer--edn-get :via target)))
    (if (eq via :retract)
        (concat name " (retract)")
      name)))

(defun clara-explorer--unqualified-name (fq-name)
  "The name portion after the last / in FQ-NAME."
  (if (string-match "/\\([^/]*\\)$" fq-name)
      (match-string 1 fq-name)
    fq-name))

(defun clara-explorer--goto-fallback (target)
  "Last resort: open the ns file and search for the defrule/defquery form."
  (let* ((name (clara-explorer--edn-get :name target))
         (ns (clara-explorer--edn-get :ns target))
         (rule-name (clara-explorer--unqualified-name name)))
    (when (and ns rule-name)
      (cider-find-ns nil ns)
      (goto-char (point-min))
      (re-search-forward
       (format "(%sdef\\(rule\\|query\\)[[:space:]\n]+%s\\b"
               "[^ \t\n()]*" rule-name)
       nil t))))

(defun clara-explorer--goto (target)
  "Jump to a target production's source."
  (let* ((name (clara-explorer--edn-get :name target))
         (source (clara-explorer--edn-get :source target))
         (var? (clara-explorer--edn-get :var? source)))
    (if var?
        (cider-find-var nil name)
      (clara-explorer--goto-fallback target))))

(defun clara-explorer--choose-target (direction targets)
  "completing-read popover over fq target names, then jump to the choice."
  (let* ((targets (if (vectorp targets) (append targets nil) targets))
         (labels (mapcar #'clara-explorer--target-label targets))
         (prompt (format "%s: " (capitalize (clara-explorer--direction-word direction))))
         (choice (completing-read prompt labels nil t))
         (idx (cl-position choice labels :test #'string=)))
    (when idx
      (clara-explorer--goto (nth idx targets)))))

(defun clara-explorer--choose-or-jump (direction type targets)
  "Dispatch on target count: message, direct jump, or popover."
  (let* ((targets (if (vectorp targets) (append targets nil) targets))
         (n (length targets)))
    (cond
     ((= n 0) (message "No %s of %s" (clara-explorer--direction-word direction) type))
     ((= n 1) (clara-explorer--goto (elt targets 0)))
     (t (clara-explorer--choose-target direction targets)))))

(defun clara-explorer--handle-result (result)
  "Handle a navigate result: relay an :error, or choose/jump to :targets."
  (let ((err (clara-explorer--edn-get :error result)))
    (if err
        (message "%s" err)
      (clara-explorer--choose-or-jump
       (clara-explorer--edn-get :direction result)
       (clara-explorer--edn-get :type result)
       (clara-explorer--edn-get :targets result)))))

(defun clara-explorer--navigate (side)
  "Shared dispatcher for producer (:lhs) / consumer (:rhs) navigation."
  (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
  (let* ((conn (cider-current-repl 'infer 'ensure))
         (ctx (clara-explorer--context))
         (production (plist-get ctx :production))
         (kind (plist-get ctx :kind))
         (caller-ns (plist-get ctx :caller-ns))
         (token (plist-get ctx :token)))
    (cond
     ((null token) (message "not on a fact type"))
     ((and (eq side :rhs) (eq kind 'query))
      (message "queries have no RHS"))
     ((and (null production) (eq side :lhs))
      (message "not inside a rule/query"))
     (t
      (let* ((eff-side (if production side nil))
             (result (clara-explorer--eval-edn
                      (clara-explorer--navigate-code production eff-side caller-ns token)
                      conn)))
        (if result
            (clara-explorer--handle-result result)
          (message "clara-explorer: no result from navigate")))))))

;; ---------------------------------------------------------------------------
;; Public commands (§9.6, §9.9)
;; ---------------------------------------------------------------------------

;;;###autoload
(defun clara-explorer-navigate-producer ()
  "Navigate from an LHS fact type at point to its producer(s)."
  (interactive)
  (clara-explorer--navigate :lhs))

;;;###autoload
(defun clara-explorer-navigate-consumer ()
  "Navigate from an RHS fact type at point to its consumer(s)."
  (interactive)
  (clara-explorer--navigate :rhs))

;;;###autoload
(defun clara-explorer-refresh ()
  "Re-derive annotations and re-warm the explorer analysis."
  (interactive)
  (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
  (clara-explorer--eval-edn
   "(do (require 'clara.server.graph.server)\n     (clara.server.graph.server/reload-annotations!))"
   (cider-current-repl 'infer 'ensure))
  (message "clara-explorer: analysis refreshed"))

(defvar clara-explorer--swap-session-exprs (make-hash-table :test 'eq)
  "Map of CIDER connection -> last session-rebuild expression.")

;;;###autoload
(defun clara-explorer-swap-session (expr)
  "Swap in a rebuilt session.  EXPR is a Clojure expression that rebuilds the
   session, wrapped in `(server/swap-session! {:session ...})`.  With a prefix
   argument, always prompt; otherwise reuse the last expression for the
   current connection."
  (interactive
   (list (if (or current-prefix-arg
                 (null (gethash (cider-current-repl 'infer 'ensure)
                                clara-explorer--swap-session-exprs)))
           (read-string "Session expression: ")
         nil)))
  (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
  (let* ((conn (cider-current-repl 'infer 'ensure))
         (expr (or expr (gethash conn clara-explorer--swap-session-exprs))))
    (when (string-empty-p (or expr ""))
      (user-error "No session expression"))
    (puthash conn expr clara-explorer--swap-session-exprs)
    (clara-explorer--eval-edn
     (format "(do (require 'clara.server.graph.server)\n     (clara.server.graph.server/swap-session! {:session %s}))"
             expr)
     conn)
    (message "clara-explorer: session swapped")))

(provide 'clara-explorer)
;;; clara-explorer.el ends here
