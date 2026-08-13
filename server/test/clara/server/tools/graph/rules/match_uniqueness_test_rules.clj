(ns clara.server.tools.graph.rules.match-uniqueness-test-rules
  "Working-memory match-uniqueness fixtures.

   Case A — one fact, one activation, two conditions: a tagged :Item satisfies
   both accumulator conditions of `overlapping-conditions`, producing duplicate
   (fact, bindings) pairs that must collapse to one row with one binding set.

   Case B — one fact, many activations: `pairwise` joins one Config with each
   Item, so the config appears once per item with a distinct binding set.

   Combined — `combined` has two accumulators over Config (duplicate pairs per
   activation) plus an Item condition (multiple activations), so the Config
   fact is both duplicated within each activation and present across
   activations.

   Every rule inserts a marker (a fact no rule consumes) so its activation
   tokens are recorded in `rule-matches`; `rule-matches` is keyed off the
   engine's production insertions, and a side-effect-only RHS records nothing."
  (:require [clara.rules :as r]
            [clara.rules.accumulators :as acc]))

(defrecord Config [name])
(defrecord Item [tag])
(defrecord Marker [id])

(r/defrule overlapping-conditions
  [?tagged <- (acc/all) :from [Item (some? tag)]]
  [?all    <- (acc/all) :from [Item]]
  =>
  (r/insert! (->Marker :overlapping-conditions)))

(r/defrule pairwise
  [?config <- Config]
  [?item   <- Item]
  =>
  (r/insert! (->Marker :pairwise)))

(r/defrule combined
  [?configs-1 <- (acc/all) :from [Config]]
  [?configs-2 <- (acc/all) :from [Config]]
  [?item      <- Item]
  =>
  (r/insert! (->Marker :combined)))

(r/defquery find-pairs
  "Query mirroring `pairwise` — the Config fact appears once per Item token."
  []
  [?config <- Config]
  [?item   <- Item])
