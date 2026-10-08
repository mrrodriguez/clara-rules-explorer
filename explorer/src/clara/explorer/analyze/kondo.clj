(ns clara.explorer.analyze.kondo
  "Reading source forms at clj-kondo positions.

   All Clojure syntax understanding in the analyze pipeline comes from
   clj-kondo; this namespace only reads single forms at kondo-provided
   positions (row/col spans in a source string).  Nothing here interprets
   what a form means."
  (:require [clojure.string :as str]))

(defn- source-text-at
  "Extracts source text from a position range.  Returns nil on any error."
  [lines-vec row col end-row end-col]
  (try
    (when (and row col end-row end-col
               (<= 1 row (count lines-vec))
               (<= 1 end-row (count lines-vec))
               (<= row end-row))
      (let [relevant-lines (subvec (vec lines-vec) (dec row) end-row)]
        (if (= (count relevant-lines) 1)
          (let [line (first relevant-lines)]
            (when (and (<= 0 (dec col) (count line))
                       (<= 0 (dec end-col) (count line))
                       (<= col end-col))
              (subs line (dec col) (dec end-col))))
          (let [first-line (first relevant-lines)
                last-line (last relevant-lines)
                middle-lines (subvec relevant-lines 1 (dec (count relevant-lines)))
                trimmed-first (if (<= 0 (dec col) (count first-line))
                                (subs first-line (dec col))
                                first-line)
                trimmed-last (if (<= 0 (dec end-col) (count last-line))
                               (subs last-line 0 (dec end-col))
                               last-line)]
            (str/join "\n" (concat [trimmed-first] middle-lines [trimmed-last]))))))
    (catch Exception _
      nil)))

(defn- read-string-in-ns
  "Reads a single form from source text `s` with `*ns*` bound to the live
   namespace named by `ns-sym`, so `::keyword` and `::alias/keyword` read in
   the callsite's own namespace (through its `:as` aliases) instead of the
   analysis thread's `*ns*`.

   Only `::` keywords resolve at read time; every other form reads the same
   whether or not `*ns*` is bound, so a missing namespace only matters for
   `::`.  When `ns-sym` names no loaded namespace and `s` contains `::`,
   returns nil (the caller treats the form as unresolved) rather than
   resolving the keyword against the wrong namespace."
  [ns-sym s]
  (let [the-ns (when ns-sym (find-ns ns-sym))]
    (if (and (nil? the-ns) (str/includes? s "::"))
      nil
      (binding [*ns* (or the-ns *ns*)]
        (try (read-string s) (catch Exception _ nil))))))

(defn source-text-for-span
  "The source text for a kondo usage/binding span
   (`{:row … :col … :end-row … :end-col … :filename …}`), or nil when the
   span or its source cannot be resolved.  `get-lines` is
   `(fn [ns-sym filename] -> lines-vec)`; the ns-sym may be nil, in which case
   the source is looked up by filename."
  [get-lines ns-sym {:keys [row col end-row end-col filename]}]
  (when-let [lines (get-lines ns-sym filename)]
    (source-text-at lines row col end-row end-col)))

(defn read-boundary-args
  "Reads the argument forms of the boundary call (`insert!`/`retract!`/…) described
   by a kondo `:var-usage`.  Returns a (possibly empty) sequence of forms.

   A usage that is not a call (a value use like `(run! insert! xs)` or a bare
   threaded step like `(-> m f insert!)`) has no argument forms in its span —
   the text reads as the bare symbol — so there is nothing to return.  Only
   `seq?` forms (call lists) yield arguments; anything else degrades to nil
   like every other read failure in this namespace."
  [{:keys [row end-row col end-col from filename] :as _usage} get-lines]
  (let [lines (get-lines from filename)
        call-str (source-text-at lines
                                 row
                                 col
                                 end-row
                                 end-col)]
    (when call-str
      (let [form (read-string-in-ns from call-str)]
        (when (seq? form)
          (rest form))))))

(defn read-init-form
  "Reads the init form following a `:locals` binding symbol in the source —
   the text from just after the binding symbol's end position onward, parsed
   as a single form.  Returns nil on any error."
  [get-lines ns-sym {:keys [row end-col]}]
  (try
    (when-let [lines (get-lines ns-sym nil)]
      (when (and row end-col)
        (let [line (nth lines (dec row))
              tail (str/join "\n" (cons (subs line (dec end-col)) (drop row lines)))]
          (read-string-in-ns ns-sym tail))))
    (catch Throwable _
      nil)))

