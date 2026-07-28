(ns clara.server.tools.graph.analyze.kondo
  "Reading source forms at clj-kondo positions.

   All Clojure syntax understanding in the analyze pipeline comes from
   clj-kondo; this namespace only reads single forms at kondo-provided
   positions (row/col spans in a source string).  Nothing here interprets
   what a form means."
  (:require [clojure.string :as str]))

(defn- source-text-at
  "Extracts source text from a position range.  Returns nil on any error."
  [source-str row col end-row end-col]
  (try
    (let [lines (str/split-lines source-str)]
      (when (and row col end-row end-col
                 (<= 1 row (count lines))
                 (<= 1 end-row (count lines))
                 (<= row end-row))
        (let [relevant-lines (subvec (vec lines) (dec row) end-row)]
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
              (str/join "\n" (concat [trimmed-first] middle-lines [trimmed-last])))))))
    (catch Exception _
      nil)))

(defn read-boundary-args
  "Reads the argument forms of the boundary call (`insert!`/`retract!`/…) described
   by a kondo `:var-usage`.  Returns a (possibly empty) sequence of forms."
  [{:keys [row end-row col end-col from filename] :as _usage} get-source]
  (let [source (get-source from filename)
        call-str (source-text-at source
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
  [source {:keys [row end-col]}]
  (try
    (when (and source row end-col)
      (let [lines (str/split-lines source)
            line (nth lines (dec row))
            tail (str/join "\n" (cons (subs line (dec end-col)) (drop row lines)))]
        (read-string tail)))
    (catch Throwable _
      nil)))

(defn read-ctor-form
  "The constructor call form as written, read from source at the usage's span.
   Public: the index memoizes it per run (shared ctor-usages are re-read once
   per rule otherwise)."
  [ctor-usage get-source]
  (let [source (get-source (:from ctor-usage) (:filename ctor-usage))]
    (when-let [call-str (source-text-at source
                                        (:row ctor-usage) (:col ctor-usage)
                                        (:end-row ctor-usage) (:end-col ctor-usage))]
      (try (read-string call-str) (catch Exception _ nil)))))
