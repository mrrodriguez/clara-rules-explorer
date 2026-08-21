;;; clara-explorer.el --- Navigate Clara rules fact types from CIDER -*- lexical-binding: t; -*-

;; Copyright (C) 2026 clara-rules-explorer contributors

;; Author: clara-rules-explorer contributors
;; Version: 0.1.0
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
(require 'xref)

(unless (or (featurep 'cider) (ignore-errors (require 'cider nil t)))
  (user-error "clara-explorer requires cider"))
(unless (or (featurep 'parseedn) (ignore-errors (require 'parseedn nil t)))
  (user-error "clara-explorer requires parseedn — add to dotspacemacs-additional-packages"))

(defcustom clara-explorer-debug nil
  "When non-nil, log navigation decisions to *Messages*.
Set via `M-x customize-variable' or `(setq clara-explorer-debug t)' in init."
  :type 'boolean
  :group 'clara-explorer)

(defun clara-explorer--log (fmt &rest args)
  "Log FMT/ARGS to *Messages* when `clara-explorer-debug' is non-nil."
  (when clara-explorer-debug
    (apply #'message (concat "clara-explorer[debug]: " fmt) args)))

(defun clara-explorer--push-jump ()
  "Push current position onto `evil' and `xref' jump lists."
  (when (fboundp 'evil-set-jump)
    (clara-explorer--log "push evil jump at %s:%d" (buffer-name) (point))
    (evil-set-jump))
  (xref-push-marker-stack)
  (clara-explorer--log "push xref marker at %s:%d" (buffer-name) (point)))

(defun clara-explorer--skip-ws ()
  "Skip Clojure whitespace, commas and line comments at point.
Skips spaces, tabs, carriage returns, newlines, commas and any `;' line
comment to end of line, repeatedly until point stops moving.  Uses
`forward-comment' when the buffer syntax table supports it
\(clojure-mode) and falls back to a manual newline skip."
  (let (moved)
    (while (progn
             (setq moved nil)
             (when (/= 0 (skip-chars-forward " \t\r\n,"))
               (setq moved t))
             (when (eq (char-after) ?\;)
               (if (ignore-errors (forward-comment 1))
                   (setq moved t)
                 (skip-chars-forward "^\n")
                 (when (eq (char-after) ?\n)
                   (forward-char 1))
                 (setq moved t)))
             moved))))


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
      (clara-explorer--skip-ws)
      (let ((head-start (point)))
        (condition-case nil
            (progn
              (forward-sexp 1)
              (buffer-substring-no-properties head-start (point)))
          (error nil))))))

(defun clara-explorer--skip-metadata ()
  "Skip Clojure metadata forms at point (^ + form)."
  (while (looking-at-p "\\^")
    (forward-char 1)
    (clara-explorer--skip-ws)
    (condition-case nil
        (forward-sexp 1)
      (error (forward-char 1)))
    (clara-explorer--skip-ws)))

(defun clara-explorer--after-head-point (form-start)
  "Point after the production name at FORM-START, skipping metadata and name."
  (save-excursion
    (goto-char form-start)
    (down-list 1)
    (forward-sexp 1)
    (clara-explorer--skip-ws)
    (clara-explorer--skip-metadata)
    (condition-case nil (forward-sexp 1) (error nil))
    (point)))

(defun clara-explorer--docstring-bounds (form-start)
  "Docstring bounds after production name at FORM-START, or nil."
  (save-excursion
    (goto-char (clara-explorer--after-head-point form-start))
    (clara-explorer--skip-ws)
    (when (eq (char-after) ?\")
      (let ((beg (point)))
        (forward-sexp 1)
        (cons beg (point))))))

(defun clara-explorer--docstring-token-at-point (form-start)
  "Inner fact-type token when point is in docstring."
  (let ((bounds (clara-explorer--docstring-bounds form-start)))
    (when (and bounds (>= (point) (car bounds)) (<= (point) (cdr bounds)))
      (let* ((str-beg (car bounds))
             (str-end (cdr bounds))
             (content-beg (1+ str-beg))
             (content-end (1- str-end)))
        (when (and (>= (point) content-beg) (<= (point) (1+ content-end)))
          (let* ((inner (buffer-substring-no-properties content-beg content-end))
                 (offset (- (point) content-beg))
                 (len (length inner))
                 (tok-chars "A-Za-z0-9._:/!?*+<>-"))
            (when (and (> offset 0) (<= offset len)
                       (or (= offset len)
                           (not (string-match-p (format "[%s]" tok-chars)
                                                (substring inner offset (1+ offset)))))
                       (string-match-p (format "[%s]" tok-chars)
                                       (substring inner (1- offset) offset)))
              (setq offset (1- offset)))
            (when (and (>= offset 0) (< offset len)
                       (string-match-p (format "[%s]" tok-chars)
                                       (substring inner offset (1+ offset))))
              (let ((start offset) (end (1+ offset)))
                (while (and (> start 0)
                            (string-match-p (format "[%s]" tok-chars)
                                            (substring inner (1- start) start)))
                  (setq start (1- start)))
                (while (and (< end len)
                            (string-match-p (format "[%s]" tok-chars)
                                            (substring inner end (1+ end))))
                  (setq end (1+ end)))
                (let ((tok (substring inner start end)))
                  (when (and tok (not (string-empty-p tok))
                             (or (string-match-p "[:/.]" tok)
                                 (let ((case-fold-search nil))
                                   (string-match-p "^[A-Z]" tok))
                                 (string-match-p "->" tok)))
                    tok))))))))))

(defun clara-explorer--enclosing-production ()
  "Return (NAME KIND FORM-START) of the enclosing defrule/defquery form, or nil.
   NAME is the unqualified production name, KIND is `rule' or `query', and
   FORM-START is the buffer position of the opening paren."
  (save-excursion
    (let ((ppss (syntax-ppss)))
      (when (nth 3 ppss)
        (goto-char (nth 8 ppss))))
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
                    (clara-explorer--skip-ws)
                    (forward-sexp 1)          ; skip the head
                    (clara-explorer--skip-ws)
                    (clara-explorer--skip-metadata)
                    (let ((name-start (point)))
                      (condition-case nil
                          (forward-sexp 1)
                        (error (throw 'found nil)))
                      (throw 'found
                             (list (buffer-substring-no-properties name-start (point))
                                   (clara-explorer--production-kind head)
                                   start))))))))
        (error nil)))))

