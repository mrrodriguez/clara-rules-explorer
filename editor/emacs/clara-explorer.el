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
;; computed; or open an expandable producer/consumer provenance tree around it.
;;
;; All semantics live in `clara.explorer.server.client/navigate` and
;; `clara.explorer.server.client/fact-type-provenance` (Clojure); this file is
;; structural navigation + transport + UX glue.  No machine-specific paths, home
;; directories, or ports are hard-coded anywhere.
;;
;; Spike workflow: open this file, M-x eval-buffer, then:
;;   M-x clara-explorer-navigate-producer   (LHS type -> producer)
;;   M-x clara-explorer-navigate-consumer   (RHS type -> consumer)
;;   M-x clara-explorer-show-producer-provenance  (upstream tree)
;;   M-x clara-explorer-show-consumer-provenance  (downstream tree)
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

(defcustom clara-explorer-transport 'auto
  "Transport for navigation and provenance queries.
`auto' uses nREPL when the CIDER session has a running explorer system,
otherwise babashka (default);
`nrepl' evals `client/navigate' / `client/fact-type-provenance' over the CIDER
session;
`bb' shells out to `editor-client.bb' over the persisted artifacts."
  :type '(choice (const :tag "Auto (nREPL when the explorer server is running, else babashka)" auto)
                 (const :tag "nREPL (live session)" nrepl)
                 (const :tag "babashka (offline artifacts)" bb))
  :group 'clara-explorer)

(defcustom clara-explorer-registry-root nil
  "Registry root for the babashka transport.
When nil, `CLARA_RULES_EXPLORER_REGISTRY' is read from the environment."
  :type '(choice (const :tag "Read CLARA_RULES_EXPLORER_REGISTRY" nil)
                 (directory))
  :group 'clara-explorer)

(defcustom clara-explorer-bb-script nil
  "Path to `editor-client.bb' for the babashka transport.
When nil, it is resolved beside `clara-explorer.el' (a symlink in the repo)."
  :type '(choice (const :tag "Beside clara-explorer.el" nil)
                 (file))
  :group 'clara-explorer)

(defconst clara-explorer--directory
  (file-name-directory (or load-file-name buffer-file-name))
  "Directory containing `clara-explorer.el', captured when it was loaded or eval'd.
Navigation runs later from the user's source buffer, so paths beside this file
must be resolved against this captured value — never recomputed from the
navigation-time `buffer-file-name'.")

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
;; EDN transport
;; ---------------------------------------------------------------------------

;; parseedn contract (parseedn 20231203): EDN maps -> elisp hash-tables
;; (:test 'equal), EDN vectors -> elisp vectors, EDN keywords -> elisp
;; keywords, `false' -> nil, `true' -> t.  The accessors and target handling
;; below rely on exactly this contract, not on any historical alist form.

(defun clara-explorer--edn-get (key map)
  "Look up KEY in a parseedn map (a hash-table)."
  (gethash key map))

(defun clara-explorer--edn-value (x)
  "Print an elisp value X as an EDN literal string.
A keyword-keyed plist prints as a nested map, and a vector prints element-wise,
so an operation input that carries maps and vectors (`fact-type-provenance`'s
`:node` / `:input` / `:ancestors`) serializes without a second printer.  Strings
are printed without their text properties: `prin1-to-string' would emit
`#(\"…\" 0 N (face …))', which is not EDN."
  (cond
   ((null x) "nil")
   ((eq x t) "true")
   ((stringp x) (prin1-to-string (substring-no-properties x)))
   ((symbolp x) (substring-no-properties (symbol-name x)))
   ((vectorp x) (concat "[" (mapconcat #'clara-explorer--edn-value x " ") "]"))
   ((keywordp (car-safe x)) (clara-explorer--edn-map x))
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
                  (if-let* ((summary (clara-explorer--err-summary err)))
                      (format " — %s" summary)
                    "")))
     (t nil))))

;; ---------------------------------------------------------------------------
;; babashka transport
;; ---------------------------------------------------------------------------

(defconst clara-explorer--server-probe-code
  (concat "(try (some? ((requiring-resolve"
          " 'clara.explorer.server.client/get-current-system)))"
          " (catch Throwable _ false))")
  "Form evaluating to `true' when the connected repl has an explorer system.")

(defun clara-explorer--server-available-p (&optional conn)
  "Non-nil when the connected CIDER session has a running explorer system.
Probes `clara.explorer.server.client/get-current-system' via
`requiring-resolve'; a repl without the explorer on its classpath (or no
registered system) resolves to nil."
  (let ((conn (or conn (and (cider-connected-p)
                            (cider-current-repl 'infer 'ensure)))))
    (when conn
      (let* ((resp (cider-nrepl-sync-request:eval clara-explorer--server-probe-code conn))
             (val (and resp (nrepl-dict-get resp "value"))))
        (and (stringp val) (string= val "true"))))))

(defun clara-explorer--effective-transport (&optional conn)
  "Resolve `clara-explorer-transport' to the transport used for this call.
`auto' selects nREPL when the connected CIDER session has a running explorer
system, otherwise babashka."
  (if (eq clara-explorer-transport 'auto)
      (if (clara-explorer--server-available-p conn) 'nrepl 'bb)
    clara-explorer-transport))

(defun clara-explorer--bb-transport-p ()
  "Non-nil when navigation uses the babashka transport."
  (eq (clara-explorer--effective-transport) 'bb))

(defun clara-explorer--registry-root ()
  "The registry root as an absolute path.
Reads `CLARA_RULES_EXPLORER_REGISTRY' if `clara-explorer-registry-root' is nil."
  (let ((root (or clara-explorer-registry-root
                  (getenv "CLARA_RULES_EXPLORER_REGISTRY"))))
    (when root (expand-file-name root))))

