(ns clara.server.tools.graph.edn-io
  "Reading and writing EDN files, in the shape `clara.server.tools.graph.artifacts` persists them.

  Generic — nothing here knows about annotations. Two opinions it carries.

  `fipp` does the printing, through `minimal-indent-printer`, and
  `write-edn-file!` streams straight at the file rather than building a string
  first. The derived artifacts run to tens of megabytes, which makes the printer
  the most expensive thing a persist does and the whole text an expensive thing
  to hold alongside the value it came from.

  `*print-meta*` is off. The values being written are full of symbols carrying
  clj-kondo location metadata that describes a position inside a *synthesized*
  source snippet. Printing that bloats the file and churns the diff without ever
  being read back."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [fipp.edn :as fipp]
   [fipp.ednize :as ednize]
   [fipp.engine :as engine]
   [fipp.visit :as visit])
  (:import
   (java.io File PushbackReader Writer)))

(set! *warn-on-reflection* true)

(def print-width
  "Column budget a collection is laid out against before it breaks across lines.
  Wide enough that a small map — a callsite, a type reference — stays on one
  line."
  100)

;; ===========================================================================
;; the printer
;;
;; fipp lays a broken collection out under `[:align]`, which indents every entry
;; to the column where the first one started. Under a long map key that column is
;; deep, and a rule keyed by a fully-qualified production name is exactly that:
;; over these artifacts it averages ~100 columns of leading space per line and
;; doubles the file, all of it whitespace.
;;
;; `[:nest 1]` instead indents one column past the opening brace, wherever the
;; collection happens to start. Same layout decisions, same line breaks, and the
;; files come out the size the data is.
;; ===========================================================================

(defn- minimal-indent-coll
  "fipp's `pretty-coll` with `[:nest 1]` in place of `[:align]`. `f` renders one
  element against `printer`; `sep` goes between them."
  [printer open xs sep close f]
  [:group open [:nest 1 (interpose sep (map #(f printer %) xs))] close])

(defrecord MinimalIndentEdnPrinter [inner]
  ;; Collections are this record's own, so a nested one keeps the indent rule;
  ;; scalars have no layout to decide and go to `inner`, an `EdnPrinter`.
  visit/IVisitor
  (visit-unknown   [this x]  (visit/visit this (ednize/edn x)))
  (visit-nil       [_]       (visit/visit-nil inner))
  (visit-boolean   [_ x]     (visit/visit-boolean inner x))
  (visit-string    [_ x]     (visit/visit-string inner x))
  (visit-character [_ x]     (visit/visit-character inner x))
  (visit-symbol    [_ x]     (visit/visit-symbol inner x))
  (visit-keyword   [_ x]     (visit/visit-keyword inner x))
  (visit-number    [_ x]     (visit/visit-number inner x))
  (visit-var       [_ x]     (visit/visit-var inner x))
  (visit-pattern   [_ x]     (visit/visit-pattern inner x))
  (visit-seq       [this x]  (minimal-indent-coll this "(" x :line ")" visit/visit))
  (visit-vector    [this x]  (minimal-indent-coll this "[" x :line "]" visit/visit))
  (visit-set       [this x]  (minimal-indent-coll this "#{" x :line "}" visit/visit))
  (visit-map       [this x]  (minimal-indent-coll this "{" x [:span "," :line] "}"
                                                  (fn [printer [k v]]
                                                    [:span (visit/visit printer k) " "
                                                     (visit/visit printer v)])))
  (visit-tagged    [this {:keys [tag form]}]
    [:group "#" (str tag) (when-not (coll? form) " ") (visit/visit this form)])
  ;; `print-edn!` binds `*print-meta*` false, so metadata never reaches here.
  (visit-meta      [this _ x] (visit/visit* this x))
  (visit-record    [this x]  (visit/visit this (ednize/record->tagged x))))

(def minimal-indent-printer
  "The fipp visitor everything here prints through: fipp's own `EdnPrinter` for
  scalars, `[:nest 1]` for collections. Stateless, so one instance serves every
  call."
  (->MinimalIndentEdnPrinter (fipp/map->EdnPrinter {:symbols {}})))

(defn- print-edn!
  "Pretty-print `data` to `*out*` as EDN."
  [data]
  (binding [*print-meta* false]
    (engine/pprint-document (visit/visit minimal-indent-printer data)
                            {:width print-width})))

(defn print-edn-to!
  "Print `data` to `writer` as pretty EDN.

  Argument order is `[value writer]` because that is the `:edn-printer` contract of
  `clara.server.tools.graph.annotations.merge/write-layer!` — handing it this puts the layer files
  through the same printer, and the same layout, as every artifact `write-edn-file!` writes. Does
  not close or flush `writer`; the caller owns it."
  [data ^Writer writer]
  (binding [*out* writer]
    (print-edn! data)))

(defn pretty-edn-str
  "`data` as pretty-printed EDN text, with metadata suppressed (see the ns
  docstring for why). For anything file-sized prefer `write-edn-file!`, which
  never materializes the text.

  Also the `(fn [form] String)` shape `clara.server.tools.graph.core/->rulebase-analysis` takes as
  its `:form-printer`, which is what keeps its rendered LHS/RHS strings off `clojure.pprint`."
  [data]
  (with-out-str (print-edn! data)))

(defn write-edn-file!
  "Write `data` to `file` as pretty EDN. Does *not* create parent dirs — a caller
  that may be writing into a fresh dir says so with `io/make-parents`."
  [file data]
  (with-open [w (io/writer file)]
    (print-edn-to! data w)))

(defn read-edn-file
  "Read one EDN file, or nil when it is absent. Absence is a normal answer here:
  every artifact `clara.server.tools.graph.artifacts.store` reads is optional at least once in its
  lifecycle."
  [^File file]
  (when (.exists file)
    (with-open [r (PushbackReader. (io/reader file))]
      (edn/read r))))
