;;; clara-explorer-test.el --- ERT unit tests for clara-explorer -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
(require 'clara-explorer)
(require 'test-helper)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defmacro with-clara-buffer (content &rest body)
  "Insert CONTENT into a temp buffer, enable `emacs-lisp-mode' for syntax, then run BODY.
Point is at `point-min' before BODY.  Stubs `cider-connected-p' to nil unless BODY binds it."
  `(with-temp-buffer
     (insert ,content)
     (emacs-lisp-mode)
     (goto-char (point-min))
     ,@body))

(defun test--search-token (search)
  "Move to SEARCH string, then return `clara-explorer--token-at-point' result for current LHS/RHS context."
  (goto-char (point-min))
  (search-forward search)
  (let* ((enc (clara-explorer--enclosing-production))
         (form-start (nth 2 enc))
         (side (and form-start (clara-explorer--side-at-point form-start))))
    (clara-explorer--token-at-point form-start side)))

;; ---------------------------------------------------------------------------
;; EDN transport
;; ---------------------------------------------------------------------------

(ert-deftest edn-value-strips-text-properties ()
  (should (equal (clara-explorer--edn-value (propertize "hello" 'face 'bold)) "\"hello\""))
  (should (equal (clara-explorer--edn-value (propertize "a/b" 'fontified t)) "\"a/b\"")))

(ert-deftest edn-value-symbol-strips-properties ()
  (should (equal (clara-explorer--edn-value :lhs) ":lhs")))

(ert-deftest edn-map-propertized-strings-are-clean ()
  (let ((m (clara-explorer--edn-map (list :production (propertize "ns/rule" 'face 'bold) :side :lhs :caller-ns (propertize "ns" 'fontified t) :token "Tok"))))
    (should-not (string-match-p "fontified" m))
    (should-not (string-match-p "#(" m))
    (should (string-match-p ":production \"ns/rule\"" m))))

(ert-deftest choose-target-handles-vector-targets ()
  (let* ((t1 (let ((h (make-hash-table :test 'equal))) (puthash :name "a/b" h) h))
         (t2 (let ((h (make-hash-table :test 'equal))) (puthash :name "c/d" h) h))
         (vec (vector t1 t2)))
    ;; should not signal wrong-type-argument listp
    (should (equal (length (if (vectorp vec) (append vec nil) vec)) 2))
    ;; choose-or-jump single vector and multi vector (mock completing-read for multi)
    (cl-letf (((symbol-function 'clara-explorer--goto) (lambda (_t) nil))
              ((symbol-function 'completing-read) (lambda (_prompt coll &rest _r) (car coll))))
      (should (progn (clara-explorer--choose-or-jump :consumer "X" vec) t))
      (should (progn (clara-explorer--choose-or-jump :consumer "X" (vector t1)) t)))))

;; ---------------------------------------------------------------------------
;; Structural navigation
;; ---------------------------------------------------------------------------

(ert-deftest enclosing-production-finds-defrule ()
  (with-clara-buffer "(ns test) (r/defrule my-rule [Application] => (println 1))"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule"))
      (should (eq (nth 1 enc) 'rule)))))

(ert-deftest enclosing-production-alias-agnostic ()
  (with-clara-buffer "(r/defrule foo [X] => 1) (my.alias/defrule bar [Y] => 2)"
    (goto-char (point-min))
    (search-forward "bar")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "bar"))
      (should (eq (nth 1 enc) 'rule)))))

(ert-deftest enclosing-production-inside-string-still-finds-defrule ()
  (with-clara-buffer "(r/defrule foo [?s <- [:loan/status \"verified\"]] => 1)"
    (search-forward "verified")
    (should (clara-explorer--enclosing-production))))

(ert-deftest top-level-=>-found ()
  (with-clara-buffer "(r/defrule foo [A] => (println 1)) (r/defrule bar [B] => 2)"
    (let* ((enc (progn (search-forward "foo") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc))
           (pos (clara-explorer--top-level-=> form-start)))
      (should (numberp pos))
      (save-excursion
        (goto-char pos)
        (should (looking-at-p "=>"))))))

(ert-deftest top-level-=>-query-has-no-arrow ()
  (with-clara-buffer "(r/defquery foo [] [A])"
    (search-forward "foo")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (null (clara-explorer--top-level-=> form-start))))))

(ert-deftest side-at-point-lhs-rhs ()
  (with-clara-buffer "(r/defrule foo [A] => (r/insert! B))"
    (let* ((enc (progn (search-forward "foo") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc)))
      (goto-char (point-min)) (search-forward "[A]")
      (should (eq (clara-explorer--side-at-point form-start) :lhs))
      (search-forward "B")
      (should (eq (clara-explorer--side-at-point form-start) :rhs)))))

(ert-deftest side-at-point-query-is-lhs ()
  (with-clara-buffer "(r/defquery foo [] [A] [B])"
    (search-forward "foo")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (goto-char (point-min)) (search-forward "[B]")
      (should (eq (clara-explorer--side-at-point form-start) :lhs)))))

;; ---------------------------------------------------------------------------
;; String / vector fact helpers
;; ---------------------------------------------------------------------------

(ert-deftest string-at-point-inside-and-on-quote ()
  (with-clara-buffer "[\"my-string\"]"
    (search-forward "my-string")
    (should (equal (clara-explorer--string-at-point) "\"my-string\"")))
  (with-clara-buffer "[\"my-string\"]"
    (search-forward "\"my-string")
    (should (equal (clara-explorer--string-at-point) "\"my-string\""))))

(ert-deftest vector-fact-at-point-tuple ()
  (with-clara-buffer "(r/defrule foo [?s <- [:loan/status \"verified\"]] => 1)"
    (search-forward ":loan/status")
    (should (equal (clara-explorer--vector-fact-at-point) "[:loan/status \"verified\"]")))
  (with-clara-buffer "(r/defrule foo [?s <- [:loan/status \"verified\"]] => 1)"
    (search-forward "verified")
    (should (equal (clara-explorer--vector-fact-at-point) "[:loan/status \"verified\"]"))))

(ert-deftest vector-fact-at-point-not-condition ()
  (with-clara-buffer "(r/defrule foo [Application (= ?x 1)] => 1)"
    (search-forward "Application")
    (should (null (clara-explorer--vector-fact-at-point)))))

;; ---------------------------------------------------------------------------
;; type-bounds-in-condition
;; ---------------------------------------------------------------------------

(ert-deftest type-bounds-plain ()
  (with-clara-buffer "(r/defrule foo [Application (= ?x 1)] => 1)"
    (search-forward "[Application")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should tb)
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "Application"))))))

(ert-deftest type-bounds-with-binding ()
  (with-clara-buffer "(r/defrule foo [?d <- ::supporting-document] => 1)"
    (search-forward "[?d")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "::supporting-document"))))))

(ert-deftest type-bounds-accumulator ()
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) :from [:my-thing [this] (= ?x 1)]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing"))))))

(ert-deftest type-bounds-accumulator-vector-type ()
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) :from [[:my-thing]]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "[:my-thing]"))))))

(ert-deftest type-bounds-boolean-group-returns-nil ()
  (with-clara-buffer "(r/defrule foo [:and [A] [B]] => 1)"
    (search-forward "[:and")
    (search-backward "[")
    (let ((beg (point)))
      (should (null (clara-explorer--type-bounds-in-condition beg))))))

;; ---------------------------------------------------------------------------
;; lhs-type-at-point — the 5 accumulator cases + tuple/string/plain
;; ---------------------------------------------------------------------------

(ert-deftest lhs-type-acc-case1 ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [:my-thing [this] (= ?x (:x this))]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") ":my-thing"))
    (search-forward "[this]")
    ;; point on [this] should still be the fact type :my-thing (condition's type)
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (tok (clara-explorer--token-at-point form-start :lhs)))
      (should (equal tok ":my-thing")))))

(ert-deftest lhs-type-acc-case2 ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [:my-thing]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") ":my-thing"))))

(ert-deftest lhs-type-acc-case3-singleton-vector ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [[:my-thing]]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") "[:my-thing]"))
    (should (equal (test--search-token "[[:my-thing") "[:my-thing]"))))

(ert-deftest lhs-type-acc-case4-with-constraints ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [[:my-thing] [this] (= ?x (first this))]]\n  =>\n  (println 1))"
    (should (equal (test--search-token "[:my-thing]") "[:my-thing]"))
    (should (equal (test--search-token "[this]") "[:my-thing]"))))

(ert-deftest lhs-type-acc-case5-qualified-vector ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (acc/all) :from [[:my-thing :qualifier]]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") "[:my-thing :qualifier]"))))

(ert-deftest lhs-type-plain-application ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [Application (= ?x 1)]\n  =>\n  (println 1))"
    (should (equal (test--search-token "Application") "Application"))))

(ert-deftest lhs-type-tuple-plain ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?s <- [:loan/status \"verified\"]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":loan/status") "[:loan/status \"verified\"]"))
    (should (equal (test--search-token "verified") "[:loan/status \"verified\"]"))))

(ert-deftest lhs-type-string-fact ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?x <- \"my-string\"]\n  =>\n  (println 1))"
    (should (equal (test--search-token "my-string") "\"my-string\""))))

(ert-deftest lhs-type-keyword ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?d <- ::supporting-document]\n  =>\n  (println 1))"
    (should (equal (test--search-token "supporting-document") "::supporting-document"))))

(ert-deftest lhs-type-with-docstring ()
  (with-clara-buffer "(ns test) (r/defrule foo \"docstring\"\n  [?acc <- (acc/all) :from [:my-thing]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") ":my-thing"))))

