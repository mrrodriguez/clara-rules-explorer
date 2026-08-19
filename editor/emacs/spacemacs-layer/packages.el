;;; packages.el --- clara-explorer layer packages file -*- lexical-binding: t; -*-

;;; Commentary:
;;; Declares the clara-explorer layer's package dependencies.  The
;;; clara-explorer.el file itself is loaded from the checkout via
;;; `clara-explorer-root` (see config.el) rather than from a package archive.

;;; Code:

(defconst clara-explorer-packages
  '(cider
    parseedn
    clojure-mode))

;;; packages.el ends here
