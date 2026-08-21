;;; keybindings.el --- clara-explorer layer keybindings -*- lexical-binding: t; -*-

;;; Commentary:
;;; Leader keys scoped to `clojure-mode' only, matching the existing `, g f'
;;; pattern.  Commands are also always available via M-x.

;;; Code:

(defun clara-explorer/init-clara-explorer ()
  "Bind clara-explorer commands under the leader in `clojure-mode'."
  (spacemacs/set-leader-keys-for-major-mode 'clojure-mode
    "gp" 'clara-explorer-navigate-producer
    "gc" 'clara-explorer-navigate-consumer
    "gr" 'clara-explorer-refresh))

;;; keybindings.el ends here
