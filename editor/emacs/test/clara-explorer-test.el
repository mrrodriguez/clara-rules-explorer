;;; clara-explorer-test.el --- ERT unit tests for clara-explorer -*- lexical-binding: t; -*-

(require 'ert)
(require 'cl-lib)
;; Make test/ discoverable for both plain emacs (Makefile adds it) and eldev.
;; eldev loads test files with load-file-name set to the test file itself.
(when load-file-name
  (add-to-list 'load-path (file-name-directory load-file-name)))
;; Fallback when load-file-name is nil (e.g. batch load via -l): add ./test
(add-to-list 'load-path (expand-file-name "test" (file-name-directory (or load-file-name default-directory))))
(add-to-list 'load-path (expand-file-name "." (file-name-directory (or load-file-name default-directory))))
(require 'test-helper)
(require 'clara-explorer)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defmacro with-clara-buffer (content &rest body)
  "Insert CONTENT into a temp buffer with Clojure syntax, then run BODY.
Prefers `clojure-mode' when available (real via Eldev or minimal stub
from `test-helper'), falling back to `emacs-lisp-mode' + manual syntax
table for bare `emacs -Q --batch'.  This exercises real `clojure-mode'
syntax (comments, :: keywords, strings) under Eldev while keeping the
Tier-1 stub path fast."
  `(with-temp-buffer
     (insert ,content)
     (if (fboundp 'clojure-mode)
         (clojure-mode)
       (progn
         (emacs-lisp-mode)
         (let ((st (syntax-table)))
           (modify-syntax-entry ?\{ "(}" st)
           (modify-syntax-entry ?\} "){" st)
           (modify-syntax-entry ?\[ "(]" st)
           (modify-syntax-entry ?\] ")[" st))
         (setq-local comment-start ";")
         (setq-local parse-sexp-ignore-comments t)))
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

(ert-deftest edn-value-prints-vectors-element-wise ()
  (should (equal (clara-explorer--edn-value ["a" "b"]) "[\"a\" \"b\"]"))
  (should (equal (clara-explorer--edn-value [:a :b]) "[:a :b]"))
  (should (equal (clara-explorer--edn-value []) "[]"))
  (should (equal (clara-explorer--edn-value (vector (propertize "a" 'face 'bold)))
                 "[\"a\"]")))

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
    (cl-letf (((symbol-function 'clara-explorer--goto) (lambda (_t &optional _no-push) nil))
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

(ert-deftest type-bounds-accumulator-param-args ()
  "Parameterized accumulator fn call: (my-sort-by-acc :x) :from [...]"
  (with-clara-buffer "(r/defrule foo [?acc <- (my-sort-by-acc :x) :from [:my-thing [this] (= ?x 1)]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing")))))
  ;; vector type with parameterized accumulator
  (with-clara-buffer "(r/defrule foo [?acc <- (my-sort-by-acc :x) :from [[:my-thing]]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "[:my-thing]"))))))

(ert-deftest type-bounds-accumulator-bare-symbol ()
  "Bare-symbol accumulator reference: my-shared-accum :from [...]"
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum :from [:my-thing [this] (= ?x 1)]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing")))))
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum :from [[:my-thing]]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "[:my-thing]")))))
  ;; qualified bare symbol
  (with-clara-buffer "(r/defrule foo [?acc <- my.ns/my-shared-accum :from [:my-thing]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing"))))))

(ert-deftest type-bounds-plain-not-misidentified-as-accumulator ()
  "Plain fact type must not be confused when next sexp happens to be :from inside constraints."
  (with-clara-buffer "(r/defrule foo [Application (= ?x 1)] => 1)"
    (search-forward "[Application")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "Application"))))))

(ert-deftest type-bounds-boolean-group-returns-nil ()
  (with-clara-buffer "(r/defrule foo [:and [A] [B]] => 1)"
    (search-forward "[:and")
    (search-backward "[")
    (let ((beg (point)))
      (should (null (clara-explorer--type-bounds-in-condition beg))))))

;; ---------------------------------------------------------------------------
;; lhs `:not`/`:exists` wrappers — fact type inside logical groups
;; ---------------------------------------------------------------------------

(ert-deftest lhs-type-not-wrapper-keyword ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:not [:my-ns/my-type]] => 1)"
    (should (equal (test--search-token ":my-ns/my-type") ":my-ns/my-type"))))

(ert-deftest lhs-type-exists-wrapper-keyword ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:exists [:my-type]] => 1)"
    (should (equal (test--search-token ":my-type") ":my-type"))))

(ert-deftest lhs-type-not-wrapper-with-constraints ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:not [MyType (= ?x 1)]] => 1)"
    (should (equal (test--search-token "MyType") "MyType"))))

(ert-deftest lhs-type-exists-wrapper-class ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:exists [MyType]] => 1)"
    (should (equal (test--search-token "MyType") "MyType"))))

(ert-deftest lhs-type-and-containing-not-and-exists ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:and [:not [:my-ns/my-type]] [:exists [:my-type]]] => 1)"
    (should (equal (test--search-token ":my-ns/my-type") ":my-ns/my-type"))
    (should (equal (test--search-token ":my-type") ":my-type"))))

(ert-deftest lhs-type-nested-not-around-and ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:not [:and [P] [Q]]] => 1)"
    ;; use P/Q (not in "and"/"or"/"not"/"exists") to avoid case-fold
    ;; "A" would match the `a' in ":and" when `case-fold-search' is t
    (should (equal (test--search-token "[P]") "P"))
    (should (equal (test--search-token "[Q]") "Q"))))

(ert-deftest lhs-type-not-outside-binding ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [?a <- MyType] [:not [OtherType]] => 1)"
    (should (equal (test--search-token "OtherType") "OtherType"))))

(ert-deftest vector-fact-rejects-logical-wrapper ()
  (with-clara-buffer "(r/defrule foo [:not [:my-ns/my-type]] => 1)"
    (search-forward ":not")
    ;; point on the wrapper operator itself must not be treated as a fact vector
    (should (null (clara-explorer--vector-fact-at-point)))
    ;; still finds the inner keyword vector when point is on the inner
    (search-forward ":my-ns/my-type")
    (should (equal (clara-explorer--vector-fact-at-point) "[:my-ns/my-type]"))))

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

(ert-deftest lhs-type-acc-param-args ()
  "Parameterized accumulator: (my-sort-by-acc :x) :from [...]"
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (my-sort-by-acc :x) :from [:my-thing [this] (= ?x 1)]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (my-sort-by-acc :x) :from [[:my-thing]]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") "[:my-thing]")))
  ;; point on accumulator symbol itself should still resolve to :from type
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- (my-sort-by-acc :x) :from [:my-thing]]\n  =>\n  (println 1))"
    (should (equal (test--search-token "my-sort-by-acc") ":my-thing"))))

(ert-deftest lhs-type-acc-bare-symbol ()
  "Bare-symbol accumulator reference: my-shared-accum :from [...]"
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- my-shared-accum :from [:my-thing [this] (= ?x 1)]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- my-shared-accum :from [[:my-thing]]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") "[:my-thing]")))
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- my.ns/my-shared-accum :from [[:my-thing :qual]]]\n  =>\n  (println 1))"
    (should (equal (test--search-token ":my-thing") "[:my-thing :qual]")))
  ;; point on the bare accumulator symbol itself resolves to :from type
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- my-shared-accum :from [:my-thing]]\n  =>\n  (println 1))"
    (should (equal (test--search-token "my-shared-accum") ":my-thing"))))

(ert-deftest lhs-type-acc-bare-symbol-with-constraints ()
  "Bare-symbol with vector type and constraints still yields vector type."
  (with-clara-buffer "(ns test) (r/defrule foo\n  [?acc <- my-shared-accum :from [[:my-thing] [this] (= ?x 1)]]\n  =>\n  (println 1))"
    (should (equal (test--search-token "[:my-thing]") "[:my-thing]"))
    (should (equal (test--search-token "[this]") "[:my-thing]"))))

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

(ert-deftest token-at-point-props-insert-type ()
  (with-clara-buffer "(r/defrule foo {:clara-rules/insert-types [::a ::b]} [?x <- ::a] => 1)"
    (search-forward "::b")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "::b")))))

(ert-deftest token-at-point-props-retract-type ()
  (with-clara-buffer "(r/defrule foo {:clara-rules/retract-types [::c]} [?x <- ::c] => 1)"
    (search-forward "::c")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "::c")))))

(ert-deftest token-at-point-props-vector-type ()
  (with-clara-buffer "(r/defrule foo {:clara-rules/insert-types [[:my-thing :qual]]} [?x <- :a] => 1)"
    (search-forward ":my-thing")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "[:my-thing :qual]")))))

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

;; ---------------------------------------------------------------------------
;; navigate dispatch — producer outside a rule/query uses the global path
;; ---------------------------------------------------------------------------

