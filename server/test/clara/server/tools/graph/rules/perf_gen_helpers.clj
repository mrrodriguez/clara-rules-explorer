(ns clara.server.tools.graph.rules.perf-gen-helpers
  "Helpers for generating large, linear rule chains for performance testing.

   Each generated rule in a chain of length N consumes a fact produced by the previous rule and
  produces a fact consumed by the next, forming a linear dependency graph.

   Facts are keywords (`:chain/step-1`, `:chain/step-2`, …) and the RHS of each rule calls a custom
  fact constructor (`->step-fact`) — a deliberate dynamic call site — to simulate environments where
  static analysis cannot trivially resolve produced fact types.

  See `comment` blocks below for usage."
  (:require
   #_{:clj-kondo/ignore [:unused-namespace]}
   [clara.rules :as r]
   [clara.rules.compiler :as com]))

;; ---------------------------------------------------------------------------
;; Custom fact constructor
;; ---------------------------------------------------------------------------

(def ^:private chain-parent
  "Common parent keyword for all chain step facts."
  :chain/step)

(defn- step-kw
  "Canonical step keyword for index i (1-based)."
  [i]
  (keyword "chain" (str "step-" i)))

(defn ->step-fact
  "Custom fact constructor for chain rules.

   Returns a map fact with `:type` metadata set to the given keyword.
   This function serves as a deliberate dynamic call site — static
   analysis of individual rule RHS bodies sees an opaque function
   call rather than an inline constructor, enabling testing of
   dynamic fact type resolution paths."
  [step-kw]
  {:pre [(keyword? step-kw)]}
  (with-meta {:step step-kw} {:type step-kw}))

;; ---------------------------------------------------------------------------
;; Rule generation
;; ---------------------------------------------------------------------------

(defn build-chain-rules
  "Generate N production maps forming a linear chain.

   Each rule i (0 ≤ i < n):
   - LHS: matches on fact type :chain/step-i (rule 0 matches :chain/seed)
   - RHS: inserts (->step-fact :chain/step-(i+1))

   Returns a vector of production maps suitable for clara.rules/mk-session."
  [n]
  {:pre [(pos-int? n)]}
  (mapv (fn [i]
          (let [step-key (step-kw i)
                next-step-key (step-kw (inc i))
                consume-type (if (zero? i) :chain/seed step-key)]
            {:name (str "chain-rule-" i)
             :doc (str "Chain rule " i ": " (pr-str consume-type) " → " (pr-str next-step-key))
             :ns-name 'clara.server.tools.graph.rules.perf-gen-helpers
             :lhs [{:type consume-type
                    :constraints []}]
             :rhs (list 'r/insert! (list '->step-fact next-step-key))}))
        (range n)))

;; -------------------------------------------------------------------
;; Hierarchy
;; -------------------------------------------------------------------

(defn build-chain-hierarchy
  "Build a Clojure hierarchy where each chain step keyword
   (`:chain/step-1` … `:chain/step-n`) derives from `:chain/step`.

   Returns a hierarchy map suitable as a source for mk-session
   (it carries :parents, :ancestors and :descendants keys)."
  [n]
  {:pre [(pos-int? n)]}
  (reduce (fn [h i]
            (derive h (step-kw i) chain-parent))
          (make-hierarchy)
          (range 1 (inc n))))

;; -------------------------------------------------------------------
;; Query
;; -------------------------------------------------------------------

(defn build-chain-query
  "Build a query production map that matches all chain step facts
   via the common `:chain/step` parent type.

   Call the query with no arguments to retrieve all step facts
   that have been inserted:

     (r/query session chain-all-steps)"
  []
  {:name "chain-all-steps"
   :doc "Query matching all chain step facts via the common :chain/step parent."
   :ns-name 'clara.server.tools.graph.rules.perf-gen-helpers
   :lhs [{:type chain-parent
          :constraints [(list '= '?step 'this)]}]
   :params #{}})

;; -------------------------------------------------------------------
;; Session
;; -------------------------------------------------------------------

(defn build-chain-session
  "Build a Clara session from N chain rules, a step-type hierarchy,
   and a query over the common parent type.

   Convenience wrapper around build-chain-rules + build-chain-query +
   build-chain-hierarchy + mk-session.  Accepts the same keyword
   options as clara.rules/mk-session (e.g. :cache false)."
  [n & options]
  (com/mk-session (concat (build-chain-rules n)
                          [(build-chain-query)
                           (build-chain-hierarchy n)]
                          options)))

(comment
  ;; Build a session with a 1000-rule chain:
  (let [session (build-chain-session 1000)
        fired (-> session
                  (r/insert (with-meta {:seed true} {:type :chain/seed}))
                  (r/fire-rules))]
    {:session fired
     :query-result (r/query fired build-chain-query)})

;; Or get the production maps for manual session construction:
  (r/mk-session (build-chain-rules 1000)))