;; ---------------------------------------------------------------------------
;; token-at-point routing (LHS vs string vs symbol, RHS vector)
;; ---------------------------------------------------------------------------

(ert-deftest token-at-point-rhs-vector ()
  (with-clara-buffer "(ns test) (r/defrule foo\n  [Application]\n  =>\n  (r/insert! [:loan/status \"verified\"]))"
    (let* ((enc (progn (search-forward "verified") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "[:loan/status \"verified\"]")))))

(ert-deftest token-at-point-rhs-string ()
  (with-clara-buffer "(ns test) (r/defrule foo [X] => (r/insert! \"my-string\"))"
    (search-forward "my-string")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "\"my-string\"")))))

(ert-deftest token-at-point-global-vector ()
  (with-clara-buffer "(ns test) (defn foo [] [:loan/status \"verified\"])"
    (search-forward ":loan/status")
    ;; outside defrule/defquery, lhs-type not applicable, falls back to vector
    (should (equal (clara-explorer--vector-fact-at-point) "[:loan/status \"verified\"]"))))

(ert-deftest token-at-point-propertized-symbol-stripped ()
  (with-clara-buffer "(r/defrule foo [Application] => 1)"
    (cl-letf (((symbol-function 'cider-symbol-at-point)
               (lambda (&optional _) (propertize "Application" 'face 'bold))))
      (should (equal (clara-explorer--token-at-point) "Application")))))

;; ---------------------------------------------------------------------------
;; context
;; ---------------------------------------------------------------------------

(ert-deftest context-gathers-production-side-token ()
  (with-clara-buffer "(ns my.ns) (r/defrule my-rule\n  [Application]\n  =>\n  (r/insert! X))"
    (search-forward "Application")
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-ns) (lambda () "my.ns")))
      (let ((ctx (clara-explorer--context)))
        (should (equal (plist-get ctx :production) "my.ns/my-rule"))
        (should (eq (plist-get ctx :side) :lhs))
        (should (equal (plist-get ctx :token) "Application"))
        (should (equal (plist-get ctx :caller-ns) "my.ns"))))))

(ert-deftest context-propertized-ns-stripped ()
  (with-clara-buffer "(ns my.ns) (r/defrule my-rule [X] => 1)"
    (search-forward "X")
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-ns) (lambda () (propertize "my.ns" 'face 'bold))))
      (let ((ctx (clara-explorer--context)))
        (should (equal (plist-get ctx :caller-ns) "my.ns"))
        (should-not (string-match-p "fontified" (plist-get ctx :production)))
        (should-not (string-match-p "#(" (plist-get ctx :production)))))))

(provide 'clara-explorer-test)
;;; clara-explorer-test.el ends here