(ert-deftest navigate-producer-outside-production-uses-global-path ()
  "Producer navigation outside a defrule/defquery must still reach the server
   (global path) instead of bailing with \"not inside a rule/query\"."
  (with-clara-buffer "(ns test) (defn build [] (r/insert! (->LoanApplication {:a 1})))"
    (search-forward "LoanApplication")
    (let (code-seen)
      (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
                ((symbol-function 'cider-current-repl) (lambda (&rest _) 'dummy-conn))
                ((symbol-function 'cider-current-ns) (lambda () "test"))
                ((symbol-function 'cider-symbol-at-point)
                 (lambda (&optional _) "->LoanApplication"))
                ((symbol-function 'clara-explorer--resolve-token)
                 (lambda (_t _ns _c) nil))
                ((symbol-function 'clara-explorer--eval-edn)
                 (lambda (code _conn)
                   (setq code-seen code)
                   (let ((h (make-hash-table :test 'equal)))
                     (puthash :error "test-complete" h)
                     h)))
                (clara-explorer-transport 'nrepl))
        (clara-explorer--navigate :lhs))
      (should code-seen)
      (should (string-match-p ":side :lhs" code-seen))
      (should-not (string-match-p ":production" code-seen))
      (should (string-match-p ":token \"->LoanApplication\"" code-seen)))))

;; ---------------------------------------------------------------------------
;; Tier-2: real-deps coverage (Eldev with cider/parseedn/clojure-mode).
;; These tests are `skip-unless' on bare `emacs -Q --batch' (Tier-1) and
;; exercise the integration surface that the stubbed tier hides:
;;   - parseedn hash-table contract (vectors -> vector, maps -> hash-table)
;;   - clara-explorer--eval-edn + nrepl-dict wiring against real parseedn
;;   - cider-symbol-at-point keyword handling in real clojure-mode
;; Tier-3 (live nREPL server) remains deferred to the integration suite.
;; ---------------------------------------------------------------------------

(ert-deftest edn-map-roundtrips-through-real-parseedn ()
  "`clara-explorer--edn-map' output must be valid EDN for `parseedn-read-str'."
  (skip-unless (test-helper--parseedn-real-p))
  (let* ((edn (clara-explorer--edn-map
               (list :production "my.ns/rule" :side :lhs :token "Application")))
         (parsed (parseedn-read-str edn)))
    (should (hash-table-p parsed))
    (should (equal (gethash :production parsed) "my.ns/rule"))
    (should (eq (gethash :side parsed) :lhs))
    (should (equal (gethash :token parsed) "Application"))
    ;; nil values are omitted so :caller-ns absent
    (should-not (gethash :caller-ns parsed)))
  ;; vector/tuple tokens preserve their EDN shape
  (let* ((edn (clara-explorer--edn-map
               (list :production "my.ns/rule" :side :rhs :token "[:loan/status \"verified\"]")))
         (parsed (parseedn-read-str edn)))
    (should (equal (gethash :token parsed) "[:loan/status \"verified\"]"))))

(ert-deftest eval-edn-parses-nrepl-value-with-real-parseedn ()
  "`clara-explorer--eval-edn' must `parseedn-read-str' the nREPL \"value\"."
  (skip-unless (test-helper--parseedn-real-p))
  ;; Mock only the transport; parsing is real.
  (cl-letf (((symbol-function 'cider-nrepl-sync-request:eval)
             (lambda (_code _conn)
               ;; cider's nrepl-dict is a plist with leading `dict'; real
               ;; `nrepl-dict-get' understands this shape.
               (list 'dict "value" "{:direction :consumer :type \"Application\" :targets [{:name \"a/b\"}]}"))))
    (let ((result (clara-explorer--eval-edn "(+ 1 2)" 'dummy-conn)))
      (should (hash-table-p result))
      (should (eq (gethash :direction result) :consumer))
      (should (equal (gethash :type result) "Application"))
      (should (vectorp (gethash :targets result)))
      (let ((t0 (aref (gethash :targets result) 0)))
        (should (equal (gethash :name t0) "a/b"))))))

(ert-deftest eval-edn-handles-vector-targets-real-parseedn ()
  "Real parseedn returns EDN vectors as elisp vectors; our `choose-or-jump' must coerce."
  (skip-unless (test-helper--parseedn-real-p))
  (cl-letf (((symbol-function 'cider-nrepl-sync-request:eval)
             (lambda (_code _conn)
               (list 'dict "value" "{:targets [{:name \"a/b\"} {:name \"c/d\"}]}"))))
    (let* ((result (clara-explorer--eval-edn "code" 'dummy-conn))
           (targets (gethash :targets result)))
      (should (vectorp targets))
      (should (= (length targets) 2))
      ;; coerce as `clara-explorer--choose-or-jump' does
      (should (= (length (if (vectorp targets) (append targets nil) targets)) 2)))))

(ert-deftest cider-symbol-at-point-real-keyword ()
  "Real `cider-symbol-at-point' in `clojure-mode' must include leading `:' / `::'."
  (skip-unless (test-helper--real-deps-p))
  (with-clara-buffer "(r/defrule foo [?d <- ::supporting-document] => 1)"
    (search-forward "supporting-document")
    (backward-char 5) ;; inside the keyword
    (let ((tok (cider-symbol-at-point 'look-back)))
      (should (stringp tok))
      (should (string-prefix-p ":" tok))
      (should (string-suffix-p "supporting-document" tok))))
  ;; Also verify outside defrule — fallback path uses real cider symbol
  (with-clara-buffer "(defn foo [] :my-thing)"
    (search-forward "my-thing")
    (backward-char 2)
    (should (equal (cider-symbol-at-point 'look-back) ":my-thing"))))

(ert-deftest clojure-mode-handles-reader-macros-and-comments ()
  "Real `clojure-mode' syntax should not break `enclosing-production'."
  (skip-unless (test-helper--real-deps-p))
  (with-clara-buffer "(ns test) ;; comment\n(r/defrule foo [Application] => 1)"
    (search-forward "foo")
    (should (clara-explorer--enclosing-production)))
  (with-clara-buffer "(r/defrule foo \"docstring\" [A] => 1)"
    (search-forward "A")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (eq (clara-explorer--side-at-point form-start) :lhs)))))

;; ---------------------------------------------------------------------------
;; Metadata on var name — ^:meta before rule/query name
;; ---------------------------------------------------------------------------

(ert-deftest enclosing-production-with-single-metadata ()
  (with-clara-buffer "(r/defrule ^:my-meta my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule"))
      (should (eq (nth 1 enc) 'rule))))
  (with-clara-buffer "(r/defquery ^:my-meta my-query [?x] [Application])"
    (search-forward "my-query")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-query"))
      (should (eq (nth 1 enc) 'query)))))

(ert-deftest enclosing-production-with-multiple-metadata ()
  (with-clara-buffer "(r/defrule ^:a ^:b my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule"))))
  (with-clara-buffer "(r/defrule ^:a ^{:doc \"hi\"} my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule")))))

(ert-deftest enclosing-production-with-map-metadata ()
  (with-clara-buffer "(r/defrule ^{:doc \"hi\"} my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule"))))
  (with-clara-buffer "(r/defrule ^String my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule")))))

(ert-deftest enclosing-production-with-metadata-and-alias ()
  (with-clara-buffer "(my.alias/defrule ^:my-meta my-rule [Application] => 1)"
    (search-forward "my-rule")
    (let ((enc (clara-explorer--enclosing-production)))
      (should (equal (nth 0 enc) "my-rule"))
      (should (eq (nth 1 enc) 'rule))))
  (with-clara-buffer "(r/defrule ^:my-meta my-rule \"doc\" [Application] => 1)"
    (search-forward "my-rule")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (equal (nth 0 enc) "my-rule"))
      (should (clara-explorer--docstring-bounds form-start)))))

(ert-deftest lhs-type-with-metadata ()
  (with-clara-buffer "(r/defrule ^:my-meta my-rule [Application] => 1)"
    (should (equal (test--search-token "Application") "Application")))
  (with-clara-buffer "(r/defrule ^:a ^:b my-rule [?d <- ::supporting-document] => 1)"
    (should (equal (test--search-token "supporting-document") "::supporting-document"))))

(ert-deftest lhs-type-with-metadata-and-docstring ()
  (with-clara-buffer "(r/defrule ^:my-meta my-rule \"docstring\" [Application] => 1)"
    (should (equal (test--search-token "Application") "Application")))
  (with-clara-buffer "(r/defrule ^:m my-rule \"doc\" {:clara-rules/insert-types [::a]} [::a] => 1)"
    (search-forward "::a")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "::a")))))

(ert-deftest props-type-with-metadata-and-docstring ()
  (with-clara-buffer "(r/defrule ^:my-meta my-rule \"doc\" {:clara-rules/insert-types [::a ::b]} [?x <- ::a] => 1)"
    (search-forward "::b")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (eq side :rhs))
      (should (equal (clara-explorer--token-at-point form-start side) "::b")))))

;; ---------------------------------------------------------------------------
;; Docstring fact-type references — "inserts a :my-type when …"
;; ---------------------------------------------------------------------------

(ert-deftest docstring-bounds-with-and-without-docstring ()
  (with-clara-buffer "(r/defrule my-rule \"my doc\" [Application] => 1)"
    (let* ((enc (progn (search-forward "my-rule") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc))
           (bounds (clara-explorer--docstring-bounds form-start)))
      (should bounds)
      (should (eq (char-after (car bounds)) ?\"))))
  (with-clara-buffer "(r/defrule my-rule [Application] => 1)"
    (let* ((enc (progn (search-forward "my-rule") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc)))
      (should-not (clara-explorer--docstring-bounds form-start))))
  (with-clara-buffer "(r/defrule ^:m my-rule \"doc\" [Application] => 1)"
    (let* ((enc (progn (search-forward "my-rule") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc)))
      (should (clara-explorer--docstring-bounds form-start)))))

(ert-deftest docstring-token-plain-keyword ()
  (with-clara-buffer "(r/defrule my-rule \"inserts a :my-type when something\" [Application] => 1)"
    (search-forward ":my-type")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (tok (clara-explorer--docstring-token-at-point form-start)))
      (should (equal tok ":my-type"))))
  (with-clara-buffer "(r/defrule my-rule \"uses ::supporting-document\" [Application] => 1)"
    (search-forward "supporting-document")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (tok (clara-explorer--docstring-token-at-point form-start)))
      (should (equal tok "::supporting-document"))))
  (with-clara-buffer "(r/defrule my-rule \"mentions my.ns/MyType\" [Application] => 1)"
    (search-forward "MyType")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (equal (clara-explorer--docstring-token-at-point form-start) "my.ns/MyType")))))

(ert-deftest docstring-token-qualified-and-class ()
  (with-clara-buffer "(r/defrule my-rule \"see clojure.lang.PersistentVector\" [Application] => 1)"
    (search-forward "PersistentVector")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (equal (clara-explorer--docstring-token-at-point form-start) "clojure.lang.PersistentVector"))))
  (with-clara-buffer "(r/defrule my-rule \"inserts :loan/status\" [Application] => 1)"
    (search-forward ":loan/status")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (equal (clara-explorer--docstring-token-at-point form-start) ":loan/status")))))

(ert-deftest docstring-token-nil-cases ()
  (with-clara-buffer "(r/defrule my-rule \"hello world\" [Application] => 1)"
    (search-forward "world")
    (backward-char 2)
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      ;; point is on whitespace inside docstring — no token
      (goto-char (+ (car (clara-explorer--docstring-bounds form-start)) 7)) ;; inside "hello world" on space
      (should-not (clara-explorer--docstring-token-at-point form-start))))
  (with-clara-buffer "(r/defrule my-rule \"inserts :my-type\" [Application] => 1)"
    (let* ((enc (progn (search-forward "my-rule") (clara-explorer--enclosing-production)))
           (form-start (nth 2 enc)))
      ;; point on the opening quote — not inside content
      (goto-char (car (clara-explorer--docstring-bounds form-start)))
      (should-not (clara-explorer--docstring-token-at-point form-start))))
  (with-clara-buffer "(r/defrule my-rule [Application] => 1)"
    (search-forward "Application")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should-not (clara-explorer--docstring-token-at-point form-start)))))

(ert-deftest token-at-point-prefers-docstring ()
  (with-clara-buffer "(r/defrule my-rule \"inserts a :my-type\" [Application] => 1)"
    (search-forward ":my-type")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start))
           (tok (clara-explorer--token-at-point form-start side)))
      (should (equal tok ":my-type"))))
  ;; with metadata + docstring, docstring token still preferred
  (with-clara-buffer "(r/defrule ^:my-meta my-rule \"see :my-type\" [Application] => 1)"
    (search-forward ":my-type")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (equal (clara-explorer--token-at-point form-start side) ":my-type")))))

(ert-deftest docstring-token-with-metadata ()
  (with-clara-buffer "(r/defrule ^:my-meta my-rule \"inserts :my-type\" [Application] => 1)"
    (search-forward ":my-type")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc)))
      (should (equal (clara-explorer--docstring-token-at-point form-start) ":my-type")))))

