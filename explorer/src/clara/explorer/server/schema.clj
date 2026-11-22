(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.server.schema
  "The shapes the editor-navigation namespaces hand each other, in one place.

  The navigate contract — input, targets, results, and the runtime capabilities map — travels
  between `clara.explorer.server.navigate`, its JVM shell (`clara.explorer.server.client`), and the
  babashka client, far enough from where each piece is built that prose in whichever docstring
  happened to receive it would drift silently."
  (:require [schema.core :as s]))

(s/defschema NavigateInput
  {(s/optional-key :production) (s/maybe s/Str)   ; fq "ns/rule"; nil = global path
   (s/optional-key :side)       (s/enum :lhs :rhs)
   (s/optional-key :caller-ns)  s/Str             ; buffer ns, for global path + ctor resolution
   :token                       s/Str})

(s/defschema SourceLoc
  "A production source location. `:var?` true carries var metadata
   `:file`/`:line`/`:column`; `:var?` false has none of them, so those three
   keys are optional."
  {:var?                      s/Bool
   (s/optional-key :file)   (s/maybe s/Str)
   (s/optional-key :line)   (s/maybe s/Int)
   (s/optional-key :column) (s/maybe s/Int)})

(s/defschema NavigateTarget
  {:name   s/Str
   :ns     s/Str
   :type   s/Str
   :via    (s/enum :insert :retract)
   (s/optional-key :unit) s/Str          ; composed selection; absent in a live session
   :source SourceLoc})

(s/defschema NavigateResult
  {:direction  (s/enum :producer :consumer :type)
   :production (s/maybe s/Str)
   :type       s/Str
   :targets    [NavigateTarget]})

(s/defschema NavigateError
  {:error s/Str})

(s/defschema NavigateResponse
  "A `navigate` result: either a `NavigateResult` or an error map."
  (s/conditional #(contains? % :error) NavigateError
                 #(contains? % :direction) NavigateResult))

(s/defschema NavigateRuntime
  "Runtime-provided capabilities for `clara.explorer.server.navigate`: resolution and source location, which differ
   per runtime. The JVM resolves aliased and bare symbols over the editor's live namespaces and
   reads var metadata; the babashka client assumes editor-resolved fully-qualified tokens and
   reports every source as absent."
  {:resolve-token      (s/=> (s/maybe s/Str) (s/maybe s/Symbol) s/Str)
   :token->fq-sym      (s/=> (s/maybe s/Symbol) (s/maybe s/Symbol) s/Str)
   :production-source  (s/=> SourceLoc s/Str)})

;; ---------------------------------------------------------------------------
;; Fact type provenance (producer / consumer trees)
;; ---------------------------------------------------------------------------

(s/defschema FactTypeProvenanceCursor
  "The cursor root: the `:input` shape is a `NavigateInput`, but `:side` there is the
   *structural* side at the cursor (`:lhs` / `:rhs` / absent), not a traversal direction —
   that is the top-level `:direction`."
  {:kind (s/eq :cursor)
   :input NavigateInput})

(s/defschema FactTypeProvenanceProductionRoot
  "The rendered-production root: expand this production one layer."
  {:kind (s/eq :production)
   :name s/Str})

(s/defschema FactTypeProvenanceInput
  "Input for `clara.explorer.server.navigate/fact-type-provenance`. `:depth` is extra production
   levels to inline (`0` is the root's immediate production layer) or `:full`; `:ancestors` names
   the productions strictly above the root. Shape violations are the schema's job — the operation
   answers `{:error …}` only for what the schema cannot express (unknown names, a depth that is
   neither a non-negative integer nor `:full`)."
  {:node (s/conditional
          #(= :cursor (:kind %)) FactTypeProvenanceCursor
          #(= :production (:kind %)) FactTypeProvenanceProductionRoot)
   :direction (s/enum :producer :consumer)
   :depth (s/cond-pre s/Int (s/eq :full))
   :ancestors [s/Str]})

(s/defschema TypeShortRef
  "A bridge type as the renderer shows it: the full serialized name plus its display form."
  {:name s/Str
   :short s/Str})

(s/defschema FactTypeProvenanceNode
  "One provenance node. `:children` absent means expandable (or stopped by `:cycle?` /
   `:seen?`); `[]` means a known leaf. `:via`, `:vias`, `:matches`, and `:bridge?` need a parent
   fact type, so they are absent at a root production. Nodes are recursive: a fact-type node's
   children are production nodes, and vice versa."
  (s/conditional
   #(= :message (:kind %)) {:kind (s/eq :message)
                            :text s/Str}

   #(= :fact-type (:kind %)) {:kind (s/eq :fact-type)
                              :type s/Str
                              :type-short s/Str
                              (s/optional-key :external?) s/Bool
                              (s/optional-key :vias) [(s/enum :insert :retract)]
                              :children [(s/recursive #'FactTypeProvenanceNode)]}

   :else {:kind (s/eq :production)
          :name s/Str
          :ns s/Str
          :type s/Str
          (s/optional-key :unit) s/Str
          (s/optional-key :via) (s/enum :insert :retract :consume)
          (s/optional-key :vias) [(s/enum :insert :retract)]
          (s/optional-key :matches) [TypeShortRef]
          (s/optional-key :bridge?) s/Bool
          (s/optional-key :outputs) (s/eq :unknown)
          :source SourceLoc
          :cycle? s/Bool
          :seen? s/Bool
          (s/optional-key :children) [(s/recursive #'FactTypeProvenanceNode)]}))

(s/defschema FactTypeProvenanceResult
  {:node FactTypeProvenanceNode})

(s/defschema FactTypeProvenanceResponse
  "A `clara.explorer.server.navigate/fact-type-provenance` result: either a
   `FactTypeProvenanceResult` or an error map."
  (s/conditional #(contains? % :error) NavigateError
                 #(contains? % :node) FactTypeProvenanceResult))
