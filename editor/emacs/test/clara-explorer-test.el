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
    ;; negative: wrong head or wrong name
    (should-not-match "(def my-rule [A] => 1)" "my-rule")
    (should-not-match "(defrule other-rule [A] => 1)" "my-rule")
    ;; ^{:map} contains space, so primary truncates - goto-fallback then uses \\b fallback
    (should-not-match "(defrule ^{:doc \"hi\"} my-rule [A] => 1)" "my-rule")
    (should (string-match-p "\\bmy-rule\\b" "(defrule ^{:doc \"hi\"} my-rule [A] => 1)"))))

(provide 'clara-explorer-test)
;;; clara-explorer-test.el ends here