(defun clara-explorer--bb-script ()
  "Path to `editor-client.bb': `clara-explorer-bb-script', else beside this file."
  (or clara-explorer-bb-script
      (progn
        (unless clara-explorer--directory
          (user-error "clara-explorer: cannot locate editor-client.bb (eval'd from a non-file buffer?)"))
        (file-truename (expand-file-name "editor-client.bb" clara-explorer--directory)))))

(defvar clara-explorer--bb-selection-cache nil
  "Cached registry-selection EDN for the babashka transport.")

(defun clara-explorer--bb-run (args)
  "Run `bb SCRIPT ARGS…' and return the parsed EDN result.
Signals `user-error' on a missing script or `bb', a non-zero exit, or invalid
EDN.  ARGS is a list of argument strings appended after the script path."
  (let ((script (clara-explorer--bb-script)))
    (unless (file-readable-p script)
      (user-error "clara-explorer: editor-client.bb not found at %s" script))
    (with-temp-buffer
      (let ((status (condition-case err
                        (apply #'call-process "bb" nil (current-buffer) nil
                               (cons script args))
                      (file-error (user-error "clara-explorer: bb not found (%s)"
                                              (error-message-string err))))))
        (unless (eql status 0)
          (user-error "clara-explorer: bb failed (exit %s): %s"
                      status (string-trim (buffer-string))))
        (condition-case err
            (parseedn-read-str (buffer-string))
          (error (user-error "clara-explorer: bb returned invalid EDN: %s"
                             (error-message-string err))))))))

(defun clara-explorer--bb-list-unit-repos (root)
  "`{:key entry}' maps for every unit under ROOT, sorted by key.
Discovery and EDN serialization live in `bb editor-client.bb --list-units' —
the one place that knows the registry layout — so this function only shells
out and returns the result."
  (let* ((result (clara-explorer--bb-run (list "--list-units" root)))
         (err (and (hash-table-p result)
                   (clara-explorer--edn-get :error result))))
    (cond
     (err (user-error "clara-explorer: %s" err))
     ((vectorp result) (append result nil))
     (t (user-error "clara-explorer: unexpected list-units result: %S" result)))))

(defun clara-explorer--bb-prompt-selection ()
  "Prompt for a single-unit registry selection under the registry root."
  (let ((root (clara-explorer--registry-root)))
    (unless (and root (file-directory-p root))
      (user-error "clara-explorer: set CLARA_RULES_EXPLORER_REGISTRY (or clara-explorer-registry-root) to a registry root"))
    (let ((repos (clara-explorer--bb-list-unit-repos root)))
      (when (null repos)
        (user-error "clara-explorer: no units (rules-inspect-manifest.edn) found under %s" root))
      (let* ((keys (mapcar (lambda (m) (clara-explorer--edn-get :key m)) repos))
             (unit-key (completing-read (format "Unit repo (under %s): " root) keys nil t))
             (entry (clara-explorer--edn-get :entry
                       (cl-find-if (lambda (m)
                                     (equal (clara-explorer--edn-get :key m) unit-key))
                                   repos))))
        (format "{:root %s :units [%s]}"
                (clara-explorer--edn-value root)
                entry)))))

(defun clara-explorer--bb-selection ()
  "The cached bb registry-selection EDN, prompting once when unset."
  (or clara-explorer--bb-selection-cache
      (setq clara-explorer--bb-selection-cache (clara-explorer--bb-prompt-selection))))

(defun clara-explorer--bb-eval (selection-edn input-edn)
  "Run `bb editor-client.bb SELECTION-EDN INPUT-EDN' and parse the EDN result."
  (clara-explorer--bb-run (list selection-edn input-edn)))

