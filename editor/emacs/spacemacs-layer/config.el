;;; config.el --- clara-explorer layer config -*- lexical-binding: t; -*-

;;; Commentary:
;;; Locates the clara-rules-explorer checkout via the per-machine layer
;;; variable `clara-explorer-root' and loads `clara-explorer.el' from it.
;;; Nothing here hard-codes an absolute path.

;;; Code:

(defcustom clara-explorer-root nil
  "Path to the clara-rules-explorer checkout root.
   The layer adds `editor/emacs' under this root to `load-path' and loads
   `clara-explorer' from there.  Set it in `dotspacemacs-configuration-layers',
   e.g. (clara-explorer :variables clara-explorer-root \"~/src/clara-rules-explorer\")."
  :group 'clara-explorer
  :type 'directory)

(defun clara-explorer/init-clara-explorer ()
  "Add `editor/emacs' to `load-path' and load the explorer package."
  (when clara-explorer-root
    (add-to-list 'load-path (expand-file-name "editor/emacs" clara-explorer-root))
    (require 'clara-explorer)))

;;; config.el ends here
