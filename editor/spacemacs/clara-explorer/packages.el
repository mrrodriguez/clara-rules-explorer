;;; packages.el --- clara-explorer layer packages file -*- lexical-binding: t; -*-

;;; Commentary:
;;; Declares the clara-explorer layer's package dependencies.  The
;;; clara-explorer.el file itself is not a published package; it is loaded
;;; from the checkout via `clara-explorer-root' (see config.el).

;;; Code:

(defconst clara-explorer-packages
  '(cider
    parseedn
    clojure-mode))

;;; packages.el ends here