(defun clara-explorer--top-level-=> (form-start)
  "Buffer position of the top-level => in the form at FORM-START, or nil."
  (save-excursion
    (goto-char form-start)
    (let ((end (save-excursion (forward-list 1) (point))))
      (down-list 1)
      (let (found)
        (while (and (not found) (< (point) end))
          (condition-case nil
              (forward-sexp 1)
            (error (goto-char end)))
          (clara-explorer--skip-ws)
          (when (and (< (point) end)
                     (looking-at-p "=>"))
            (setq found (point))))
        found))))

(defun clara-explorer--side-at-point (form-start)
  "Return :lhs or :rhs for FORM-START. Props vectors are :rhs."
  (if (clara-explorer--props-type-at-point form-start)
      :rhs
    (let ((=>-pos (clara-explorer--top-level-=> form-start)))
      (cond
       ((and =>-pos (< (point) =>-pos)) :lhs)
       (=>-pos :rhs)
       (t :lhs)))))

(defun clara-explorer--string-at-point ()
  "String literal at point."
  (save-excursion
    (let* ((ppss (syntax-ppss))
           (in-str (nth 3 ppss))
           (start (nth 8 ppss)))
      (cond
       (in-str
        (goto-char start)
        (forward-sexp 1)
        (buffer-substring-no-properties start (point)))
       ((eq (char-after) ?\")
        (let ((beg (point)))
          (forward-sexp 1)
          (buffer-substring-no-properties beg (point))))
       (t nil)))))

(defconst clara-explorer--logical-operator-rx
  (rx bos ":" (or "and" "or" "not" "exists") eos)
  "Regexp matching exactly `:and', `:or', `:not' or `:exists'.")

(defconst clara-explorer--fact-vector-prefix-rx
  (rx bos "[" (* (any " \t\n")) ":")
  "Regexp matching a vector that starts with a keyword.")

(defconst clara-explorer--logical-wrapper-vector-rx
  (rx bos "[" (* (any " \t\n")) ":" (or "and" "or" "not" "exists") word-boundary)
  "Regexp matching a vector whose first element is a logical wrapper.")

(defconst clara-explorer--fact-forbidden-chars-rx
  (rx (any "()?"))
  "Chars that disqualify a vector from being a plain fact vector.")

(defun clara-explorer--skip-fact-binding ()
  "Skip a leading `?var <-` binding at point, if present.
Assumes point is just inside the condition vector after `down-list`."
  (when (and (eq (char-after) ?\?)
             (save-excursion
               (forward-sexp 1)
               (clara-explorer--skip-ws)
               (looking-at-p "<-")))
    (forward-sexp 1)
    (clara-explorer--skip-ws)
    (forward-sexp 1)
    (clara-explorer--skip-ws)))

(defun clara-explorer--accumulator-type-bounds ()
  "Return TYPE bounds for an accumulator condition at point, or nil.
Point must be at the start of the accumulator expression, which may be
either a parenthesized form `(<accum> ...)` — e.g. `(acc/all)` or
`(my-sort-by-acc :x)` — or a bare symbol referencing a predefined
accumulator, e.g. `my-shared-accum`.  In either case the expression is
followed by `:from [TYPE ...]`; on success return `(TYPE-BEG . TYPE-END)`
for the first element inside the `:from` vector, otherwise return nil
without moving point."
  ;; Use `save-excursion` so failure leaves point unchanged for the
  ;; caller’s fallback (plain fact-type) branch in
  ;; `clara-explorer--type-bounds-in-condition`.
  (let ((bounds
         (save-excursion
           (when (not (eobp))
             (ignore-errors
               (let ((start (point)))
                 (forward-sexp 1)
                 (when (> (point) start)
                   (clara-explorer--skip-ws)
                   (when (looking-at-p ":from")
                     (forward-sexp 1)
                     (clara-explorer--skip-ws)
                     (when (eq (char-after) ?\[)
                       (down-list 1)
                       (clara-explorer--skip-ws)
                       (let ((type-beg (point)))
                         (forward-sexp 1)
                         (cons type-beg (point))))))))))))
    bounds))

(defun clara-explorer--logical-operator-p (kw)
  "Non-nil when KW is exactly `:and', `:or', `:not' or `:exists'."
  (string-match-p clara-explorer--logical-operator-rx kw))

(defun clara-explorer--wrapper-inner-type-bounds (beg orig)
  "For wrapper vector at BEG, return fact-type bounds of inner containing ORIG.
BEG must be a vector whose first element is a logical operator
\(`:and'/`:or'/`:not'/`:exists').  Recurses for nested wrappers."
  (let ((wrapper-end (save-excursion
                       (goto-char beg)
                       (forward-sexp 1)
                       (point))))
    (clara-explorer--skip-ws)
    (catch 'found
      (while (< (point) wrapper-end)
        (clara-explorer--skip-ws)
        (when (>= (point) wrapper-end)
          (throw 'found nil))
        (cond
         ((eq (char-after) ?\[)
          (let ((inner-beg (point))
                (inner-end (save-excursion (forward-sexp 1) (point))))
            (if (and (>= orig inner-beg) (<= orig inner-end))
                (throw 'found (clara-explorer--type-bounds-in-condition-at-point inner-beg orig))
              (goto-char inner-end))))
         ((eq (char-after) ?\()
          (forward-sexp 1))
         (t
          (forward-sexp 1)))
        (clara-explorer--skip-ws))
      nil)))

(defun clara-explorer--type-bounds-in-condition (beg)
  "For condition vector at BEG return (BEG . END) of its fact-type."
  (save-excursion
    (goto-char beg)
    (condition-case nil
        (progn
          (down-list 1)
          (clara-explorer--skip-ws)
          (clara-explorer--skip-fact-binding)
          (or (clara-explorer--accumulator-type-bounds)
              (if (looking-at-p ":[a-z]")
                  (let ((kw-start (point)))
                    (forward-sexp 1)
                    (let ((kw (buffer-substring-no-properties kw-start (point))))
                      (if (clara-explorer--logical-operator-p kw)
                          nil
                        (cons kw-start (point)))))
                (let ((type-beg (point)))
                  (forward-sexp 1)
                  (cons type-beg (point))))))
      (error nil))))

(defun clara-explorer--type-bounds-in-condition-at-point (beg orig)
  "Like `clara-explorer--type-bounds-in-condition' but ORIG-aware.
Handles `:not', `:exists', `:and', `:or' wrappers by recursing to the
inner condition that contains ORIG.  Returns (BEG . END) of the
fact-type or nil."
  (save-excursion
    (goto-char beg)
    (condition-case nil
        (progn
          (down-list 1)
          (clara-explorer--skip-ws)
          (clara-explorer--skip-fact-binding)
          (or (clara-explorer--accumulator-type-bounds)
              (if (looking-at-p ":[a-z]")
                  (let ((kw-start (point)))
                    (forward-sexp 1)
                    (let ((kw (buffer-substring-no-properties kw-start (point))))
                      (if (clara-explorer--logical-operator-p kw)
                          (clara-explorer--wrapper-inner-type-bounds beg orig)
                        (cons kw-start (point)))))
                (let ((type-beg (point)))
                  (forward-sexp 1)
                  (cons type-beg (point))))))
      (error nil))))

(defun clara-explorer--lhs-type-at-point (form-start)
  (save-excursion
    (let* ((orig (point))
           (=>-pos (clara-explorer--top-level-=> form-start))
           (lhs-end (or =>-pos (point-max)))
           (found nil))
      (goto-char (clara-explorer--after-head-point form-start))
      (clara-explorer--skip-ws)
      (when (eq (char-after) ?\")
        (forward-sexp 1)
        (clara-explorer--skip-ws))
      (when (eq (char-after) ?\{)
        (forward-sexp 1)
        (clara-explorer--skip-ws))
      (clara-explorer--skip-ws)
      (while (and (not found) (< (point) lhs-end))
        (clara-explorer--skip-ws)
        (when (>= (point) lhs-end) (error "end"))
        (let ((cond-beg (point))
              (cond-end (save-excursion (forward-sexp 1) (point))))
          (when (and (eq (char-after cond-beg) ?\[)
                     (>= orig cond-beg) (<= orig cond-end))
            (let ((bounds (clara-explorer--type-bounds-in-condition-at-point cond-beg orig)))
              (when bounds
                (setq found (buffer-substring-no-properties (car bounds) (cdr bounds))))))
          (goto-char cond-end)
          (clara-explorer--skip-ws))
        )
      found)))


(defun clara-explorer--props-type-at-point (form-start)
  "If point in props insert/retract vector, return element at point."
  (clara-explorer--log "props check at %d form-start %d" (point) form-start)
  (save-excursion
    (let ((orig (point)) found)
      (goto-char (clara-explorer--after-head-point form-start))
      (clara-explorer--skip-ws)
      (when (eq (char-after) ?\")
        (forward-sexp 1)
        (clara-explorer--skip-ws))
      (clara-explorer--log "props after name char %c at %d" (char-after (point)) (point))
      (when (eq (char-after) ?\{)
        (let ((map-beg (point))
              (map-end (save-excursion (forward-sexp 1) (point))))
          (when (and (>= orig map-beg) (<= orig map-end))
            (goto-char map-beg)
            (down-list 1)
            (while (and (not found) (< (point) map-end))
              (clara-explorer--skip-ws)
              (when (< (point) map-end)
                (let ((k-beg (point)))
                  (forward-sexp 1)
                  (let ((k-str (buffer-substring-no-properties k-beg (point))))
                    (clara-explorer--skip-ws)
                    (let ((v-beg (point))
                          (v-end (save-excursion (forward-sexp 1) (point))))
                      (when (and (member k-str '(":clara-rules/insert-types" ":clara-rules/retract-types"
                                                 ":insert-types" ":retract-types"))
                                 (eq (char-after v-beg) ?\[)
                                 (>= orig v-beg) (<= orig v-end))
                        (goto-char v-beg)
                        (down-list 1)
                        (while (and (not found) (< (point) v-end))
                          (clara-explorer--skip-ws)
                          (when (< (point) v-end)
                            (let ((e-beg (point))
                                  (e-end (save-excursion (forward-sexp 1) (point))))
                              (when (and (>= orig e-beg) (<= orig e-end))
                                (setq found (cons e-beg e-end)))
                              (goto-char e-end)
                              (clara-explorer--skip-ws)))))
                      (goto-char v-end)
                      (clara-explorer--skip-ws)))))))))
      (when found
        (buffer-substring-no-properties (car found) (cdr found))))))

(defun clara-explorer--vector-fact-at-point ()
  "Innermost vector fact-type at point, or nil.
Returns the `[...]` text when point is inside a keyword-led tuple vector such
as `[:loan/status \"verified\"]` or `[:my-thing]` / `[:my-thing :qual]`.
Used for RHS and global cases where LHS-structure is not applicable."
  (save-excursion
    (let* ((orig (point))
           (ppss (syntax-ppss))
           (in-str (nth 3 ppss))
           (probe (if in-str (nth 8 ppss) orig))
           found)
      (goto-char probe)
      (condition-case nil
          (let ((open (nth 1 (syntax-ppss))))
            (when (and open (eq (char-after open) ?\[))
              (goto-char open)
              (let ((beg open)
                    (end (save-excursion (forward-sexp 1) (point))))
                (when (and (>= orig beg) (<= orig end))
                  (let ((s (buffer-substring-no-properties beg end)))
                    (when (and (string-match-p clara-explorer--fact-vector-prefix-rx s)
                               (not (string-match-p clara-explorer--logical-wrapper-vector-rx s))
                               (not (string-match-p clara-explorer--fact-forbidden-chars-rx s))
                               (not (string-match-p "=" s)))
                      (setq found s)))))))
        (error nil))
      found)))

(defun clara-explorer--token-at-point (&optional form-start side)
  "Fact-type token at point."
  (cond
   ((and form-start (clara-explorer--docstring-token-at-point form-start)))
   ((and form-start (clara-explorer--props-type-at-point form-start)))
   ((and form-start (eq side :lhs) (clara-explorer--lhs-type-at-point form-start)))
   ((clara-explorer--vector-fact-at-point))
   ((clara-explorer--string-at-point))
   (t (let ((tok (cider-symbol-at-point 'look-back)))
        (when tok (substring-no-properties tok))))))

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
         (token (clara-explorer--token-at-point form-start side)))
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

(defconst clara-explorer--fallback-head-rx
  (rx "(" (* (not (any " \t\n()"))) "def" (or "rule" "query"))
  "Rx for \"(alias/defrule\" prefix up to head, without rule name.")

(defun clara-explorer--fallback-regexp (rule-name)
  "Regexp for fallback search: (defrule/defquery [^meta]* RULE-NAME\\b."
  (concat clara-explorer--fallback-head-rx
          (rx (* (seq (+ (any " \t\n")) "^" (+ (not (any " \t\n")))))
              (+ (any " \t\n")))
          (regexp-quote rule-name) "\\b"))

(defun clara-explorer--goto-fallback (target)
  "Last resort: open the ns file and search for the defrule/defquery form."
  (let* ((name (clara-explorer--edn-get :name target))
         (ns (clara-explorer--edn-get :ns target))
         (rule-name (clara-explorer--unqualified-name name)))
    (clara-explorer--log "fallback: name=%S ns=%S rule-name=%S" name ns rule-name)
    (when (and ns rule-name)
      (clara-explorer--log "fallback: cider-find-ns %S" ns)
      (cider-find-ns nil ns)
      (goto-char (point-min))
      (let ((found (or (re-search-forward
                        (clara-explorer--fallback-regexp rule-name)
                        nil t)
                       ;; fallback for ^{:map} metadata or other forms
                       (re-search-forward
                        (format "\\b%s\\b" (regexp-quote rule-name))
                        nil t))))
        (clara-explorer--log "fallback: search %S -> %s at %d" rule-name (if found "found" "NOT-FOUND") (point))
        found))))

(defun clara-explorer--goto (target)
  "Jump to target.  Pushes evil/xref jumps for C-o."
  (let* ((name (clara-explorer--edn-get :name target))
         (source (clara-explorer--edn-get :source target))
         (var? (clara-explorer--edn-get :var? source)))
    (clara-explorer--log "goto: name=%S var?=%S source=%S" name var? source)
    (clara-explorer--push-jump)
    (if var?
        (progn
          (clara-explorer--log "goto: cider-find-var %S" name)
          (cider-find-var nil name))
      (clara-explorer--log "goto: fallback path")
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
      (let* ((eff-side side)
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