(ert-deftest fallback-regexp-enumeration ()
  "Independent test for `clara-explorer--fallback-regexp` - succinct enumeration of expected matches."
  (cl-labels ((should-match (s rule) (should (string-match-p (clara-explorer--fallback-regexp rule) s)))
              (should-not-match (s rule) (should-not (string-match-p (clara-explorer--fallback-regexp rule) s))))
    ;; primary rx should match plain, aliased, and ^:meta forms
    (should-match "(defrule my-rule [A] => 1)" "my-rule")
    (should-match "(r/defrule my-rule [A] => 1)" "my-rule")
    (should-match "(my.alias/defrule my-rule [A] => 1)" "my-rule")
    (should-match "(defrule ^:my-meta my-rule [A] => 1)" "my-rule")
    (should-match "(defrule ^:a ^:b my-rule [A] => 1)" "my-rule")
    (should-match "(defrule ^String my-rule [A] => 1)" "my-rule")
    (should-match "(defquery my-query [?x] [A])" "my-query")
    (should-match "(defquery ^:m my-query [?x] [A])" "my-query")
    ;; rule names that end in a non-word Clojure symbol char (? ! * + < > -)
    ;; must still be bounded correctly — `\b` would fail after `?`.
    (should-match "(r/defrule app-outcome-approved? [A] => 1)" "app-outcome-approved?")
    (should-match "(r/defrule retry! [A] => 1)" "retry!")
    (should-match "(r/defrule key+ [A] => 1)" "key+")
    ;; negative: wrong head or wrong name
    (should-not-match "(def my-rule [A] => 1)" "my-rule")
    (should-not-match "(defrule other-rule [A] => 1)" "my-rule")
    ;; a ?-suffixed name must not match its own longer siblings
    (should-not-match "(defrule app-outcome-approved-args-demo [A] => 1)" "app-outcome-approved?")
    ;; ^{:map} contains space, so primary truncates - goto-fallback then uses
    ;; the whole-symbol fallback
    (should-not-match "(defrule ^{:doc \"hi\"} my-rule [A] => 1)" "my-rule")
    (should (string-match-p (clara-explorer--whole-symbol-regexp "my-rule")
                            "(defrule ^{:doc \"hi\"} my-rule [A] => 1)"))
    (should-not (string-match-p (clara-explorer--whole-symbol-regexp "app-outcome-approved?")
                                "app-outcome-approved-args-demo"))))


;; ---------------------------------------------------------------------------
;; Whitespace and comment skipping (clara-explorer--skip-ws)
;; ---------------------------------------------------------------------------

(ert-deftest skip-ws-skips-all-clojure-whitespace ()
  "Direct unit test for `clara-explorer--skip-ws` across all whitespace chars."
  (with-clara-buffer "  \t\r\n,,  \nnext"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  (with-clara-buffer ",,,next"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  (with-clara-buffer "\r\n\t next"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  (with-clara-buffer "   next"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  ;; no whitespace — point stays
  (with-clara-buffer "next"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next"))))

(ert-deftest skip-ws-skips-line-comments ()
  "Line comments `;` are treated as whitespace, including trailing newline."
  (with-clara-buffer "  ; comment\nnext"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  (with-clara-buffer "; comment\nnext"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  (with-clara-buffer " , ; comment 1\n ; comment 2\n next"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next")))
  ;; comment without trailing newline (EOB)
  (with-clara-buffer "  ; comment"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (eobp)))
  ;; comment between commas and spaces
  (with-clara-buffer ",; c1\n,; c2\nnext"
    (goto-char (point-min))
    (clara-explorer--skip-ws)
    (should (looking-at-p "next"))))

(ert-deftest type-bounds-accumulator-with-extra-whitespace ()
  "Accumulator `:from` parsing must tolerate extra whitespace, commas and \\r."
  ;; extra spaces, tabs, commas between accumulator and :from
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all)   ,,,  \t  :from    [:my-thing]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing")))))
  ;; CR + newline between :from and vector
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) :from \r\n [:my-thing]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing")))))
  ;; commas and newlines inside the :from vector's leading whitespace
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum  , :from  , \n [  :my-thing ]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) ":my-thing")))))
  ;; bare-symbol with vector type and extra whitespace
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum :from \t [[:my-thing]]] => 1)"
    (search-forward "[?acc")
    (search-backward "[")
    (let ((beg (point)))
      (let ((tb (clara-explorer--type-bounds-in-condition beg)))
        (should (equal (buffer-substring-no-properties (car tb) (cdr tb)) "[:my-thing]"))))))

(ert-deftest lhs-type-accumulator-with-comments ()
  "Comments `;` between accumulator parts must be ignored for LHS type."
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) ; leading comment\n :from [:my-thing]] => 1)"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) :from ; between\n [:my-thing]] => 1)"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum ; comment\n :from ; another\n [:my-thing]] => 1)"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(r/defrule foo [?acc <- (my-sort-by-acc :x) ; c\n :from ; c2\n [[:my-thing]]] => 1)"
    (should (equal (test--search-token ":my-thing") "[:my-thing]")))
  ;; comment inside the :from vector before the type
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) :from [ ; inner\n :my-thing]] => 1)"
    (should (equal (test--search-token ":my-thing") ":my-thing"))))

(ert-deftest lhs-type-with-extra-commas-and-whitespace ()
  "Clojure commas are whitespace — LHS type must be found through them."
  (with-clara-buffer "(r/defrule foo [?acc <- (acc/all) , :from , [:my-thing]] => 1)"
    (should (equal (test--search-token ":my-thing") ":my-thing")))
  (with-clara-buffer "(r/defrule foo [?acc <- my-shared-accum ,, :from ,, [[:my-thing :qual]]] => 1)"
    (should (equal (test--search-token ":my-thing") "[:my-thing :qual]")))
  ;; plain fact with commas (non-accumulator) should still work
  (with-clara-buffer "(r/defrule foo [Application ,, (= ?x 1)] => 1)"
    (should (equal (test--search-token "Application") "Application"))))


;; ---------------------------------------------------------------------------
;; Editor-side token resolution (resolve-before-send)
;; ---------------------------------------------------------------------------

(ert-deftest resolve-form-embeds-caller-ns-and-token ()
  (let ((code (clara-explorer--resolve-form "my.ns" "Doc.")))
    (should (string-match-p (regexp-quote "(symbol \"my.ns\")") code))
    (should (string-match-p (regexp-quote "(read-string token-text)") code))
    (should-not (string-match-p "%s" code))))

(ert-deftest resolve-form-escapes-token-text ()
  (let ((code (clara-explorer--resolve-form "ns" "a\"b\\c")))
    ;; still one string literal at the read site: nothing leaks out of it
    (should (string-match-p "read-string" code))
    (should-not (string-match-p "%s" code))))

(ert-deftest resolve-form-matches-canonical-template ()
  "Guard against drift from `clara.explorer.server.tokens/editor-token-resolve-form`: the
   canonical template's shape markers must all be present."
  (let ((code (clara-explorer--resolve-form "ns" "tok")))
    (dolist (frag '("(find-ns ns-sym)"
                    "(String/.startsWith token-text"
                    "(String/.endsWith n"
                    "(Class/.getName v)"
                    "(ns-resolve the-ns target)"
                    "(keyword? form) (str form)"
                    "(nil? form) nil"))
      (should (string-match-p (regexp-quote frag) code)))))

