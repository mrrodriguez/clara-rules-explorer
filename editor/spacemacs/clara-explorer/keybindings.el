;;; keybindings.el --- clara-explorer layer keybindings -*- lexical-binding: t; -*-

;;; Commentary:
;;; Leader keys scoped to `clojure-mode' only, matching the existing `, g f'
;;; pattern.  Package loading/setup lives in `config.el'; this file only
;;; binds keys.  Commands are also always available via M-x.

;;; Code:

(spacemacs/set-leader-keys-for-major-mode 'clojure-mode
  "gp" 'clara-explorer-navigate-producer
  "gc" 'clara-explorer-navigate-consumer
  "gr" 'clara-explorer-refresh)

;;; keybindings.el ends here
