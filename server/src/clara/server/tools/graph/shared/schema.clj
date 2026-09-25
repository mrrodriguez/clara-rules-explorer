(ns ^{:clara-rules-explorer/bb-loaded true} clara.server.tools.graph.shared.schema
  "The shapes the editor-navigation namespaces hand each other, in one place.

  The navigate contract — input, targets, results, and the runtime capabilities map — travels
  between `shared.navigate`, its JVM shell (`clara.server.graph.client`), and the babashka client,
  far enough from where each piece is built that prose in whichever docstring happened to receive it
  would drift silently."
  (:require [schema.core :as s]))

(s/defschema NavigateInput
  {(s/optional-key :production) (s/maybe s/Str)   ; fq "ns/rule"; nil = global path
   (s/optional-key :side)       (s/enum :lhs :rhs)
   (s/optional-key :caller-ns)  s/Str             ; buffer ns, for global path + ctor resolution
   :token                       s/Str})

(s/defschema SourceLoc
  {:var?   s/Bool
   :file   (s/maybe s/Str)
   :line   (s/maybe s/Int)
   :column (s/maybe s/Int)})

(s/defschema NavigateTarget
  {:name   s/Str
   :ns     s/Str
   :type   s/Str
   :via    (s/enum :insert :retract)
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
  "Runtime-provided capabilities for `shared.navigate`: resolution and source location, which differ
   per runtime. The JVM resolves aliased and bare symbols over the editor's live namespaces and
   reads var metadata; the babashka client assumes editor-resolved fully-qualified tokens and
   reports every source as absent."
  {:resolve-token      (s/=> (s/maybe s/Str) (s/maybe s/Symbol) s/Str)
   :token->fq-sym      (s/=> (s/maybe s/Symbol) (s/maybe s/Symbol) s/Str)
   :production-source  (s/=> SourceLoc s/Str)})
