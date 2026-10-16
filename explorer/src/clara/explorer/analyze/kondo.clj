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
        (if (Character/isLetter ch)
          (loop [k j]
            (if (and (< k n) (Character/isLetter (.charAt s k)))
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

(defn- scan-brackets
  "Scans `^String s`, returning `{:stack […] :past-depth2-close? bool}`. `:stack` is the
  open-bracket stack (outermost first) at the end of the scan; `:past-depth2-close?` is true when a
  `[` at depth 2 closed during the scan — for a single-arity fn-family macro that is the params
  vector closing, which marks the header/body boundary. A multi-arity arity list is a `(` at depth 2
  and does not set it. Strings, character literals, regex literals, and line comments are skipped so
  their brackets do not count. `#_`-discarded forms need no special case: a discarded form is one
  balanced form, so its brackets contribute net-zero."
  [^String s]
  (let [n (.length s)]
    (loop [i 0 stack [] past-depth2-close? false]
      (if (>= i n)
        {:stack stack :past-depth2-close? past-depth2-close?}
        (let [ch (.charAt s i)]
          (cond
            (= ch \") (recur (skip-string s i) stack past-depth2-close?)
            (= ch \\) (recur (skip-char-literal s i) stack past-depth2-close?)
            (= ch \;) (recur (skip-line-comment s i) stack past-depth2-close?)
            (and (= ch \#)
                 (< (inc i) n)
                 (= \" (.charAt s (inc i))))
            (recur (skip-string s (inc i)) stack past-depth2-close?)
            (or (= ch \() (= ch \[) (= ch \{))
            (recur (inc i) (conj stack ch) past-depth2-close?)
            (or (= ch \)) (= ch \]) (= ch \}))
            (recur (inc i)
                   (if (seq stack) (pop stack) stack)
                   (or past-depth2-close?
                       (and (= ch \]) (= 2 (count stack)))))
            :else (recur (inc i) stack past-depth2-close?)))))))

(defn ->bracket-scan
  "The `scan-brackets` result for the source prefix between a macro usage's start and a binding
  position within it, measured just before the binding position. Nil when the source cannot be read.

   `usage` and `binding` are kondo position maps (`:row`/`:col` 1-indexed, `:filename`)."
  [get-lines
   ns-sym
   {:keys [row col filename] :as _usage}
   {brow :row bcol :col :as _binding}]
  (when-let [lines (get-lines ns-sym filename)]
    (when-let [prefix (source-text-at lines row col brow bcol)]
      (scan-brackets prefix))))
