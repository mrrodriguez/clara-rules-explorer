(ns clara.explorer.utils
  "Small utilities shared across the clara.explorer namespaces.  Depends on
   clara-rules and nothing else in this library, so every namespace here can
   require it without risking a cycle."
  (:require [clara.rules.engine :as eng]
            [clojure.walk :as walk]))

(defn get-rulebase
  "The rulebase of a session, or the rulebase itself when handed one directly.

   The either-or every entry point in this library accepts, collapsed into one
   definition: `->rulebase-analysis`,
   `clara.explorer.annotations.merge/->props-layer` and
   `->annotations-from-rule-source-analysis` all take a session *or* a rulebase,
   and a caller holding one should never have to disagree with them about which.

   Tests for `:productions` rather than for a session type, so a hand-built
   rulebase map is accepted and so is any `ISession` implementation."
  [session-or-rulebase]
  (if (:productions session-or-rulebase)
    session-or-rulebase
    (-> session-or-rulebase eng/components :rulebase)))

(defn remove-nil-vals
  "Returns the map `m` with all entries whose value is nil removed."
  [m]
  (->> m
       (reduce-kv (fn [m' k v]
                    (if (nil? v) (dissoc! m' k) m'))
                  (transient m))
       persistent!))

(defn sort-by-key
  "Sorts `coll` by `key-fn`, computing `key-fn` exactly once per element
   (decorate-sort-undecorate / Schwartzian transform).  `sort-by` recomputes
   its key-fn on every comparison — O(n log n) key computations — which is
   wasteful when the key-fn stringifies or walks large values; this computes
   it O(n) times instead.

   `key-fn` results must be mutually `compare`-able; ties preserve input
   order (stable), matching `sort-by`."
  [key-fn coll]
  (->> coll
       (map (fn [x] [(key-fn x) x]))
       (sort-by first)
       (map second)))

(def ^:private digest-widths
  "Hex-digest suffix widths (characters) recognized as a minted-name suffix,
   longest first. Only md5's 32 today; adding sha1 (40) or sha256 (64) is a
   one-line change here. Longest-first matters: a 64-hex suffix also ends in
   32 hex characters, so the widest width must win."
  [32])

(def ^:private digest-patterns
  "`digest-widths` as `{:width … :pattern … :ordinal-fmt …}` entries, in order. Each pattern
   captures the prefix of `<prefix><width lowercase hex>`; the greedy `.+`
   anchors the hex run at the name's end, so a prefix ending in a hex letter
   keeps its last letter. A bare digest (no prefix) cannot match — `.+`
   needs at least one character — and neither can uppercase hex."
  (mapv (fn [width]
          {:width width
           :pattern (re-pattern (format "(.+)[0-9a-f]{%d}" width))
           :ordinal-fmt (format "%%0%dx" width)})
        digest-widths))

(defn- minted-name
  "The canonical minted name for a symbol's `name` at the given 0-based
   `ordinal`, or nil when `name` is not one of the minted shapes: the four
   reader gensym shapes (`#(…)` positional args (`p1__NNN#`), `#(…)` rest arg
   (`rest__NNN#`), syntax-quote auto-gensyms (`x__NNN__auto__`), and
   `(gensym)` defaults (`G__NNN`)) plus digest-suffixed locals
   (`<prefix><hex digest>`, e.g. a macro minting `monthly-income` + the md5
   of its own input). Reader shapes are tried first, so a name that matched
   before (e.g. `G__` + 32 hex digits, all digits) keeps its old canonical
   form."
  [name ordinal]
  (or
   (when-let [[_ pos] (re-matches #"p(\d+)__\d+#" name)]
     (str "p" pos "__" ordinal "#"))
   (when (re-matches #"rest__\d+#" name)
     (str "rest__" ordinal "#"))
   (when-let [[_ prefix] (re-matches #"(.+)__\d+__auto__" name)]
     (str prefix "__" ordinal "__auto__"))
   (when (re-matches #"G__\d+" name)
     (str "G__" ordinal))
   (some (fn [{:keys [pattern ordinal-fmt]}]
           (when-let [[_ prefix] (re-matches pattern name)]
             (str prefix (format ordinal-fmt ordinal))))
         digest-patterns)))

(defn canonicalize-minted-names
  "Returns `form` with every minted name renamed to a canonical name derived
   from its order of first appearance within `form`, so persisted text depends
   on the form's own content rather than process state (the JVM-wide reader
   counter, or a macro's digest of printed input that embeds it).

   Covers the reader gensym shapes and the digest-suffix shape (see `minted-name`):
   the `#(…)` positional index is preserved (`p1__NNN#` -> `p1__<ord>#`), and one
   ordinal counter is shared across all shapes so distinct gensyms never map
   to the same name.  Each canonical name still matches its shape's pattern,
   so it cannot collide with a symbol the walk leaves alone; a digest-suffixed
   canonical name (`<prefix>` + zero-padded hex ordinal) still ends in hex of
   the same width, so a second pass assigns the same ordinals (idempotent). Symbol
   metadata is preserved. A form with no minted names is returned unchanged
   (identical, not merely equal)."
  [form]
  (let [renames (volatile! {})
        counter (volatile! 0)
        walked (walk/postwalk
                (fn [x]
                  (if (and (symbol? x) (nil? (namespace x)))
                    (let [n (name x)]
                      (if-let [canonical (get @renames n)]
                        (-> canonical symbol (with-meta (meta x)))
                        (if-let [canonical (minted-name n @counter)]
                          (do (vswap! renames assoc n canonical)
                              (vswap! counter inc)
                              (-> canonical symbol (with-meta (meta x))))
                          x)))
                    x))
                form)]
    (if (pos? @counter)
      walked
      form)))