;; `parseedn-read-str' is autoloaded (never stubbed by test-helper), so tests
;; driving `--resolve-token' past the transport stub it with the same
;; `read-from-string' semantics.  Values here are string/nil literals only.
(defmacro test--with-stubbed-transport (eval-fn &rest body)
  "Run BODY with the nREPL transport and scalar EDN parsing stubbed.
EVAL-FN is the canned `cider-nrepl-sync-request:eval' replacement."
  (declare (indent 1))
  `(cl-letf (((symbol-function 'cider-nrepl-sync-request:eval) ,eval-fn)
             ((symbol-function 'nrepl-dict-get)
              (lambda (dict key) (cdr (assoc key dict))))
             ((symbol-function 'parseedn-read-str)
              (lambda (s) (car (read-from-string s)))))
     ,@body))

(ert-deftest resolve-token-returns-fq-on-value ()
  (test--with-stubbed-transport
      (lambda (_code _conn) '(("value" . "\"fq.Name\"")))
    (should (equal (clara-explorer--resolve-token "Doc" "my.ns" 'conn) "fq.Name"))))

(ert-deftest resolve-token-nil-falls-back ()
  (test--with-stubbed-transport
      (lambda (_code _conn) '(("value" . "nil")))
    (should (null (clara-explorer--resolve-token "Doc" "my.ns" 'conn))))
  (cl-letf (((symbol-function 'cider-nrepl-sync-request:eval)
             (lambda (_code _conn) '(("ex" . "boom"))))
            ((symbol-function 'nrepl-dict-get)
             (lambda (dict key) (cdr (assoc key dict)))))
    (should (null (clara-explorer--resolve-token "Doc" "my.ns" 'conn))))
  (should (null (clara-explorer--resolve-token nil "my.ns" 'conn))))

(ert-deftest navigate-sends-resolved-token ()
  (let (captured)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/rule" :kind 'rule :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token)
               (lambda (_t _ns _c) "fq.Doc"))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (code _conn) (setq captured code) nil))
              ((symbol-function 'message) (lambda (&rest _) nil))
              (clara-explorer-transport 'nrepl))
      (clara-explorer--navigate :lhs)
      (should (string-match-p (regexp-quote ":token \"fq.Doc\"") captured))
      (should (string-match-p (regexp-quote ":caller-ns \"ns\"") captured)))))

(ert-deftest navigate-falls-back-to-raw-token ()
  (let (captured)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/rule" :kind 'rule :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token)
               (lambda (_t _ns _c) nil))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (code _conn) (setq captured code) nil))
              ((symbol-function 'message) (lambda (&rest _) nil))
              (clara-explorer-transport 'nrepl))
      (clara-explorer--navigate :lhs)
      (should (string-match-p (regexp-quote ":token \"Doc\"") captured)))))

;; ---------------------------------------------------------------------------
;; babashka transport
;; ---------------------------------------------------------------------------

(ert-deftest registry-root-reads-env-var ()
  (let ((clara-explorer-registry-root nil))
    (cl-letf (((symbol-function 'getenv)
               (lambda (k) (when (equal k "CLARA_RULES_EXPLORER_REGISTRY") "/reg"))))
      (should (equal (clara-explorer--registry-root) "/reg")))))

(ert-deftest registry-root-defcustom-wins ()
  (let ((clara-explorer-registry-root "/custom"))
    (should (equal (clara-explorer--registry-root) "/custom"))))

(ert-deftest bb-prompt-selection-builds-edn ()
  (cl-letf (((symbol-function 'clara-explorer--registry-root) (lambda () "/reg"))
            ((symbol-function 'file-directory-p) (lambda (_d) t))
            ((symbol-function 'clara-explorer--bb-list-unit-repos)
             (lambda (_root)
               (list (let ((ht (make-hash-table :test 'equal)))
                       (puthash :key "loan-app-ruleset" ht)
                       (puthash :entry "{:repo \"loan-app-ruleset\"}" ht)
                       ht))))
            ((symbol-function 'completing-read)
             (lambda (_prompt coll _req _match) (car coll))))
    (should (equal (clara-explorer--bb-prompt-selection)
                   "{:root \"/reg\" :units [{:repo \"loan-app-ruleset\"}]}"))))

(ert-deftest bb-prompt-selection-splices-variant-entry ()
  (cl-letf (((symbol-function 'clara-explorer--registry-root) (lambda () "/reg"))
            ((symbol-function 'file-directory-p) (lambda (_d) t))
            ((symbol-function 'clara-explorer--bb-list-unit-repos)
             (lambda (_root)
               (list (let ((ht (make-hash-table :test 'equal)))
                       (puthash :key "loan-disposition-ruleset@ref=feature%2Fnew-tax" ht)
                       (puthash :entry "{:repo \"loan-disposition-ruleset\", :variant [[:ref \"feature/new-tax\"]]}" ht)
                       ht))))
            ((symbol-function 'completing-read)
             (lambda (_prompt coll _req _match) (car coll))))
    (should (equal (clara-explorer--bb-prompt-selection)
                   "{:root \"/reg\" :units [{:repo \"loan-disposition-ruleset\", :variant [[:ref \"feature/new-tax\"]]}]}"))))

(ert-deftest bb-list-unit-repos-shells-out ()
  (cl-letf (((symbol-function 'clara-explorer--bb-script) (lambda () "/p/editor-client.bb"))
            ((symbol-function 'file-readable-p) (lambda (_f) t))
            ((symbol-function 'call-process)
             (lambda (_prog _in _dest _disp script &rest args)
               (should (equal script "/p/editor-client.bb"))
               (should (equal args '("--list-units" "/reg")))
               (insert "ignored")
               0))
            ((symbol-function 'parseedn-read-str)
             (lambda (_s)
               (let ((ht (make-hash-table :test 'equal)))
                 (puthash :key "loan-app-ruleset" ht)
                 (puthash :entry "{:repo \"loan-app-ruleset\"}" ht)
                 (vector ht)))))
    (let ((repos (clara-explorer--bb-list-unit-repos "/reg")))
      (should (= 1 (length repos)))
      (should (equal (clara-explorer--edn-get :key (car repos)) "loan-app-ruleset"))
      (should (equal (clara-explorer--edn-get :entry (car repos)) "{:repo \"loan-app-ruleset\"}")))))

(ert-deftest bb-selection-caches ()
  (let ((clara-explorer--bb-selection-cache nil))
    (cl-letf (((symbol-function 'clara-explorer--bb-prompt-selection)
               (lambda () "{cached}")))
      (should (equal (clara-explorer--bb-selection) "{cached}"))
      (cl-letf (((symbol-function 'clara-explorer--bb-prompt-selection)
                 (lambda () (error "should not re-prompt"))))
        (should (equal (clara-explorer--bb-selection) "{cached}"))))))

(ert-deftest bb-eval-runs-and-parses ()
  (cl-letf (((symbol-function 'clara-explorer--bb-script) (lambda () "/p/editor-client.bb"))
            ((symbol-function 'file-readable-p) (lambda (_f) t))
            ((symbol-function 'call-process)
             (lambda (_prog _in _dest _disp script sel input)
               (should (equal script "/p/editor-client.bb"))
               (should (equal sel "{sel}"))
               (should (equal input "{input}"))
               (insert "{:direction :consumer}")
               0))
            ((symbol-function 'parseedn-read-str)
             (lambda (s) (if (equal s "{:direction :consumer}") 'parsed 'bad))))
    (should (eq (clara-explorer--bb-eval "{sel}" "{input}") 'parsed))))

(ert-deftest bb-script-uses-captured-directory ()
  "`clara-explorer--bb-script' must not depend on the navigation-time buffer."
  (let ((clara-explorer-bb-script nil))
    (should clara-explorer--directory)
    (should (string-suffix-p "editor-client.bb" (clara-explorer--bb-script)))
    (with-temp-buffer
      (setq buffer-file-name "/elsewhere/loan_app_rules.clj")
      (should (string-suffix-p "editor-client.bb" (clara-explorer--bb-script))))))