;; ---------------------------------------------------------------------------
;; Structural navigation
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
\(`:and'/`:or'/`:not'/`:exists').  Recurses for nested wrappers.  When ORIG
sits on the wrapper head or its opening bracket — before the first child
condition — the first child's type bounds are returned: the user is pointing at
the condition, not at an operator."
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
            (cond
             ((and (>= orig inner-beg) (<= orig inner-end))
              (throw 'found (clara-explorer--type-bounds-in-condition-at-point inner-beg orig)))
             ((< orig inner-beg)
              (throw 'found (clara-explorer--type-bounds-in-condition-at-point inner-beg inner-beg)))
             (t (goto-char inner-end)))))
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
;; Remote navigation
;; ---------------------------------------------------------------------------

(defun clara-explorer--op-code (op input-edn)
  "The Clojure form string that calls OP's `client/' fn with INPUT-EDN.
   OP is `navigate' or `fact-type-provenance'; an unknown OP is an error rather
   than a silent default.  Uses `requiring-resolve' so the namespace is loaded
   at runtime rather than resolved at compile time (which would fail if the
   namespace is not yet loaded in the REPL)."
  (format "((requiring-resolve 'clara.explorer.server.client/%s) %s)"
          (pcase op
            ('navigate "navigate")
            ('fact-type-provenance "fact-type-provenance")
            (_ (error "clara-explorer: unknown operation %S" op)))
          input-edn))

(defun clara-explorer--resolve-template-file ()
  "Absolute path of the canonical resolve-form template shipped beside this
file (a symlink to the `clara.explorer.server.tokens` canonical text)."
  (unless clara-explorer--directory
    (error "clara-explorer: cannot locate resolve template (eval'd from a non-file buffer?)"))
  (expand-file-name "editor-resolve-form.clj" clara-explorer--directory))

(defun clara-explorer--load-resolve-template ()
  "Read the canonical resolve-form template text."
  (let ((file (clara-explorer--resolve-template-file)))
    (unless (file-readable-p file)
      (error "clara-explorer: resolve template not found at %s" file))
    (with-temp-buffer
      (insert-file-contents file)
      (buffer-string))))

(defconst clara-explorer--resolve-form-template
  (clara-explorer--load-resolve-template)
  "Canonical resolve-form template. Same two `%s` slots: caller-ns, token.")

(defun clara-explorer--resolve-form (caller-ns token)
  "Build the self-contained resolve form for CALLER-NS and TOKEN."
  (format clara-explorer--resolve-form-template
          (clara-explorer--edn-value (or caller-ns ""))
          (clara-explorer--edn-value token)))

(defun clara-explorer--resolve-token (token caller-ns conn)
  "Resolve TOKEN to fully-qualified form via a sync eval over CONN.
   Returns the fq string, or nil when unresolvable/unreadable — the caller
   falls back to the raw token."
  (when token
    (let* ((code (clara-explorer--resolve-form caller-ns token))
           (resp (cider-nrepl-sync-request:eval code conn))
           (val (and resp (nrepl-dict-get resp "value"))))
      (when (stringp val)
        (condition-case nil
            (let ((parsed (parseedn-read-str val)))
              (when (stringp parsed) parsed))
          (error nil))))))

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

(defconst clara-explorer--symbol-chars
  "A-Za-z0-9._:/!?*+<>-"
  "Clojure symbol characters, matching `token.lua`'s SYMBOL_CHARS.")

(defun clara-explorer--symbol-boundary-rx ()
  "A shy group closing a Clojure symbol: non-symbol char or end of line."
  (concat "\\(?:[^" clara-explorer--symbol-chars "]\\|$\\)"))

(defun clara-explorer--fallback-regexp (rule-name)
  "Fallback search: (defrule/defquery … RULE-NAME, whole-symbol-bounded."
  (concat clara-explorer--fallback-head-rx
          (rx (* (seq (+ (any " \t\n")) "^" (+ (not (any " \t\n")))))
              (+ (any " \t\n")))
          (regexp-quote rule-name)
          (clara-explorer--symbol-boundary-rx)))

(defun clara-explorer--whole-symbol-regexp (rule-name)
  "Regexp matching RULE-NAME as a whole Clojure symbol anywhere in a buffer."
  (concat "\\(?:[^" clara-explorer--symbol-chars "]\\|^\\)"
          (regexp-quote rule-name)
          (clara-explorer--symbol-boundary-rx)))

(defun clara-explorer--buffer-visits-ns-p (ns &optional buffer)
  "True when BUFFER (default: the current buffer) is the source buffer of NS.
BUFFER must visit a Clojure source file whose own `ns' form names NS.
`cider-current-ns' is deliberately not consulted: it falls back to the
connection's namespace, so while the REPL sits in NS every other buffer — the
REPL itself, `*scratch*', a log — answers NS too, and a jump would then search
and move point in a buffer that has nothing to do with the target."
  (with-current-buffer (or buffer (current-buffer))
    (and buffer-file-name
         (or (derived-mode-p 'clojure-mode 'clojure-ts-mode)
             (string-match-p "\\.clj[sc]?\\'" buffer-file-name))
         (equal ns (ignore-errors
                     (substring-no-properties (or (cider-get-ns-name) "")))))))

(defun clara-explorer--ns-source-buffer (ns)
  "The buffer visiting NS's source file, or nil.
The buffer's own `ns' form decides, so the file need not follow the
source-path convention; the Clojure-file test in
`clara-explorer--buffer-visits-ns-p' keeps a doc buffer that merely quotes
`(ns NS)' from being taken for the source."
  (cl-find-if (lambda (buf) (clara-explorer--buffer-visits-ns-p ns buf))
              (buffer-list)))

(defun clara-explorer--navigation-window (node)
  "Window to run NODE's jump in, or nil to keep the selected one.
The window already showing NODE's source buffer wins: the provenance view is a
side window, so jumping from there would open the source beside it, and the
most-recently-used window often shows something unrelated to NODE — a buffer
the jump has no business selecting or searching."
  (or (when-let* ((buf (clara-explorer--ns-source-buffer
                        (clara-explorer--edn-get :ns node))))
        (get-buffer-window buf t))
      (get-mru-window nil nil t)))

(defun clara-explorer--try-open-ns (ns)
  "Ask CIDER to visit NS's source file; the error text when it cannot.
A nil return only means CIDER did not signal — it does not prove the buffer
changed, so callers verify the buffer instead of trusting the return value."
  (condition-case err
      (progn (cider-find-ns nil ns) nil)
    (error (error-message-string err))))

(defun clara-explorer--search-production-form (ns rule-name)
  "Search the current buffer — NS's source — for RULE-NAME and go to it.
Returns non-nil when found; on a miss point goes back to where it was, so a
failed jump leaves the buffer as it found it."
  (let ((start (point)))
    (goto-char (point-min))
    (let ((found (or (re-search-forward
                      (clara-explorer--fallback-regexp rule-name)
                      nil t)
                     ;; fallback for ^{:map} metadata or other forms
                     (re-search-forward
                      (clara-explorer--whole-symbol-regexp rule-name)
                      nil t))))
      (clara-explorer--log "fallback: search %S in %s -> %s at %d"
                           rule-name ns (if found "found" "NOT-FOUND") (point))
      (if found
          t
        (goto-char start)
        (message "clara-explorer: %s not found in %s" rule-name ns)
        nil))))