(defn init-form-start
  "The `[row col]` (1-indexed) where the init form of a `:locals` binding
   starts: the first readable character at/after the binding symbol's end
   position, skipping whitespace, commas, and `;` comments — mirroring how
   `read-init-form` finds the form.  Returns nil on any error."
  [get-lines ns-sym {:keys [row end-col]}]
  (try
    (when-let [lines (get-lines ns-sym nil)]
      (when (and row end-col)
        (loop [r row
               off (dec end-col)]
          (when (<= r (count lines))
            (let [line (nth lines (dec r))]
              (cond
                (>= off (count line))
                (recur (inc r) 0)

                (or (Character/isWhitespace ^char (nth line off))
                    (= \, (nth line off)))
                (recur r (inc off))

                (= \; (nth line off))
                (recur (inc r) 0)

                :else [r (inc off)]))))))
    (catch Throwable _
      nil)))

(defn- read-one-form-char-count
  "Reads a single form from the head of string `s`, returning the number of
   chars consumed (trailing whitespace/comments excluded). Nil when no
   complete form reads.  `ns-sym` is the namespace `::` keywords resolve
   against (see `read-string-in-ns`)."
  [ns-sym ^String s]
  (let [the-ns (when ns-sym (find-ns ns-sym))]
    (if (and (nil? the-ns) (str/includes? s "::"))
      nil
      (try
        (let [consumed (atom 0)
              rdr (proxy [java.io.PushbackReader] [(java.io.StringReader. s)]
                    (read
                      ([]
                       (let [c (proxy-super read)]
                         (when-not (= -1 c)
                           (swap! consumed inc))
                         c))
                      ([cbuf]
                       (let [^chars buf cbuf
                             n (proxy-super read buf)]
                         (when (pos? n)
                           (swap! consumed + n))
                         n))
                      ([cbuf off len]
                       (let [^chars buf cbuf
                             o (int off)
                             l (int len)
                             n (proxy-super read buf o l)]
                         (when (pos? n)
                           (swap! consumed + n))
                         n)))
                    (unread
                      ([c-or-buf]
                       (if (number? c-or-buf)
                         (do (proxy-super unread (int c-or-buf))
                             (swap! consumed dec))
                         (let [^chars buf c-or-buf]
                           (proxy-super unread buf)
                           (swap! consumed - (alength buf))))
                       nil)
                      ([cbuf off len]
                       (let [^chars buf cbuf
                             o (int off)
                             l (int len)]
                         (proxy-super unread buf o l)
                         (swap! consumed - l)
                         nil))))]
          (binding [*ns* (or the-ns *ns*)]
            (clojure.lang.LispReader/read rdr nil))
          @consumed)
        (catch Throwable
               _
          nil)))))

(defn- advance-pos-by-count
  "Advances a 1-indexed `[row col]` forward by `n` chars of string `s`."
  [[row col] ^String s n]
  (loop [r row c col i 0]
    (if (or (>= i n) (>= i (.length s)))
      [r c]
      (if (= \newline (.charAt s (int i)))
        (recur (inc r) 1 (inc i))
        (recur r (inc c) (inc i))))))

(defn init-form-span
  "The `{:filename … :start [row col] :end [row col]}` span (1-indexed,
   `:end` exclusive) of the init form following a `:locals` binding symbol.
   Nil when the span cannot be determined."
  [get-lines ns-sym {:keys [row end-col filename] :as _binding}]
  (when-let [start (init-form-start get-lines ns-sym {:row row :end-col end-col})]
    (try
      (when-let [lines (get-lines ns-sym nil)]
        (let [[sr sc] start
              line (nth lines (dec sr) nil)
              tail (when line
                     (str/join "\n" (cons (subs line (dec sc)) (drop sr lines))))]
          (when-let [n (and tail (read-one-form-char-count ns-sym tail))]
            {:filename filename
             :start start
             :end (advance-pos-by-count start tail n)})))
      (catch Throwable
             _
        nil))))

(defn read-ctor-form
  "The constructor call form as written, read from source at the usage's span."
  [ctor-usage get-lines]
  (let [lines (get-lines (:from ctor-usage) (:filename ctor-usage))]
    (when-let [call-str (source-text-at lines
                                        (:row ctor-usage) (:col ctor-usage)
                                        (:end-row ctor-usage) (:end-col ctor-usage))]
      (read-string-in-ns (:from ctor-usage) call-str))))

;; ---------------------------------------------------------------------------
;; Bracket-depth scanning (for binding classification)
;;
;; Classifying a kondo `:locals` binding (see
;; `clara.explorer.analyze.callsite`) needs to know how deeply the binding
;; symbol nests inside its enclosing binding form, without interpreting
;; destructuring semantics — kondo already reports which source positions are
;; bindings.  These helpers scan source text char-by-char, tracking `()` / `[]`
;; / `{}` depth while skipping the regions whose brackets must not count:
;; strings, character literals, `;` line comments, and `#_`-discarded forms.
;; ---------------------------------------------------------------------------

