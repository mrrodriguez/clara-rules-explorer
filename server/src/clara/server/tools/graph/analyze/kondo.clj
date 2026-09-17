(ns clara.server.tools.graph.analyze.kondo
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

(defn read-boundary-args
  "Reads the argument forms of the boundary call (`insert!`/`retract!`/…) described
   by a kondo `:var-usage`.  Returns a (possibly empty) sequence of forms."
  [{:keys [row end-row col end-col from filename] :as _usage} get-lines]
  (let [lines (get-lines from filename)
        call-str (source-text-at lines
                                 row
                                 col
                                 end-row
                                 end-col)]
    (if call-str
      (try
        (rest (read-string call-str))
        (catch Exception _ nil))
      nil)))

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
          (read-string tail))))
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
   complete form reads."
  [^String s]
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
      (clojure.lang.LispReader/read rdr nil)
      @consumed)
    (catch Throwable
           _
      nil)))

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
          (when-let [n (and tail (read-one-form-char-count tail))]
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
      (try (read-string call-str) (catch Exception _ nil)))))