(ert-deftest effective-transport-auto-probes-server ()
  (let ((clara-explorer-transport 'auto))
    (cl-letf (((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) t)))
      (should (eq (clara-explorer--effective-transport) 'nrepl)))
    (cl-letf (((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) nil)))
      (should (eq (clara-explorer--effective-transport) 'bb)))))

(ert-deftest effective-transport-passes-explicit-through ()
  (let ((clara-explorer-transport 'bb))
    (cl-letf (((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) (error "must not be called"))))
      (should (eq (clara-explorer--effective-transport) 'bb)))))

(ert-deftest server-available-p-parses-probe ()
  (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
            ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
            ((symbol-function 'cider-nrepl-sync-request:eval)
             (lambda (_code _conn) (list 'dict "value" "true"))))
    (should (clara-explorer--server-available-p 'conn))))

(ert-deftest server-available-p-nil-when-absent ()
  (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
            ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
            ((symbol-function 'cider-nrepl-sync-request:eval)
             (lambda (_code _conn) (list 'dict "value" "false"))))
    (should-not (clara-explorer--server-available-p 'conn))))

(ert-deftest transport-status-reports-effective-mode ()
  (let ((clara-explorer-transport 'auto) msg)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) t))
              ((symbol-function 'message)
               (lambda (fmt &rest args) (setq msg (apply #'format fmt args)))))
      (clara-explorer-transport-status)
      (should (string-match-p "configured=auto" msg))
      (should (string-match-p "connected=yes" msg))
      (should (string-match-p "explorer-server=yes" msg))
      (should (string-match-p "effective=nrepl" msg)))))

(ert-deftest navigate-auto-uses-bb-when-no-server ()
  "Auto transport with a connected repl but no explorer server must use bb."
  (let ((clara-explorer-transport 'auto) captured-input)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) nil))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/rule" :kind 'rule :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token)
               (lambda (_t _ns _c) "fq.Doc"))
              ((symbol-function 'clara-explorer--bb-selection) (lambda () "{sel}"))
              ((symbol-function 'clara-explorer--bb-eval)
               (lambda (_sel input)
                 (setq captured-input input)
                 (let ((h (make-hash-table :test 'equal)))
                   (puthash :error "done" h)
                   h)))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (_c _conn) (error "should not use nREPL")))
              ((symbol-function 'message) (lambda (&rest _) nil)))
      (clara-explorer--navigate :lhs)
      (should (string-match-p (regexp-quote ":token \"fq.Doc\"") captured-input)))))

(ert-deftest navigate-auto-uses-nrepl-when-server ()
  "Auto transport with a running explorer server must use nREPL."
  (let ((clara-explorer-transport 'auto) captured-code)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--server-available-p)
               (lambda (&optional _c) t))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/rule" :kind 'rule :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token)
               (lambda (_t _ns _c) "fq.Doc"))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (code _conn) (setq captured-code code) nil))
              ((symbol-function 'clara-explorer--bb-eval)
               (lambda (_s _i) (error "should not use bb")))
              ((symbol-function 'message) (lambda (&rest _) nil)))
      (clara-explorer--navigate :lhs)
      (should (string-match-p (regexp-quote ":token \"fq.Doc\"") captured-code)))))

(ert-deftest navigate-dispatches-to-bb ()
  (let (captured-sel captured-input)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/rule" :kind 'rule :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token)
               (lambda (_t _ns _c) "fq.Doc"))
              ((symbol-function 'clara-explorer--bb-selection) (lambda () "{sel}"))
              ((symbol-function 'clara-explorer--bb-eval)
               (lambda (sel input)
                 (setq captured-sel sel captured-input input)
                 (let ((h (make-hash-table :test 'equal)))
                   (puthash :error "done" h)
                   h)))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (_c _conn) (error "should not use nREPL")))
              ((symbol-function 'message) (lambda (&rest _) nil))
              (clara-explorer-transport 'bb))
      (clara-explorer--navigate :lhs)
      (should (equal captured-sel "{sel}"))
      (should (string-match-p (regexp-quote ":token \"fq.Doc\"") captured-input))
      (should (string-match-p (regexp-quote ":production \"ns/rule\"") captured-input)))))

(ert-deftest refresh-noop-in-bb-mode ()
  (let ((clara-explorer-transport 'bb) msg)
    (cl-letf (((symbol-function 'message) (lambda (fmt &rest _) (setq msg fmt))))
      (clara-explorer-refresh)
      (should (string-match-p "no-op" msg)))))

(ert-deftest swap-session-noop-in-bb-mode ()
  (let ((clara-explorer-transport 'bb) msg)
    (cl-letf (((symbol-function 'message) (lambda (fmt &rest _) (setq msg fmt))))
      (clara-explorer-swap-session)
      (should (string-match-p "no-op" msg)))))

(ert-deftest toggle-transport-switches ()
  (let ((clara-explorer-transport 'nrepl) msg)
    (cl-letf (((symbol-function 'message) (lambda (fmt &rest args) (setq msg (apply #'format fmt args)))))
      (clara-explorer-toggle-transport)
      (should (eq clara-explorer-transport 'bb))
      (should (string-match-p "bb" msg))
      (clara-explorer-toggle-transport)
      (should (eq clara-explorer-transport 'nrepl))
      (should (string-match-p "nrepl" msg)))))

(ert-deftest select-unit-warns-under-nrepl ()
  (let ((clara-explorer-transport 'nrepl) msg)
    (cl-letf (((symbol-function 'message) (lambda (fmt &rest args) (setq msg (apply #'format fmt args))))
              ((symbol-function 'clara-explorer--bb-prompt-selection)
               (lambda () (error "should not prompt"))))
      (clara-explorer-select-unit)
      (should (string-match-p "warning" msg)))))

(ert-deftest select-unit-prompts-under-bb ()
  (let ((clara-explorer-transport 'bb) (clara-explorer--bb-selection-cache nil) msg)
    (cl-letf (((symbol-function 'clara-explorer--bb-prompt-selection) (lambda () "{unit}"))
              ((symbol-function 'message) (lambda (fmt &rest args) (setq msg (apply #'format fmt args)))))
      (clara-explorer-select-unit)
      (should (equal clara-explorer--bb-selection-cache "{unit}"))
      (should (string-match-p "bb unit set" msg)))))



;; ---------------------------------------------------------------------------
;; Wrapper heads — point on `:not`/`:and` (or the wrapper bracket)
;; ---------------------------------------------------------------------------

(ert-deftest lhs-type-not-wrapper-head-resolves-inner-type ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:not [:my-ns/my-type]] => 1)"
    (should (equal (test--search-token ":not") ":my-ns/my-type"))))

(ert-deftest lhs-type-not-wrapper-bracket-resolves-inner-type ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:not [:my-ns/my-type]] => 1)"
    (search-forward "[:not")
    (search-backward "[")
    (let* ((enc (clara-explorer--enclosing-production))
           (form-start (nth 2 enc))
           (side (clara-explorer--side-at-point form-start)))
      (should (equal (clara-explorer--token-at-point form-start side) ":my-ns/my-type")))))

(ert-deftest lhs-type-and-wrapper-head-resolves-first-child ()
  (with-clara-buffer "(ns test) (r/defrule x-rule [:and [P] [Q]] => 1)"
    (should (equal (test--search-token ":and") "P"))))

;; ---------------------------------------------------------------------------
;; Operation prep and dispatch (`--prepare-query' / `--op-result')
;; ---------------------------------------------------------------------------

(ert-deftest prepare-query-sends-no-operation ()
  (let (sent)
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'clara-explorer--effective-transport) (lambda (_c) 'bb))
              ((symbol-function 'clara-explorer--context)
               (lambda () (list :production "ns/q" :kind 'query :side :lhs
                                :caller-ns "ns" :token "Doc")))
              ((symbol-function 'clara-explorer--resolve-token) (lambda (_t _ns _c) "fq.Doc"))
              ((symbol-function 'clara-explorer--bb-eval) (lambda (&rest _) (setq sent 'bb-eval)))
              ((symbol-function 'clara-explorer--bb-run) (lambda (&rest _) (setq sent 'bb-run)))
              ((symbol-function 'clara-explorer--eval-edn) (lambda (&rest _) (setq sent 'nrepl))))
      (let ((query (clara-explorer--prepare-query)))
        (should query)
        (should (eq (plist-get query :transport) 'bb))
        (should (equal (plist-get (plist-get query :input) :production) "ns/q"))
        (should (equal (plist-get (plist-get query :input) :side) :lhs))
        (should (equal (plist-get (plist-get query :input) :caller-ns) "ns"))
        (should (equal (plist-get (plist-get query :input) :token) "fq.Doc"))
        (should (eq (plist-get (plist-get query :ctx) :kind) 'query))
        (should (equal (plist-get query :conn) 'conn))))
    (should-not sent)))

(ert-deftest prepare-query-works-inside-a-defquery ()
  (with-clara-buffer "(ns test) (r/defquery find-x [?x] [DocumentCheck (= status :pass)])"
    (search-forward "DocumentCheck")
    (cl-letf (((symbol-function 'cider-connected-p) (lambda () t))
              ((symbol-function 'cider-current-repl) (lambda (&rest _) 'conn))
              ((symbol-function 'cider-current-ns) (lambda (&rest _) "test"))
              ((symbol-function 'clara-explorer--effective-transport) (lambda (_c) 'nrepl))
              ((symbol-function 'clara-explorer--resolve-token) (lambda (_t _ns _c) nil)))
      (let ((query (clara-explorer--prepare-query)))
        (should query)
        (should (eq (plist-get (plist-get query :ctx) :kind) 'query))
        (should (equal (plist-get (plist-get query :input) :production) "test/find-x"))
        (should (equal (plist-get (plist-get query :input) :token) "DocumentCheck"))))))

(ert-deftest op-result-provenance-transports ()
  (let (bb-args nrepl-code
        (input (list :node (list :kind :production :name "ns/p")
                     :direction :producer
                     :depth 0
                     :ancestors ["ns/outer"]))
        (bb-query (list :input (list :token "Doc") :transport 'bb :conn nil))
        (nrepl-query (list :input (list :token "Doc") :transport 'nrepl :conn nil)))
    (cl-letf (((symbol-function 'clara-explorer--bb-selection) (lambda () "{sel}"))
              ((symbol-function 'clara-explorer--bb-run)
               (lambda (args) (setq bb-args args) 'bb-result))
              ((symbol-function 'clara-explorer--eval-edn)
               (lambda (code _conn) (setq nrepl-code code) 'nrepl-result)))
      (should (eq (clara-explorer--op-result bb-query 'fact-type-provenance input) 'bb-result))
      (should (equal (car bb-args) "--provenance"))
      (should (equal (cadr bb-args) "{sel}"))
      (should (string-match-p (regexp-quote ":node {:kind :production :name \"ns/p\"}")
                              (caddr bb-args)))
      (should (string-match-p (regexp-quote ":ancestors [\"ns/outer\"]") (caddr bb-args)))
      (should (eq (clara-explorer--op-result nrepl-query 'fact-type-provenance input)
                  'nrepl-result))
      (should (string-match-p (regexp-quote "client/fact-type-provenance") nrepl-code))
      (should (string-match-p (regexp-quote ":direction :producer") nrepl-code)))))

(ert-deftest op-result-navigate-keeps-the-wire-contract ()
  (let (bb-input
        (query (list :input (list :production "ns/p" :side :lhs :caller-ns "ns" :token "T")
                     :transport 'bb
                     :conn nil)))
    (cl-letf (((symbol-function 'clara-explorer--bb-selection) (lambda () "{sel}"))
              ((symbol-function 'clara-explorer--bb-eval)
               (lambda (_sel input) (setq bb-input input) 'bb-result)))
      (should (eq (clara-explorer--op-result query 'navigate) 'bb-result))
      (should (string-match-p (regexp-quote ":side :lhs") bb-input))
      (should (string-match-p (regexp-quote ":token \"T\"") bb-input)))))

;; ---------------------------------------------------------------------------
;; Provenance view (tabulated-list-mode, no packages)
;; ---------------------------------------------------------------------------

;; ERT has no clojure.test-style grouping form; this names a group of assertions
;; without changing how failures are reported (the enclosing deftest is the unit).
(defmacro test--group (_string &rest body)
  (declare (indent 1))
  `(progn ,@body))

(defun test--prov (&rest kvs)
  "A provenance node shaped as parseedn returns one: a hash-table, vectors for lists."
  (let ((h (make-hash-table :test 'equal)))
    (while kvs (puthash (pop kvs) (pop kvs) h))
    h))

(defun test--prov-labels (entries)
  "The plain-text label of each tabulated-list ENTRY."
  (mapcar (lambda (row) (substring-no-properties (aref (cadr row) 0))) entries))

(defun test--prov-ids (entries)
  (mapcar #'car entries))

(ert-deftest provenance-row-labels-render-the-contract-test ()
  (let* ((matches (vector (test--prov :name ":loan-app/application-outcome"
                                      :short ":loan-app/application-outcome")))
         (root (test--prov
                :kind :fact-type
                :type "clara.explorer.test.rules.loan_app_rules.ApplicationOutcome"
                :type-short "ApplicationOutcome"
                :children
                (vector
                 (test--prov :kind :production
                             :name "clara.explorer.test.rules.loan-outcome-notices/notice-approved-app"
                             :ns "clara.explorer.test.rules.loan-outcome-notices"
                             :type "rule"
                             :unit "loan-disposition-ruleset"
                             :via :consume
                             :matches matches
                             :bridge? t
                             :children [])
                 (test--prov :kind :production
                             :name "clara.explorer.test.rules.loan-app-rules/find-app-outcome"
                             :ns "clara.explorer.test.rules.loan-app-rules"
                             :type "query"
                             :children [])
                 (test--prov :kind :production
                             :name "clara.explorer.test.rules.loan-app-rules/app-outcome-approved-args-demo"
                             :ns "clara.explorer.test.rules.loan-app-rules"
                             :type "rule"
                             :children [])
                 (test--prov :kind :production
                             :name "clara.explorer.test.rules.loan-app-rules/unresolved"
                             :ns "clara.explorer.test.rules.loan-app-rules"
                             :type "rule"
                             :outputs :unknown
                             :children []))))
         (labels (with-temp-buffer
                   (test--prov-labels (clara-explorer--provenance-load root :consumer nil nil)))))
    (test--group "the fact type shows its contract short name"
      (should (string-match-p "^ApplicationOutcome" (car labels))))
    (test--group "a bridge production says via <short match>"
      (should (cl-find-if (lambda (l) (string-match-p "via :loan-app/application-outcome" l))
                          labels)))
    (test--group "the unit and the kind markers"
      (should (cl-find-if (lambda (l) (string-match-p "@loan-disposition-ruleset" l)) labels))
      (should (cl-find-if (lambda (l) (string-match-p "(query)" l)) labels)))
    (test--group "leaf kinds"
      (should (cl-find-if (lambda (l) (string-match-p "(no outputs)" l)) labels))
      (should (cl-find-if (lambda (l) (string-match-p "(outputs unknown)" l)) labels)))))

(ert-deftest provenance-labels-use-contract-short-names-and-markers-test ()
  (test--group "the short name is the contract's :type-short, never derived from :type"
    (let* ((node (test--prov :kind :fact-type
                             :type "some.deep.Klass"
                             :type-short "SHORT"
                             :external? t
                             :children []))
           (label (substring-no-properties
                   (clara-explorer--provenance-fact-type-label node))))
      (should (string-prefix-p "SHORT" label))
      (should (string-match-p "some\\.deep\\.Klass" label))
      (should (string-match-p "(external)" label))))
  (test--group "cycle and already-shown markers"
    (let ((cycle (test--prov :kind :production :name "ns/a" :ns "ns" :type "rule"
                             :cycle? t :children []))
          (seen (test--prov :kind :production :name "ns/b" :ns "ns" :type "rule"
                            :seen? t :children [])))
      (should (string-match-p "↻ cycle"
                              (clara-explorer--provenance-production-label cycle :producer)))
      (should (string-match-p "↑ shown above"
                              (clara-explorer--provenance-production-label seen :producer)))
      (test--group "an expanded seen row drops the marker"
        (should-not (string-match-p
                     "↑ shown above"
                     (clara-explorer--provenance-production-label seen :producer t))))))
  (test--group "a retract edge is marked in each shape the backend emits"
    (let ((producer-retract (test--prov :kind :production :name "ns/r" :ns "ns"
                                        :type "rule" :via :retract :children []))
          (producer-both (test--prov :kind :production :name "ns/r" :ns "ns" :type "rule"
                                     :via :retract :vias [:insert :retract] :children []))
          (consumer-retract (test--prov :kind :fact-type :type "T" :type-short "T"
                                        :vias [:retract] :children []))
          (consumer-both (test--prov :kind :fact-type :type "T" :type-short "T"
                                     :vias [:insert :retract] :children [])))
      (should (string-match-p "(retract)"
                              (clara-explorer--provenance-production-label
                               producer-retract :producer)))
      (should (string-match-p (regexp-quote "(insert+retract)")
                              (clara-explorer--provenance-production-label
                               producer-both :producer)))
      (should (string-match-p "(retract)"
                              (clara-explorer--provenance-fact-type-label
                               consumer-retract)))
      (should (string-match-p (regexp-quote "(insert+retract)")
                              (clara-explorer--provenance-fact-type-label
                               consumer-both))))))

(ert-deftest provenance-toggle-is-keyed-by-full-path-test ()
  (let* ((q-node (test--prov :kind :production :name "ns/q" :ns "ns" :type "rule"))
         (ft-a (test--prov :kind :fact-type :type "A" :type-short "A"
                           :children (vector q-node)))
         (ft-b (test--prov :kind :fact-type :type "B" :type-short "B"
                           :children (vector q-node)))
         (p (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                        :children (vector ft-a ft-b)))
         (root (test--prov :kind :fact-type :type "T" :type-short "T"
                           :children (vector p))))
    (with-temp-buffer
      (clara-explorer--provenance-load root :producer nil nil)
      (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                 (lambda (&rest _)
                   (test--prov :kind :production :name "ns/q" :ns "ns" :type "rule"
                               :children (vector (test--prov :kind :fact-type
                                                             :type "A2" :type-short "A2"
                                                             :children []))))))
        (should (clara-explorer--provenance-toggle-path
                 (vector "T" "ns/p" "A" "ns/q")))
        (let ((labels (test--prov-labels
                       (clara-explorer--provenance-entries root :producer))))
          (should (= 1 (length (cl-remove-if-not
                                (lambda (l) (string-match-p "A2" l)) labels))))))
      (test--group "the sibling path stays collapsed"
        (should (= 7 (length (clara-explorer--provenance-entries root :producer))))))))

(ert-deftest provenance-pre-expanded-depth-needs-no-fetch-test ()
  (let* ((grandchild (test--prov :kind :production :name "ns/r" :ns "ns" :type "rule"
                                 :children []))
         (child-ft (test--prov :kind :fact-type :type "U" :type-short "U"
                               :children (vector grandchild)))
         (p (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                        :children (vector child-ft)))
         (root (test--prov :kind :fact-type :type "T" :type-short "T"
                           :children (vector p))))
    (with-temp-buffer
      (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                 (lambda (&rest _) (error "must not fetch"))))
        (let ((ids (test--prov-ids (clara-explorer--provenance-load root :producer nil nil))))
          (should (cl-find-if (lambda (id) (equal id (vector "T" "ns/p" "U" "ns/r")))
                              ids)))))))

(ert-deftest provenance-tab-request-shape-test ()
  (test--group "the root layer has no ancestors"
    (let* ((p1 (test--prov :kind :production :name "ns/p1" :ns "ns" :type "rule"))
           (root (test--prov :kind :fact-type :type "T" :type-short "T"
                             :children (vector p1)))
           captured)
      (with-temp-buffer
        (clara-explorer--provenance-load root :producer 'query nil)
        (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                   (lambda (_q input)
                     (setq captured input)
                     (test--prov :kind :production :name "ns/p1" :ns "ns" :type "rule"
                                 :children []))))
          (should (clara-explorer--provenance-toggle-path (vector "T" "ns/p1"))))
        (should (equal (plist-get captured :node)
                       (list :kind :production :name "ns/p1")))
        (should (equal (plist-get captured :depth) 0))
        (should (eq (plist-get captured :direction) :producer))
        (should (equal (plist-get captured :ancestors) [])))))
  (test--group "a nested production sends the production names above it, minus self"
    (let* ((p2 (test--prov :kind :production :name "ns/p2" :ns "ns" :type "rule"))
           (u (test--prov :kind :fact-type :type "U" :type-short "U"
                          :children (vector p2)))
           (p1 (test--prov :kind :production :name "ns/p1" :ns "ns" :type "rule"
                           :children (vector u)))
           (root (test--prov :kind :fact-type :type "T" :type-short "T"
                             :children (vector p1)))
           captured)
      (with-temp-buffer
        (clara-explorer--provenance-load root :producer 'query nil)
        (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                   (lambda (_q input)
                     (setq captured input)
                     (test--prov :kind :production :name "ns/p2" :ns "ns" :type "rule"
                                 :children []))))
          (should (clara-explorer--provenance-toggle-path
                   (vector "T" "ns/p1" "U" "ns/p2"))))
        (should (equal (plist-get captured :ancestors) ["ns/p1"])))))
  (test--group "a :seen? row is fetched on demand at depth 0"
    (let* ((seen (test--prov :kind :production :name "ns/seen" :ns "ns" :type "rule"
                             :seen? t))
           (root (test--prov :kind :fact-type :type "T" :type-short "T"
                             :children (vector seen)))
           captured)
      (with-temp-buffer
        (clara-explorer--provenance-load root :producer 'query nil)
        (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                   (lambda (_q input)
                     (setq captured input)
                     (test--prov :kind :production :name "ns/seen" :ns "ns" :type "rule"
                                 :children []))))
          (should (clara-explorer--provenance-toggle-path (vector "T" "ns/seen"))))
        (should (equal (plist-get captured :node)
                       (list :kind :production :name "ns/seen")))
        (should (equal (plist-get captured :depth) 0))
        (should (equal (plist-get captured :ancestors) [])))))
  (test--group "cycle, leaf, outputs-unknown, and message rows do not fetch"
    (let* ((leaf (test--prov :kind :production :name "ns/leaf" :ns "ns" :type "rule"
                             :children []))
           (cycle (test--prov :kind :production :name "ns/cycle" :ns "ns" :type "rule"
                              :cycle? t))
           (unknown (test--prov :kind :production :name "ns/unknown" :ns "ns" :type "rule"
                                :outputs :unknown :children []))
           (message (clara-explorer--provenance-message-node "boom"))
           (ft (test--prov :kind :fact-type :type "A" :type-short "A"
                           :children (vector leaf cycle unknown message)))
           (p (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                          :children (vector ft)))
           (root (test--prov :kind :fact-type :type "T" :type-short "T"
                             :children (vector p))))
      (with-temp-buffer
        (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                   (lambda (&rest _) (error "must not fetch")))
                  ((symbol-function 'message) (lambda (&rest _) nil)))
          (clara-explorer--provenance-load root :producer nil nil)
          (dolist (path (list (vector "T" "ns/p" "A" "ns/leaf")
                              (vector "T" "ns/p" "A" "ns/cycle")
                              (vector "T" "ns/p" "A" "ns/unknown")
                              (vector "T" "ns/p" "A" "boom")))
            (should-not (clara-explorer--provenance-toggle-path path))))))))

(ert-deftest provenance-request-turns-errors-into-message-nodes-test ()
  (test--group "a backend error map"
    (cl-letf (((symbol-function 'clara-explorer--op-result)
               (lambda (&rest _) (test--prov :error "no fact type found"))))
      (let ((node (clara-explorer--provenance-request nil nil)))
        (should (eq (clara-explorer--edn-get :kind node) :message))
        (should (equal (clara-explorer--edn-get :text node) "no fact type found")))))
  (test--group "a transport failure (bb exit) becomes a message node"
    (cl-letf (((symbol-function 'clara-explorer--op-result)
               (lambda (&rest _) (user-error "editor-client.bb exited 1"))))
      (let ((node (clara-explorer--provenance-request nil nil)))
        (should (string-match-p "exited 1" (clara-explorer--edn-get :text node))))))
  (test--group "a nil result and a message root render as one message row"
    (cl-letf (((symbol-function 'clara-explorer--op-result) (lambda (&rest _) nil)))
      (should (eq (clara-explorer--edn-get :kind (clara-explorer--provenance-request nil nil))
                  :message)))
    (with-temp-buffer
      (should (equal (test--prov-labels
                      (clara-explorer--provenance-load
                       (clara-explorer--provenance-message-node "boom") :producer nil nil))
                     '("boom"))))))

(ert-deftest provenance-visit-reports-why-it-does-not-jump-test ()
  (let* ((prod (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                           :source (test--prov :var? t)))
         (ft (test--prov :kind :fact-type :type "T" :type-short "T"
                         :children (vector prod)))
         (messages '())
         (goto-called nil))
    (with-temp-buffer
      (clara-explorer--provenance-load ft :producer nil nil)
      (cl-letf (((symbol-function 'message)
                 (lambda (fmt &rest args) (push (apply #'format fmt args) messages)))
                ((symbol-function 'clara-explorer--jump-to-production)
                 (lambda (&rest _) (setq goto-called t))))
        (test--group "a fact-type row explains itself"
          (cl-letf (((symbol-function 'tabulated-list-get-id)
                     (lambda (&optional _) (vector "T"))))
            (clara-explorer-provenance-visit))
          (should (cl-find-if (lambda (m) (string-match-p "not a production row" m))
                              messages)))
        (test--group "an ID with no node asks for a refresh"
          (cl-letf (((symbol-function 'tabulated-list-get-id)
                     (lambda (&optional _) (vector "T" "gone"))))
            (clara-explorer-provenance-visit))
          (should (cl-find-if (lambda (m) (string-match-p "stale row" m)) messages)))
        (test--group "a production row jumps"
          (cl-letf (((symbol-function 'tabulated-list-get-id)
                     (lambda (&optional _) (vector "T" "ns/p"))))
            (clara-explorer-provenance-visit))
          (should goto-called))))))

(ert-deftest provenance-collapse-then-expand-uses-the-cache-test ()
  (let* ((child (test--prov :kind :fact-type :type "U" :type-short "U" :children []))
         (p (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"))
         (root (test--prov :kind :fact-type :type "T" :type-short "T"
                           :children (vector p)))
         (fetches 0))
    (with-temp-buffer
      (clara-explorer--provenance-load root :producer 'query nil)
      (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                 (lambda (&rest _)
                   (setq fetches (1+ fetches))
                   (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                               :children (vector child)))))
        (should (clara-explorer--provenance-toggle-path (vector "T" "ns/p")))
        (should (clara-explorer--provenance-toggle-path (vector "T" "ns/p")))
        (should (clara-explorer--provenance-toggle-path (vector "T" "ns/p")))
        (should (= 1 fetches))   ; re-expanding reads the cached children
        (should (= 3 (length (clara-explorer--provenance-entries root :producer))))))))

(ert-deftest provenance-header-shows-the-direction-test ()
  (with-temp-buffer
    (clara-explorer-provenance-mode)
    (clara-explorer--provenance-load
     (test--prov :kind :fact-type :type "T" :type-short "T" :children [])
     :consumer nil nil)
    (should (equal (car (aref tabulated-list-format 0)) "Consumer Provenance"))
    (should-not (nth 2 (aref tabulated-list-format 0)))
    (should-error (tabulated-list-sort 0))))

(defconst test--scratch-dir
  (expand-file-name "target/tmp"
                    (file-name-directory
                     (or load-file-name buffer-file-name default-directory)))
  "Repo-local scratch directory for tests that need a real file.")

(defmacro test--with-repl-lookup (var-info-fn ns-path-fn &rest body)
  "Run BODY with the REPL's var lookup and `cider/ns-path' answered by stubs.
`cider-find-file' visits the path as is, and messages and jump pushes are
silenced.  Messages are collected in `messages', which BODY's caller binds."
  (declare (indent 2))
  `(cl-letf (((symbol-function 'cider-var-info) ,var-info-fn)
             ((symbol-function 'cider-sync-request:ns-path) ,ns-path-fn)
             ((symbol-function 'cider-find-file) #'find-file-noselect)
             ((symbol-function 'message)
              (lambda (fmt &rest args) (push (apply #'format fmt args) messages)))
             ((symbol-function 'clara-explorer--push-jump) (lambda () nil))
             ((symbol-function 'clara-explorer--log) (lambda (&rest _) nil)))
     ,@body))

(defun test--write-demo-ns (file)
  "Write a demo.ns source to FILE: the ns form on line 1, rule-a on line 3."
  (make-directory (file-name-directory file) t)
  (with-temp-file file
    (insert "(ns demo.ns)\n\n(r/defrule rule-a [] => 1)\n")))

(defun test--demo-ns-name-stub (file)
  "A `cider-get-ns-name' stub: \"demo.ns\" in the buffer visiting FILE only."
  (lambda (&rest _) (and (equal (buffer-file-name) file) "demo.ns")))

(ert-deftest provenance-goto-fallback-reports-a-missing-namespace-test ()
  "An unloaded namespace off the classpath is reported, and the empty
`cider/ns-path' answer never reaches `cider-find-file' — which would expand it
to `default-directory' and open that directory in dired."
  (let ((messages '())
        (find-file-args '())
        (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                          :type "rule" :source (test--prov :var? nil))))
    (with-temp-buffer
      (test--with-repl-lookup (lambda (&rest _) nil) (lambda (&rest _) "")
        (cl-letf (((symbol-function 'cider-find-file)
                   (lambda (file) (push file find-file-args) nil)))
          (should-not (clara-explorer--goto node))))
      (should-not find-file-args)
      (should (cl-find-if (lambda (m) (string-match-p "classpath" m)) messages))
      (should (cl-find-if (lambda (m) (string-match-p "demo\\.ns" m)) messages)))))

(ert-deftest provenance-goto-fallback-reports-from-a-file-buffer-visiting-another-ns-test ()
  (let* ((file (expand-file-name "provenance-goto-other-ns.clj" test--scratch-dir))
         (messages '())
         (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                           :type "rule" :source (test--prov :var? nil)))
         (buffer nil))
    (make-directory test--scratch-dir t)
    (with-temp-file file
      (insert "(ns other.ns)\n\n(r/defrule other-rule [] => 1)\n"))
    (unwind-protect
        (with-current-buffer (find-file-noselect file)
          (setq buffer (current-buffer))
          (goto-char (point-max))
          (let ((point-before (point)))
            (test--with-repl-lookup (lambda (&rest _) nil) (lambda (&rest _) "")
              (cl-letf (((symbol-function 'cider-get-ns-name)
                         (lambda (&rest _) "other.ns")))
                (should-not (clara-explorer--goto node))))
            ;; point is not clobbered in the wrong buffer
            (should (= point-before (point)))
            ;; the classpath diagnostic fires instead of a bogus search of other.ns
            (should (cl-find-if (lambda (m) (string-match-p "classpath" m)) messages))
            (should (cl-find-if (lambda (m) (string-match-p "demo\\.ns" m)) messages))
            (should-not (cl-find-if (lambda (m) (string-match-p "not found in" m)) messages))))
      (when (buffer-live-p buffer) (kill-buffer buffer))
      (delete-file file))))

(ert-deftest provenance-goto-fallback-ignores-the-connections-namespace-test ()
  "A buffer that merely shares the REPL's ns is never searched.
`cider-current-ns' answers with the connection's namespace for non-Clojure
buffers, so with the REPL sitting in the target ns an unrelated buffer (the
REPL itself, `*scratch*', a log) used to pass the guard: point moved to the top
of that buffer and the rule search ran there."
  (let ((messages '())
        (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                          :type "rule" :source (test--prov :var? nil))))
    (with-temp-buffer
      (insert "unrelated notes\n\nthe rule-a line is a red herring\n")
      (goto-char (point-max))
      (let ((point-before (point)))
        (test--with-repl-lookup (lambda (&rest _) nil) (lambda (&rest _) "")
          (cl-letf (((symbol-function 'cider-current-ns)
                     (lambda (&rest _) "demo.ns"))   ; the connection's ns
                    ((symbol-function 'cider-get-ns-name)
                     (lambda (&rest _) nil)))        ; this buffer has no ns form
            (should-not (clara-explorer--goto node))))
        (should (= point-before (point)))
        (should (cl-find-if (lambda (m) (string-match-p "classpath" m)) messages))
        (should-not (cl-find-if (lambda (m) (string-match-p "not found in" m)) messages))))))

(ert-deftest provenance-goto-fallback-returns-nil-when-cider-signals-test ()
  (let ((messages '())
        (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                          :type "rule" :source (test--prov :var? nil))))
    (with-temp-buffer
      (goto-char (point-max))
      (let ((point-before (point)))
        (test--with-repl-lookup
            (lambda (&rest _) (user-error "No linked CIDER sessions"))
            (lambda (&rest _) (error "ns-path must not run after the REPL failed"))
          (should-not (clara-explorer--goto node)))
        (should (= point-before (point))))   ; point is not moved after the error
      (should (cl-find-if (lambda (m)
                            (string-match-p "could not open demo\\.ns (No linked CIDER sessions)" m))
                          messages))
      (should-not (cl-find-if (lambda (m) (string-match-p "not found in" m)) messages)))))

(ert-deftest provenance-navigation-window-prefers-the-sources-own-window-test ()
  "RET runs in the window showing the production's source, not the MRU window.
Two windows are open — the rules file and an unrelated buffer — and the caller
is in the provenance view.  The source's window must win, so the jump neither
clobbers nor searches the unrelated one; with no such window the MRU one is
used."
  (let* ((file (expand-file-name "provenance-nav-window.clj" test--scratch-dir))
         (src nil)
         (other nil))
    (test--write-demo-ns file)
    (unwind-protect
        (progn
          (setq src (find-file-noselect file))
          (setq other (get-buffer-create " *clara-unrelated*"))
          (delete-other-windows)
          (let ((src-win (selected-window))
                (other-win (split-window)))
            (set-window-buffer src-win src)
            (set-window-buffer other-win other)
            (cl-letf (((symbol-function 'cider-get-ns-name) (test--demo-ns-name-stub file))
                      ((symbol-function 'get-mru-window)
                       (lambda (&rest _) other-win)))
              (select-window other-win)
              (should (eq (clara-explorer--ns-source-buffer "demo.ns") src))
              (should (eq (clara-explorer--navigation-window src) src-win))
              (set-window-buffer src-win other)
              (should (eq (clara-explorer--navigation-window src) other-win)))))
      (when (buffer-live-p src) (kill-buffer src))
      (when (buffer-live-p other) (kill-buffer other))
      (delete-other-windows)
      (delete-file file))))

(ert-deftest provenance-visit-changes-nothing-when-the-production-is-not-found-test ()
  "A failed RET selects no window and pushes no jump: the user stays in the view,
and the MRU window keeps whatever it showed (a failed jump used to leave a dired
buffer there, whose missing session then broke the next jump)."
  (let* ((prod (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                           :source (test--prov :var? nil)))
         (ft (test--prov :kind :fact-type :type "T" :type-short "T"
                         :children (vector prod)))
         (events '())
         (messages '()))
    (with-temp-buffer
      (clara-explorer--provenance-load ft :producer nil nil)
      (test--with-repl-lookup (lambda (&rest _) nil) (lambda (&rest _) "")
        (cl-letf (((symbol-function 'tabulated-list-get-id)
                   (lambda (&optional _) (vector "T" "ns/p")))
                  ((symbol-function 'clara-explorer--push-jump)
                   (lambda () (push 'push-jump events)))
                  ((symbol-function 'select-window)
                   (lambda (window &rest _) (push (list 'select window) events) window))
                  ((symbol-function 'pop-to-buffer)
                   (lambda (buffer &rest _) (push (list 'display buffer) events) buffer)))
          (clara-explorer-provenance-visit)))
      (should-not events)
      (should (cl-find-if (lambda (m) (string-match-p "classpath" m)) messages)))))

(ert-deftest provenance-visit-pushes-the-jump-before-changing-windows-test ()
  "C-o returns to the provenance view, not to the window the jump ran in."
  (let* ((prod (test--prov :kind :production :name "ns/p" :ns "ns" :type "rule"
                           :source (test--prov :var? nil)))
         (ft (test--prov :kind :fact-type :type "T" :type-short "T"
                         :children (vector prod)))
         (events '()))
    (with-temp-buffer
      (clara-explorer--provenance-load ft :producer nil nil)
      (cl-letf (((symbol-function 'tabulated-list-get-id)
                 (lambda (&optional _) (vector "T" "ns/p")))
                ((symbol-function 'clara-explorer--locate-production)
                 (lambda (&rest _) (list :buffer 'source-buffer)))
                ((symbol-function 'clara-explorer--navigation-window)
                 (lambda (buffer) (list 'window-for buffer)))
                ((symbol-function 'clara-explorer--push-jump)
                 (lambda () (push 'push-jump events)))
                ((symbol-function 'select-window)
                 (lambda (window &rest _) (push (list 'select window) events) window))
                ((symbol-function 'clara-explorer--show-production)
                 (lambda (location &rest _)
                   (push (list 'show (plist-get location :buffer)) events)
                   t)))
        (clara-explorer-provenance-visit))
      (should (equal (reverse events)
                     '(push-jump
                       (select (window-for source-buffer))
                       (show source-buffer)))))))

(ert-deftest provenance-goto-fallback-finds-the-form-test ()
  "With nothing loaded, NS's classpath path is visited and searched."
  (let* ((file (expand-file-name "provenance-goto-fallback.clj" test--scratch-dir))
         (messages '())
         (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                           :type "rule" :source (test--prov :var? nil))))
    (test--write-demo-ns file)
    (unwind-protect
        (with-temp-buffer
          (test--with-repl-lookup (lambda (&rest _) nil) (lambda (&rest _) file)
            (cl-letf (((symbol-function 'cider-get-ns-name) (test--demo-ns-name-stub file)))
              (should (clara-explorer--goto node))
              (should (equal (buffer-file-name) file))
              ;; the fallback regexp consumes the symbol's boundary char, so search back
              (should (save-excursion
                        (re-search-backward "rule-a" (line-beginning-position) t))))))
      (when-let* ((visited (find-buffer-visiting file))) (kill-buffer visited))
      (delete-file file))))

(ert-deftest provenance-visit-jumps-to-a-loaded-var-off-the-classpath-test ()
  "The reported bug: a namespace loaded from a linked repo outside the classpath.
`cider/ns-path' answers \"\", but the loaded var's metadata names the file and
line, so RET lands on the rule.  Every REPL request runs in the REPL the view
was opened from — not in the buffer the target window shows, which may have no
linked session — and the opened file is pinned to that REPL like a CIDER jump."
  (let* ((file (expand-file-name "provenance-loaded-var.clj" test--scratch-dir))
         (repl (get-buffer-create " *clara-fake-repl*"))
         (unlinked (get-buffer-create " *clara-unlinked*"))
         (prod (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                           :type "rule" :source (test--prov :var? nil)))
         (ft (test--prov :kind :fact-type :type "T" :type-short "T"
                         :children (vector prod)))
         (request-buffers '())
         (pinned nil)
         (messages '()))
    (test--write-demo-ns file)
    (unwind-protect
        (progn
          (delete-other-windows)
          (set-window-buffer (selected-window) unlinked)
          (with-temp-buffer
            (clara-explorer--provenance-load ft :producer (list :conn repl) nil)
            (test--with-repl-lookup
                (lambda (name &rest _)
                  (push (current-buffer) request-buffers)
                  (and (equal name "demo.ns/rule-a")
                       (list (quote dict) "file" file "line" 3)))
                (lambda (&rest _) (push (current-buffer) request-buffers) "")
              (cl-letf (((symbol-function 'tabulated-list-get-id)
                         (lambda (&optional _) (vector "T" "demo.ns/rule-a")))
                        ((symbol-function 'get-mru-window)
                         (lambda (&rest _) (get-buffer-window unlinked)))
                        ((symbol-function 'cider-get-ns-name)
                         (test--demo-ns-name-stub file))
                        ((symbol-function 'cider--pin-repl-if-out-of-project)
                         (lambda (r) (setq pinned (list r (buffer-file-name))))))
                (clara-explorer-provenance-visit))))
          (should (equal request-buffers (list repl)))
          (should (equal (buffer-file-name (window-buffer (selected-window))) file))
          (with-current-buffer (window-buffer (selected-window))
            (should (= 3 (line-number-at-pos)))
            (should (looking-at-p "(r/defrule rule-a")))
          (should (equal pinned (list repl file))))
      (when-let* ((visited (find-buffer-visiting file))) (kill-buffer visited))
      (kill-buffer repl)
      (kill-buffer unlinked)
      (delete-other-windows)
      (delete-file file))))

(ert-deftest provenance-goto-searches-when-the-reported-line-drifted-test ()
  "An edited buffer can drift from the loaded var's line: the form is searched."
  (let* ((file (expand-file-name "provenance-drifted-line.clj" test--scratch-dir))
         (messages '())
         (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                           :type "rule" :source (test--prov :var? nil))))
    (test--write-demo-ns file)
    (unwind-protect
        (with-temp-buffer
          (test--with-repl-lookup
              (lambda (&rest _) (list (quote dict) "file" file "line" 1))
              (lambda (&rest _) "")
            (cl-letf (((symbol-function 'cider-get-ns-name) (test--demo-ns-name-stub file)))
              (should (clara-explorer--goto node))
              (should (= 3 (line-number-at-pos))))))
      (when-let* ((visited (find-buffer-visiting file))) (kill-buffer visited))
      (delete-file file))))

(ert-deftest provenance-goto-uses-an-open-source-buffer-when-the-repl-fails-test ()
  "A source file the user has open is reachable without a working session."
  (let* ((file (expand-file-name "provenance-open-buffer.clj" test--scratch-dir))
         (messages '())
         (node (test--prov :kind :production :name "demo.ns/rule-a" :ns "demo.ns"
                           :type "rule" :source (test--prov :var? nil)))
         (src nil))
    (test--write-demo-ns file)
    (unwind-protect
        (progn
          (setq src (find-file-noselect file))
          (with-temp-buffer
            (test--with-repl-lookup
                (lambda (&rest _) (user-error "No linked CIDER sessions"))
                (lambda (&rest _) "")
              (cl-letf (((symbol-function 'cider-get-ns-name)
                         (test--demo-ns-name-stub file)))
                (should (clara-explorer--goto node))
                (should (eq (current-buffer) src))
                (should (= 3 (line-number-at-pos)))))))
      (when (buffer-live-p src) (kill-buffer src))
      (delete-file file))))

(ert-deftest provenance-mode-uses-the-standard-keys-test ()
  (test--group "g and q come from the parent mode, not from us"
    (with-temp-buffer
      (clara-explorer-provenance-mode)
      (should (eq revert-buffer-function #'clara-explorer-provenance-refresh))
      (should (eq (lookup-key clara-explorer-provenance-mode-map (kbd "g"))
                  #'revert-buffer))
      (should (eq (lookup-key clara-explorer-provenance-mode-map (kbd "q"))
                  #'quit-window))
      (test--group "the view's own keys are bound in the mode map"
        (should (eq (lookup-key clara-explorer-provenance-mode-map (kbd "TAB"))
                    #'clara-explorer-provenance-toggle))
        (should (eq (lookup-key clara-explorer-provenance-mode-map (kbd "RET"))
                    #'clara-explorer-provenance-visit))))))

(ert-deftest provenance-revert-reruns-the-root-call-test ()
  (let* ((root (test--prov :kind :fact-type :type "T" :type-short "T" :children []))
         (new-root (test--prov :kind :fact-type :type "T2" :type-short "T2"
                               :children []))
         (calls 0))
    (with-temp-buffer
      (clara-explorer-provenance-mode)
      (setq tabulated-list-entries
            (clara-explorer--provenance-load root :producer 'query
                                             (list :direction :producer)))
      (tabulated-list-print t)
      (cl-letf (((symbol-function 'clara-explorer--provenance-request)
                 (lambda (_q _input)
                   (setq calls (1+ calls))
                   new-root)))
        (revert-buffer t t))
      (should (= 1 calls))
      (should (equal (test--prov-labels tabulated-list-entries) '("T2"))))))

(provide 'clara-explorer-test)
;;; clara-explorer-test.el ends here