(defn- skip-string
  "Index after the string literal whose opening `\"` sits at index `i`."
  [^String s i]
  (let [n (.length s)]
    (loop [j (inc i)]
      (cond
        (>= j n) n
        (= \\ (.charAt s j)) (recur (+ j 2))
        (= \" (.charAt s j)) (inc j)
        :else (recur (inc j))))))

(defn- skip-char-literal
  "Index after the character literal whose leading `\\` sits at index `i`.
   Handles both single-char literals (`\\(`) and named/hex literals
   (`\\newline`, `\\u0041`)."
  [^String s i]
  (let [n (.length s)
        j (inc i)]
    (if (>= j n)
      n
      (let [ch (.charAt s j)]
        (if (Character/isLetter ^char ch)
          (loop [k j]
            (if (and (< k n) (Character/isLetter ^char (.charAt s k)))
              (recur (inc k))
              k))
          (inc j))))))

(defn- skip-line-comment
  "Index just past the `;` comment starting at index `i` (the newline, when
   present, is left for the caller to consume)."
  [^String s i]
  (let [n (.length s)]
    (loop [j i]
      (cond
        (>= j n) n
        (= \newline (.charAt s j)) j
        :else (recur (inc j))))))

;; `skip-delimited`, `skip-discard`, and `skip-form` are mutually recursive
;; (`#_` inside a collection skips a form, which may itself contain a
;; collection).
(declare skip-delimited skip-discard skip-form)

(defn- skip-delimited
  "Index after the `open`-delimited form starting at index `i`.  Only the
   given `open`/`close` delimiter pair is balanced; other delimiters are
   skipped as ordinary characters, which is correct for well-formed source."
  [^String s i open close]
  (let [n (.length s)]
    (loop [j (inc i) depth 1]
      (if (>= j n)
        n
        (let [ch (.charAt s j)
              nch (when (< (inc j) n) (.charAt s (inc j)))]
          (cond
            (= ch \") (recur (skip-string s j) depth)
            (= ch \\) (recur (skip-char-literal s j) depth)
            (= ch \;) (recur (skip-line-comment s j) depth)
            (and (= ch \#) (= nch \_)) (recur (skip-discard s j) depth)
            (= ch open) (recur (inc j) (inc depth))
            (= ch close) (if (= depth 1)
                           (inc j)
                           (recur (inc j) (dec depth)))
            :else (recur (inc j) depth)))))))

(defn- skip-discard
  "Index after a `#_` discarded form whose `#` sits at index `i`."
  [^String s i]
  (skip-form s (+ i 2)))

(defn- skip-form
  "Index after the form starting at index `i`.  Skips whitespace, strings,
   character literals, line comments, `#_` discards, and balanced
   `()`/`[]`/`{}` collections."
  [^String s i]
  (let [n (.length s)]
    (loop [j i]
      (if (>= j n)
        n
        (let [ch (.charAt s j)
              nch (when (< (inc j) n) (.charAt s (inc j)))]
          (cond
            (Character/isWhitespace ^char ch) (recur (inc j))
            (= ch \") (recur (skip-string s j))
            (= ch \\) (recur (skip-char-literal s j))
            (= ch \;) (recur (skip-line-comment s j))
            (and (= ch \#) (= nch \_)) (recur (skip-discard s j))
            (= ch \() (recur (skip-delimited s j \( \)))
            (= ch \[) (recur (skip-delimited s j \[ \]))
            (= ch \{) (recur (skip-delimited s j \{ \}))
            :else (recur (inc j))))))))

(defn- pos->offset
  "0-indexed char offset of the 1-indexed `[row col]` position within `s`.
   Positions past the end resolve to the length of `s`."
  [^String s row col]
  (let [n (.length s)]
    (loop [i 0 r 1 c 1]
      (cond
        (>= i n) n
        (and (= r row) (= c col)) i
        :else
        (let [ch (.charAt s i)]
          (if (= ch \newline)
            (recur (inc i) (inc r) 1)
            (recur (inc i) r (inc c))))))))

(defn bracket-depth-at
  "Bracket nesting depth at the 1-indexed `[row col]` position within source
   string `s`, measured just before the char at that position.  Every open
   `(`/`[`/`{` counts one level; strings, character literals, line comments,
   and `#_`-discarded forms are skipped so their brackets do not count."
  [^String s row col]
  (let [target (pos->offset s row col)]
    (loop [i 0 depth 0]
      (if (>= i target)
        depth
        (let [ch (.charAt s i)
              n (.length s)
              nch (when (< (inc i) n) (.charAt s (inc i)))]
          (cond
            (= ch \") (recur (skip-string s i) depth)
            (= ch \\) (recur (skip-char-literal s i) depth)
            (= ch \;) (recur (skip-line-comment s i) depth)
            (and (= ch \#) (= nch \_)) (recur (skip-discard s i) depth)
            (or (= ch \() (= ch \[) (= ch \{)) (recur (inc i) (inc depth))
            (or (= ch \)) (= ch \]) (= ch \})) (recur (inc i) (dec depth))
            :else (recur (inc i) depth)))))))