(defun clara-explorer--goto-fallback (target)
  "Last resort: open NS and search it for the target's defrule/defquery form.
Only a buffer that really is NS's source is searched, and point in any other
buffer is left alone: a registry/bb tree can name productions whose namespace
is not on the connected REPL's classpath, and `cider-find-ns' then leaves
whatever buffer was current selected — searching that one moved point to an
unrelated line."
  (let* ((name (clara-explorer--edn-get :name target))
         (ns (clara-explorer--edn-get :ns target))
         (rule-name (clara-explorer--unqualified-name name)))
    (clara-explorer--log "fallback: name=%S ns=%S rule-name=%S" name ns rule-name)
    (cond
     ((not (and ns rule-name))
      (message "clara-explorer: cannot locate %s (no namespace in the target)" name)
      nil)
     (t
      (let ((open-error (clara-explorer--try-open-ns ns)))
        (if (clara-explorer--buffer-visits-ns-p ns)
            (clara-explorer--search-production-form ns rule-name)
          (when open-error
            (message "clara-explorer: could not open %s: %s" ns open-error))
          (message
           (concat "clara-explorer: namespace %s is not on this REPL's classpath; "
                   "load it (or use the nREPL transport with a live session) to jump to %s")
           ns rule-name)
          nil))))))

(defun clara-explorer--goto (target &optional no-push)
  "Jump to TARGET, pushing evil/xref jumps for C-o unless NO-PUSH.
NO-PUSH is for callers that already pushed from the buffer they are leaving,
as `clara-explorer--goto-production' does when it re-selects a window first.
Returns non-nil when the jump happened, so such a caller can put the selection
back after a failed jump."
  (let* ((name (clara-explorer--edn-get :name target))
         (source (clara-explorer--edn-get :source target))
         (var? (clara-explorer--edn-get :var? source)))
    (clara-explorer--log "goto: name=%S var?=%S source=%S" name var? source)
    (unless no-push
      (clara-explorer--push-jump))
    (if var?
        (progn
          (clara-explorer--log "goto: cider-find-var %S" name)
          (cider-find-var nil name)
          t)
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

(defun clara-explorer--prepare-query ()
  "Gather the navigation context at point and resolve the token, without
sending an operation.  Returns a plist (:input :ctx :transport :conn), or nil
after messaging why not.  `:input' carries the *structural* side at point; an
operation that needs a traversal direction sets its own (see
`clara-explorer--navigate-input').  The transport is resolved at call time:
`auto' picks nREPL when the connected CIDER session has a running explorer
system, otherwise bb."
  (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
  (let* ((conn (cider-current-repl 'infer 'ensure))
         (transport (clara-explorer--effective-transport conn))
         (ctx (clara-explorer--context))
         (caller-ns (plist-get ctx :caller-ns))
         (token (plist-get ctx :token)))
    (if (null token)
        (progn (message "not on a fact type") nil)
      (let* ((resolved (clara-explorer--resolve-token token caller-ns conn))
             (input (list :production (plist-get ctx :production)
                          :side (plist-get ctx :side)
                          :caller-ns caller-ns
                          :token (or resolved token))))
        (list :input input :ctx ctx :transport transport :conn conn)))))

(defun clara-explorer--navigate-input (query direction)
  "QUERY's prepared input with `:side' set to DIRECTION's navigate side
\(`:lhs' for producer, `:rhs' for consumer).  `navigate' reads `:side' as the
traversal direction; `fact-type-provenance' keeps the structural side instead."
  (plist-put (copy-sequence (plist-get query :input)) :side direction))

(defun clara-explorer--bb-provenance (selection-edn input-edn)
  "Run `bb editor-client.bb --provenance SEL INPUT' and parse the result."
  (clara-explorer--bb-run (list "--provenance" selection-edn input-edn)))

(defun clara-explorer--op-result (query op &optional input)
  "Send the prepared QUERY to OP and return the parsed result, or nil after
messaging why not.  OP is `navigate' or `fact-type-provenance'; INPUT is that
operation's input map (defaulting to QUERY's prepared input).  bb shells out to
`editor-client.bb' (no flag for navigate, `--provenance' for provenance); nREPL
evals the matching call in `client`."
  (let* ((transport (plist-get query :transport))
         (conn (plist-get query :conn))
         (input (or input (plist-get query :input)))
         (input-edn (clara-explorer--edn-map input))
         (result (if (eq transport 'bb)
                     (pcase op
                       ('navigate
                        (clara-explorer--bb-eval (clara-explorer--bb-selection) input-edn))
                       ('fact-type-provenance
                        (clara-explorer--bb-provenance (clara-explorer--bb-selection)
                                                       input-edn))
                       (_ (error "clara-explorer: unknown operation %S" op)))
                   (clara-explorer--eval-edn
                    (clara-explorer--op-code op input-edn) conn))))
    (if result
        result
      (message "clara-explorer: no result from %s" op)
      nil)))

(defun clara-explorer--navigate (direction)
  "Shared dispatcher for producer (:lhs) / consumer (:rhs) navigation.  A query
has no RHS, so only the consumer direction rejects one; provenance accepts any
token."
  (when-let* ((query (clara-explorer--prepare-query)))
    (if (and (eq direction :rhs)
             (eq (plist-get (plist-get query :ctx) :kind) 'query))
        (message "queries have no RHS")
      (when-let* ((result (clara-explorer--op-result
                           query 'navigate
                           (clara-explorer--navigate-input query direction))))
        (clara-explorer--handle-result result)))))

;; ---------------------------------------------------------------------------
;; Public commands
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
  (if (clara-explorer--bb-transport-p)
      (message "clara-explorer: refresh is a no-op in bb mode (re-persist the artifacts to pick up changes)")
    (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
    (clara-explorer--eval-edn
     "(do (require 'clara.explorer.server.serve)\n     (clara.explorer.server.serve/reload-annotations!))"
     (cider-current-repl 'infer 'ensure))
    (message "clara-explorer: analysis refreshed")))

(defvar clara-explorer--swap-session-exprs (make-hash-table :test 'eq)
  "Map of CIDER connection -> last swap opts expression.
Each value is the raw EDN string the user entered for
`clara.explorer.server.client/swap-session!` (the full opts map,
not just the session form).  Used to repeat the last swap without
re-prompting.  When `client/register-session-swap-opts-fn` is set,
calling with no opts delegates to that fn via the 0-arity.")

;;;###autoload
(defun clara-explorer-swap-session (&optional opts)
  "Swap the explorer session.
OPTS is the full opts EDN for `client/swap-session!`
(e.g. \"{:session my-session}\").
When supplied, call \"(client/swap-session! OPTS)\";
when nil/empty, call the 0-arity which delegates to
the fn registered via `register-session-swap-opts-fn`.
Prefix arg always prompts; otherwise reuse the last
opts for the connection.  Empty input at the prompt
means use the registered default (0-arity)."
  (interactive
   (if (clara-explorer--bb-transport-p)
       (list nil)
     (list (if (or current-prefix-arg
                   (null (gethash (cider-current-repl 'infer 'ensure)
                                  clara-explorer--swap-session-exprs)))
               (read-string "Swap opts (EDN map, empty for default): ")
             nil))))
  (if (clara-explorer--bb-transport-p)
      (message "clara-explorer: swap-session is a no-op in bb mode")
    (unless (cider-connected-p) (user-error "Not connected to a CIDER REPL"))
    (let* ((conn (cider-current-repl 'infer 'ensure))
           (raw (or opts (gethash conn clara-explorer--swap-session-exprs)))
           (trimmed (string-trim (or raw "")))
           (use-default (string-empty-p trimmed)))
      (unless use-default
        (puthash conn trimmed clara-explorer--swap-session-exprs))
      (clara-explorer--eval-edn
       (if use-default
           "(do (require 'clara.explorer.server.client)\n     (clara.explorer.server.client/swap-session!))"
         (format "(do (require 'clara.explorer.server.client)\n     (clara.explorer.server.client/swap-session! %s))"
                 trimmed))
       conn)
      (message "clara-explorer: session swapped"))))

;;;###autoload
(defun clara-explorer-toggle-transport ()
  "Toggle `clara-explorer-transport' between nREPL and bb."
  (interactive)
  (setq clara-explorer-transport
        (if (clara-explorer--bb-transport-p) 'nrepl 'bb))
  (message "clara-explorer: transport is now %s"
           (if (clara-explorer--bb-transport-p) "bb" "nrepl")))

;;;###autoload
(defun clara-explorer-transport-status ()
  "Show the configured and effective transport, and the explorer-server probe."
  (interactive)
  (let* ((connected (cider-connected-p))
         (server (clara-explorer--server-available-p))
         (effective (if (eq clara-explorer-transport 'auto)
                        (if server 'nrepl 'bb)
                      clara-explorer-transport)))
    (message
     "clara-explorer: effective=%S configured=%S connected=%s explorer-server=%s"
     effective
     clara-explorer-transport
     (if connected "yes" "no")
     (if server "yes" "no"))))

;;;###autoload
(defun clara-explorer-select-unit ()
  "Re-prompt for the bb transport's registry unit.
Has no effect on the nREPL transport."
  (interactive)
  (if (clara-explorer--bb-transport-p)
      (progn
        (setq clara-explorer--bb-selection-cache (clara-explorer--bb-prompt-selection))
        (message "clara-explorer: bb unit set"))
    (message "clara-explorer: warning — unit selection only affects the bb transport (currently nrepl)")))


;; ---------------------------------------------------------------------------
;; Fact type provenance view (built-in `tabulated-list-mode')
;; ---------------------------------------------------------------------------

(defcustom clara-explorer-provenance-depth 0
  "Initial expansion depth for a provenance view.
0 shows the root's immediate production layer; N inlines N further levels;
`full' expands until a path cycle (`:cycle?') or an already-expanded
production (`:seen?') stops it."
  :type '(choice (const :tag "Immediate (0)" 0)
                 (natnum :tag "Production levels")
                 (const :tag "Full" full))
  :group 'clara-explorer)

(defcustom clara-explorer-provenance-window-params nil
  "Alist for `display-buffer-in-side-window' when showing a provenance view.
nil uses the side-window defaults."
  :type '(alist :key-type symbol :value-type sexp)
  :group 'clara-explorer)

(defconst clara-explorer--provenance-buffer-name "*Clara Fact Type Provenance*"
  "Buffer name of the provenance view.")

(defvar-local clara-explorer--provenance-root nil
  "The root node of the rendered tree.")

(defvar-local clara-explorer--provenance-direction nil
  "The traversal direction: `:producer' or `:consumer'.")

(defvar-local clara-explorer--provenance-query nil
  "The prepared query the view was opened from (for `g').")

(defvar-local clara-explorer--provenance-input nil
  "The root operation input the view was opened with (for `g').")

(defvar-local clara-explorer--provenance-expanded nil
  "Hash table (equal) of full tree path -> t for open production rows.")

(defvar-local clara-explorer--provenance-children nil
  "Hash table (equal) of full tree path -> children fetched after the root.")

(defvar-local clara-explorer--provenance-nodes nil
  "Hash table (equal) of full tree path -> node, rebuilt on every render.")

(defun clara-explorer--provenance-message-node (text)
  "A leaf node carrying TEXT, used for transport and backend errors."
  (let ((node (make-hash-table :test 'equal)))
    (puthash :kind :message node)
    (puthash :text text node)
    node))

(defun clara-explorer--provenance-node-key (node)
  "The full-path component for NODE: its fact-type name, production name, or
message text.  Alternating fact-type and production names make the path unique
per tree position, so the same production under two sibling fact types toggles
independently."
  (pcase (clara-explorer--edn-get :kind node)
    (:fact-type (clara-explorer--edn-get :type node))
    (:production (clara-explorer--edn-get :name node))
    (_ (clara-explorer--edn-get :text node))))

(defun clara-explorer--provenance-vias-label (node)
  "The retract aside for an edge that is not insert-only, else nil."
  (let ((vias (clara-explorer--edn-get :vias node))
        (via (clara-explorer--edn-get :via node)))
    (cond
     ((and (vectorp vias) (> (length vias) 1))
      (propertize " (insert+retract)" 'face 'warning))
     ((or (equal vias [:retract]) (eq via :retract))
      (propertize " (retract)" 'face 'warning)))))

(defun clara-explorer--provenance-fact-type-label (node)
  "NODE's label: the contract's short type, dimmed full type, and markers.
The short name comes from `:type-short'; nothing is derived from `:type'."
  (let ((short (clara-explorer--edn-get :type-short node))
        (full (clara-explorer--edn-get :type node)))
    (concat short
            (unless (equal short full)
              (concat " " (propertize full 'face 'shadow)))
            (when (clara-explorer--edn-get :external? node)
              (propertize " (external)" 'face 'shadow))
            (clara-explorer--provenance-vias-label node))))

(defun clara-explorer--provenance-matches-label (node)
  "The `via <short types>' suffix for a production that reaches the parent
through the hierarchy; nil when the edge is direct."
  (let ((matches (clara-explorer--edn-get :matches node)))
    (when (and (clara-explorer--edn-get :bridge? node)
               matches
               (not (equal matches [])))
      (propertize
       (format " via %s"
               (mapconcat (lambda (match) (clara-explorer--edn-get :short match))
                          (append matches nil)
                          ", "))
       'face 'shadow))))

(defun clara-explorer--provenance-production-label (node direction &optional expanded?)
  "NODE's label: unqualified rule name, dim ns, unit, kind/leaf/edge markers.
EXPANDED? suppresses the `shown above' marker on a row that is open here."
  (let* ((name (clara-explorer--edn-get :name node))
         (ns (clara-explorer--edn-get :ns node))
         (unit (clara-explorer--edn-get :unit node))
         (type (clara-explorer--edn-get :type node))
         (outputs (clara-explorer--edn-get :outputs node))
         (leaf (equal (clara-explorer--edn-get :children node) [])))
    (concat
     (propertize (clara-explorer--unqualified-name name)
                 'face 'font-lock-function-name-face)
     (when ns (concat " " (propertize ns 'face 'shadow)))
     (when (equal type "query") (propertize " (query)" 'face 'shadow))
     (when unit (concat " " (propertize (format "@%s" unit) 'face 'shadow)))
     (cond
      (outputs (propertize " (outputs unknown)" 'face 'shadow))
      ((and leaf (eq direction :consumer) (not (equal type "query")))
       (propertize " (no outputs)" 'face 'shadow)))
     (clara-explorer--provenance-matches-label node)
     (clara-explorer--provenance-vias-label node)
     (when (clara-explorer--edn-get :cycle? node)
       (propertize " ↻ cycle" 'face 'shadow))
     (when (and (clara-explorer--edn-get :seen? node) (not expanded?))
       (propertize " ↑ shown above" 'face 'shadow)))))

(defun clara-explorer--provenance-row-label (node indent direction path)
  "One row's label: INDENT per path level, then the node's own label."
  (concat (make-string (* 2 indent) ?\s)
          (pcase (clara-explorer--edn-get :kind node)
            (:fact-type (clara-explorer--provenance-fact-type-label node))
            (:production (clara-explorer--provenance-production-label
                          node direction
                          (gethash path clara-explorer--provenance-expanded)))
            (_ (propertize (format "%s" (clara-explorer--edn-get :text node))
                           'face 'shadow)))))

(defun clara-explorer--provenance-has-children-p (node)
  "True when NODE carries a non-empty `:children' list."
  (let ((children (clara-explorer--edn-get :children node)))
    (and children (not (equal children [])))))

(defun clara-explorer--provenance-seed (node path)
  "Mark expanded — and cache — every production below NODE whose children
came populated.  A `:depth N' / `:full' response therefore renders expanded
without a fetch."
  (dolist (child (append (clara-explorer--edn-get :children node) nil))
    (let ((child-path (vconcat path
                               (vector (clara-explorer--provenance-node-key child)))))
      (when (and (eq (clara-explorer--edn-get :kind child) :production)
                 (clara-explorer--provenance-has-children-p child))
        (puthash child-path
                 (append (clara-explorer--edn-get :children child) nil)
                 clara-explorer--provenance-children)
        (puthash child-path t clara-explorer--provenance-expanded))
      (clara-explorer--provenance-seed child child-path))))

(defun clara-explorer--provenance-visible-children (node path)
  "The child nodes to render under NODE at PATH.  Fact types are
transparent; a production's children show only while it is expanded, from the
cache when the level was fetched later."
  (pcase (clara-explorer--edn-get :kind node)
    (:fact-type (append (clara-explorer--edn-get :children node) nil))
    (:production
     (when (gethash path clara-explorer--provenance-expanded)
       (or (gethash path clara-explorer--provenance-children)
           (append (clara-explorer--edn-get :children node) nil))))
    (_ nil)))

(defun clara-explorer--provenance-rows (node path direction)
  "Rows for NODE at PATH, depth-first, plus the node table the commands read."
  (puthash path node clara-explorer--provenance-nodes)
  (cons (list path (vector (clara-explorer--provenance-row-label
                            node (1- (length path)) direction path)))
        (mapcan (lambda (child)
                  (clara-explorer--provenance-rows
                   child
                   (vconcat path (vector (clara-explorer--provenance-node-key child)))
                   direction))
                (clara-explorer--provenance-visible-children node path))))

(defun clara-explorer--provenance-entries (root direction)
  "The `tabulated-list-entries' for ROOT, rebuilding the path -> node table."
  (setq clara-explorer--provenance-nodes (make-hash-table :test 'equal))
  (clara-explorer--provenance-rows root
                                   (vector (clara-explorer--provenance-node-key root))
                                   direction))

(defun clara-explorer--provenance-set-header (direction)
  "Show the traversal DIRECTION in the column header, and forbid sorting.
Sorting an indented tree would flatten it."
  (setq tabulated-list-format
        (vector (list (pcase direction
                        (:producer "Producer Provenance")
                        (_ "Consumer Provenance"))
                      0 nil)))
  (tabulated-list-init-header))

(defun clara-explorer--provenance-load (root direction query input)
  "Reset the view state to ROOT and return its entries."
  (setq clara-explorer--provenance-root root
        clara-explorer--provenance-direction direction
        clara-explorer--provenance-query query
        clara-explorer--provenance-input input
        clara-explorer--provenance-expanded (make-hash-table :test 'equal)
        clara-explorer--provenance-children (make-hash-table :test 'equal))
  (clara-explorer--provenance-set-header direction)
  (clara-explorer--provenance-seed root (vector (clara-explorer--provenance-node-key root)))
  (clara-explorer--provenance-entries root direction))

(defun clara-explorer--provenance-render ()
  "Reprint the view in the current buffer."
  (setq tabulated-list-entries
        (clara-explorer--provenance-entries clara-explorer--provenance-root
                                            clara-explorer--provenance-direction))
  (tabulated-list-print t))

(defun clara-explorer--provenance-request (query input)
  "Post INPUT to the provenance op and return its `:node', or a message node.
Both an `{:error …}' answer and a transport failure (a signalled bb exit or
nREPL error) become message nodes, so a failed expansion keeps the buffer."
  (condition-case err
      (let ((result (clara-explorer--op-result query 'fact-type-provenance input)))
        (cond
         ((null result)
          (clara-explorer--provenance-message-node "no result from fact-type-provenance"))
         ((clara-explorer--edn-get :error result)
          (clara-explorer--provenance-message-node (clara-explorer--edn-get :error result)))
         (t (clara-explorer--edn-get :node result))))
    (user-error
     (clara-explorer--provenance-message-node (error-message-string err)))
    (error
     (clara-explorer--provenance-message-node
      (format "error: %s" (error-message-string err))))))

(defun clara-explorer--provenance-show (query direction input)
  "Run INPUT for QUERY walking DIRECTION and reprint the current buffer.
A root-call error renders as a message row rather than failing the command."
  (let ((node (clara-explorer--provenance-request query input)))
    (setq tabulated-list-entries
          (clara-explorer--provenance-load node direction query input))
    (tabulated-list-print t)))

(defun clara-explorer--provenance-production-ancestors (path)
  "Production names strictly above the production at PATH's last element.
Paths alternate fact-type and production names, so the productions sit at
the odd indices; the last element is the row being expanded and is
excluded."
  (vconcat (cl-loop for i from 1 below (1- (length path)) by 2
                    collect (aref path i))))

(defun clara-explorer--provenance-expandable-p (node)
  "True when NODE can fetch another level: not a cycle, not outputs-unknown,
not a leaf."
  (and (not (clara-explorer--edn-get :cycle? node))
       (not (clara-explorer--edn-get :outputs node))
       (not (equal (clara-explorer--edn-get :children node) []))))

(defun clara-explorer--provenance-toggle-path (path)
  "Expand or collapse the production row at PATH; non-nil when the view changed.
A collapsed row re-expands from `clara-explorer--provenance-children' without
round-tripping; a cached message row retries.  A `:seen?' row is fetched on
demand at depth 0; cycle, message, outputs-unknown, and leaf rows do not
toggle."
  (let* ((node (and path (gethash path clara-explorer--provenance-nodes)))
         (cached (and path (gethash path clara-explorer--provenance-children :miss)))
         (cached-error? (and (consp cached)
                             (= 1 (length cached))
                             (eq (clara-explorer--edn-get :kind (car cached)) :message))))
    (when node
      (pcase (clara-explorer--edn-get :kind node)
        (:message (message "%s" (clara-explorer--edn-get :text node)))
        (:fact-type (message "fact types stay expanded; expand a production"))
        (:production
         (cond
          ((gethash path clara-explorer--provenance-expanded)
           (remhash path clara-explorer--provenance-expanded)
           t)
          ((not (clara-explorer--provenance-expandable-p node))
           (message "nothing to expand")
           nil)
          ((and (not (eq cached :miss)) (not cached-error?))
           (puthash path t clara-explorer--provenance-expanded)
           t)
          (t
           (let ((fetched (clara-explorer--provenance-request
                           clara-explorer--provenance-query
                           (list :node (list :kind :production
                                             :name (clara-explorer--edn-get :name node))
                                 :direction clara-explorer--provenance-direction
                                 :depth 0
                                 :ancestors (clara-explorer--provenance-production-ancestors
                                             path)))))
             (if (eq (clara-explorer--edn-get :kind fetched) :message)
                 (puthash path (list fetched) clara-explorer--provenance-children)
               (puthash path (append (clara-explorer--edn-get :children fetched) nil)
                        clara-explorer--provenance-children)
               (clara-explorer--provenance-seed fetched path))
             (puthash path t clara-explorer--provenance-expanded)
             t))))))))

(defun clara-explorer--provenance-row-node ()
  "The node on the row at point, or nil."
  (let ((path (tabulated-list-get-id)))
    (and path (gethash path clara-explorer--provenance-nodes))))

(defun clara-explorer-provenance-toggle ()
  "Expand or collapse the production on the current row."
  (interactive)
  (when (clara-explorer--provenance-toggle-path (tabulated-list-get-id))
    (clara-explorer--provenance-render)))

(defun clara-explorer--goto-production (node)
  "Jump to production NODE, from a window that belongs to its source if one does.
The jump is pushed before the window changes, so `C-o' returns to the provenance
view rather than to the source window the jump happened to be made from.  A
failed jump restores the selection, so pressing RET on a production the REPL
cannot open leaves the user in the provenance view with the reason, rather than
in whatever buffer was most recently used."
  (let ((start (selected-window))
        (jumped nil))
    (clara-explorer--push-jump)
    (when-let* ((window (clara-explorer--navigation-window node)))
      (select-window window))
    (unwind-protect
        (setq jumped (clara-explorer--goto node t))
      (unless jumped (select-window start)))))

(defun clara-explorer-provenance-visit ()
  "Jump to the production on the current row.
The jump runs in the window that already shows the production's source file, if
there is one, and a failed jump leaves the view and the selection as they were."
  (interactive)
  (let* ((path (tabulated-list-get-id))
         (node (clara-explorer--provenance-row-node)))
    (cond
     ((and path (null node))
      (message "clara-explorer: stale row; press g to refresh the view"))
     ((and node (eq (clara-explorer--edn-get :kind node) :production))
      (clara-explorer--goto-production node))
     (t
      (message "not a production row (RET jumps from a production row)")))))

(defun clara-explorer-provenance-refresh (&optional _ignore-auto _noconfirm)
  "Re-run the root call from the stored query.
Also the buffer's `revert-buffer-function', hence the (ignored) arguments
`revert-buffer' passes."
  (interactive)
  (when clara-explorer--provenance-query
    (clara-explorer--provenance-show
     clara-explorer--provenance-query
     (plist-get clara-explorer--provenance-input :direction)
     clara-explorer--provenance-input)))

(defvar clara-explorer-provenance-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "TAB") #'clara-explorer-provenance-toggle)
    (define-key map (kbd "<tab>") #'clara-explorer-provenance-toggle)
    (define-key map (kbd "RET") #'clara-explorer-provenance-visit)
    ;; GUI Return, and convenient when a global map shadows C-m.
    (define-key map (kbd "<return>") #'clara-explorer-provenance-visit)
    map)
  "Keymap for `clara-explorer-provenance-mode'.
Only the keys `special-mode' does not already provide are bound here: `g'
reverts (through `revert-buffer-function', see the mode) and `q' buries, both
from the parent map.  Rebind here to customize; if your setup shadows `g' (e.g.
evil's `g' prefix), bind `clara-explorer-provenance-refresh' yourself or call
`revert-buffer'.")

(define-derived-mode clara-explorer-provenance-mode tabulated-list-mode "Provenance"
  "Major mode for a fact type provenance tree.
\\{clara-explorer-provenance-mode-map}
`g' reverts through `revert-buffer-function', which re-runs the stored root call
(`clara-explorer-provenance-refresh'); `q' buries; `TAB' expands or collapses a
production; `RET' jumps to it.  The mode map is the customization point."
  (setq tabulated-list-format [("Provenance" 0 nil)])
  (setq tabulated-list-padding 0)
  (setq tabulated-list-sort-key nil)
  (setq-local revert-buffer-function #'clara-explorer-provenance-refresh)
  (tabulated-list-init-header))

(defun clara-explorer--provenance-root-input (query direction)
  "The provenance input for QUERY's fact type, walking DIRECTION at the
configured depth."
  (list :node (list :kind :cursor :input (plist-get query :input))
        :direction direction
        :depth (if (eq clara-explorer-provenance-depth 'full)
                   :full
                 clara-explorer-provenance-depth)
        :ancestors []))

(defun clara-explorer--provenance-display (query direction input)
  "Open the provenance view for QUERY walking DIRECTION with INPUT, and show it.
A root-call error renders as a message row rather than failing the command."
  (with-current-buffer (get-buffer-create clara-explorer--provenance-buffer-name)
    (clara-explorer-provenance-mode)
    (clara-explorer--provenance-show query direction input))
  (select-window
   (or (display-buffer-in-side-window
        (get-buffer clara-explorer--provenance-buffer-name)
        clara-explorer-provenance-window-params)
       (selected-window))))

(defun clara-explorer--show-provenance (direction)
  "Open a provenance view for the fact type at point, walking DIRECTION."
  (when-let* ((query (clara-explorer--prepare-query)))
    (clara-explorer--provenance-display
     query direction (clara-explorer--provenance-root-input query direction))))

;;;###autoload
(defun clara-explorer-show-producer-provenance ()
  "Show the producer (upstream) provenance of the fact type at point."
  (interactive)
  (clara-explorer--show-provenance :producer))

;;;###autoload
(defun clara-explorer-show-consumer-provenance ()
  "Show the consumer (downstream) provenance of the fact type at point."
  (interactive)
  (clara-explorer--show-provenance :consumer))

(provide 'clara-explorer)
;;; clara-explorer.el ends here
