(ns clara.explorer.test.rules.helpers
  (:require [clara.rules :as r])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(defn- md5-hex
  "Lowercase 32-character md5 hex of `s`, left-padded with zeros."
  ^String [^String s]
  (let [digest (MessageDigest/getInstance "MD5")
        bytes (.digest digest (.getBytes s "UTF-8"))]
    (format "%032x" (BigInteger. 1 bytes))))

(defmacro def-digest-fact
  "Like `def-fact-fn`, but the rule's local is minted by appending the md5 of
   the macro's printed input to `name-sym`. `body` must include a `#(…)` (as
   below), whose printed reader gensyms carry the compiling process's
   JVM-wide counter — so the digest differs from one process to the next
   with no source change, and only
   `clara.explorer.utils/canonicalize-minted-names` keeps the persisted `:rhs-form` and
   the callsite `:source-str` stable. Exists to pin that canonicalization
   in the checked-in example."
  [name-sym fact-type & body]
  (let [local (symbol (str name-sym (md5-hex (pr-str body))))
        rule-name (symbol (format "%s-rule" name-sym))
        name-sym-with-meta (with-meta name-sym {:type fact-type})]
    `(do
       (defn ~name-sym-with-meta ~@body)
       (r/defrule ~rule-name
         ~'=>
         (let [~local (var ~name-sym-with-meta)]
           (r/insert! ~local))))))

(defmacro def-fact-fn
  [name-sym fact-type & body]
  (let [rule-name (symbol (format "%s-rule" name-sym))
        name-sym-with-meta (with-meta name-sym {:type fact-type})]
    `(do
       (defn ~name-sym-with-meta ~@body)
       (r/defrule ~rule-name
         ~'=>
         (let [resolved# (var ~name-sym-with-meta)]
           (r/insert! resolved#))))))

(defn ->fact
  [fact-type fact-data]
  (with-meta fact-data {:type fact-type}))
