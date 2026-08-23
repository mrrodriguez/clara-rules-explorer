;;; config.el --- clara-explorer layer config -*- lexical-binding: t; -*-

;;; Commentary:
;;; Defines the single per-machine setting, `clara-explorer-root', pointing
;;; at the clara-rules-explorer checkout, and loads `clara-explorer.el' from
;;; it once CIDER is available.  Nothing here hard-codes an absolute path.

;;; Code:

(defcustom clara-explorer-root nil
  "Path to the clara-rules-explorer checkout root.
   `editor/emacs' under this root is added to `load-path' and
   `clara-explorer' is loaded from there.  Set it in
   `dotspacemacs-configuration-layers', e.g.
   (clara-explorer :variables clara-explorer-root \"~/src/clara-rules-explorer\")."
  :group 'clara-explorer
  :type 'directory)

;; Load the package once CIDER is ready.  clara-explorer.el's runtime guards
;; require cider, parseedn and clojure-mode to be loaded already; CIDER
;; transitively loads parseedn and clojure-mode, so hooking on 'cider is the
;; single correct trigger.  (Hooking on 'clojure-mode would fire before
;; cider/parseedn and trip the guards.)
(with-eval-after-load 'cider
  (if clara-explorer-root
      (progn
        (add-to-list 'load-path (expand-file-name "editor/emacs" clara-explorer-root))
        (require 'clara-explorer))
    (message "clara-explorer: clara-explorer-root is nil — set it in dotspacemacs-configuration-layers")))

;;; config.el ends here
