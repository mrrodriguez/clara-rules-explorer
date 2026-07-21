1. The caller can indeed provide clj-kondo hooks. I think it is reasonable to assume that the caller
   will likely provide the imports from clara-rules itself as part of these hooks. You can see how
   this setup looks in this project itself @server/.clj-kondo/imports/clara/rules/config.edn
   We may want to consider using the verbatim clara-rules config in what we default to when the
   caller does not give their own since this is the common case. We should at least know that our
   analysis is not going to break when clara-rules hooks from the lib itself are provided and therefore
   now understand defquery and defrule etc. Your offset protection should be fine with this I would
   expect. Very this. Consider this in the design. This will also effect section "### 5.2 Bundled
   kondo config: empty by design". Notably the caller kondo config may indeed produce both vars and
   defrule/defquery constructs and we have to be ok with those defrule and defquery constructs not
   polluting our analysis. Which this design should have already accounted for via the offset logic.

2. In section 5.1 on the "reconstructed ns form" fallback: clojure.core and java.lang refers are
   automatic for any newly created ns in clojure. You shouldn't have to remap them when you
   reconstruct the ns form. the standard `ns` macro from clojure automatically adds a lot of defaults.
   You just need to not exclude them. Do not try to enumerate all of clojure.core and java.lang in
   the synthesized `ns` macro header - that'd be huge and redundant. The only caveat typically would
   be is that some people can `(:refer-clojure :exclude [defn])` for example to not automatically
   get that refer in a ns. They can also use the :only or :rename directives which would change the
   default behavior.

   eg.

```
   ;; Copied from https://gist.github.com/jkk/284230 (ns-cheatsheet.clj):

;; Excludes built-in print
(:refer-clojure :exclude [print])

;; Excludes all built-ins except print
(:refer-clojure :only [print])

;; Renames built-in print to core-print
(:refer-clojure :rename {print core-print})

```

to detect this special case, I think you'd have to take the `ns-publics` from clojure.core - which
are the defaults. Then look at the Namespace in question to see if any of those publics are not
there in the `ns-map`. If they are not there, they must be `(:refer-clojure :exclude [<syms
here>])`.

java.lang imports are not optional. The only way to exclude them would be to explicitly `ns-unmap`
them in the namespace code dynamically. The default class imports can be found in
clojure.lang.RT/DEFAULT_IMPORTS static field. You could compare the `ns-imports` vs these entries to
see if any of them need to be unmapped. This is a rare case and it'd have to be an inline `ns-unmap`
call added to our synthesized ns following immediately after the `ns` form at the head, eg:

```
(ns-unmap (the-ns 'tester.mikerod) 'String)
```

3. In section 5.1 bullet (3) `kono/run!` is going to run a single ns at at time. Will kondo
   correctly understand how to traverse the var usages when you do this? what if a var usage from
   namespace X needs to analyze namespace Y where it is defined and so on? I think we reconcile this after
   we have gathered the per-ns kondo passes correct? I believe this what bullet (5) mentions in that
   same section.

4. In section 5.4 bullet (3) looks incorrect and needs more elaboration. Why do we need to walk form
   seqs to resolve the first symbol in any way other than our deftype/defrecord/java constructor
   defaults? In particular, do not assume a `with-meta` with a `:type` means anything since that only
   applies if the `fact-type-fn` uses that and that is overstepping what we can or should assume. A
   dynamic resolver fn given would have to determine based on this callsite form and ns what to
   resolve to if applicable. I also do not understand the point concerning `clojure.core/var` being
   specially handled. I used that in my example here, but that is not a pattern we are going to
   hardcode into the logic. The "fact as function var" pattern is one I will need to support but it
   would have to be done in a caller-supplied manner where the caller specifically gave guidance to
   how certain fact types, when bound as RHS locals, should be considered an alias to an underlying
   var, so that that var can be traced in the chain. this function as var fact pattern is complex. I
   want you to note it in the design, but make it one of the later milestones for us to actually try
   to implement it completely.

5. for the ":callsite-resolver-fn contract:" of section 5.4 I think that the resolve should get
   the entire rule strucutre, not just the rule-rhs. Then it has more context to work from.

6. Concerning section 10 of the open questions:

(a) Concernign bullet (1) if you try to use an invalid symbol in a
`def` form I'd suspect it may fail to be parsed by clj-kondo, but that is not obviously true.
Have you explored that?
Concerning buillet (2) the `:env` on a rule is I believe meant to capture closures over rule
structures when they were typical defined via macros eg.

```
(let [x :thing]
  (defrule my-rule => (insert! {:x x})))
```

This is a rare case, but that is what it is about. The env provides additionally bound mappings the
RHS can refer to (maybe the LHS too, but not applicable here). We can pass the full production form
the to callsite-resolver-fn and it will have access to this detail if it wants to incorporate it.

(b) Concerning bullet (3), I do not follow what you mean by record literals not being supported. Do you
mean something like #my.record.Thing{:x 1} ? We should be able to support that since it'd just be
another way to represent a known constructor, but how did you encounter one?

(c) Concerning bullet (5) I do not know what you mean by class/keyword assumptions for "conventional
LHS" facts. We cannot assume any LHS conventions beyond what the production rule/query structures
allow for the <fact type> position. The fact-type-fn and ancestors-fn is pluggable and we can end up
with various possibilities here. Notably observe this is legal syntax:

```
(r/defrule my-rule-test1
  [[:vector :type :thing]] => :anything)

(my-rule-test1)
;;= returns

{:ns-name clara.server.tools.graph.rules.loan-doc-rules,
 :lhs [{:type [:vector :type :thing], :constraints []}],
 :rhs (do :anything),
 :name "clara.server.tools.graph.rules.loan-doc-rules/my-rule-test1",
 :handler clara.server.tools.graph.rules.loan-doc-rules/my-rule-test1}
```

The shape of a fact-type can be mostly anything. When it is a symbol it is special cased by the
parser in clara.rules.dsl/construct-condition but that is noting we can rely upon. When we see a
class or a record type constructor used for an insertion though, we can reasonably assume those must
be what LHS are matching on so we publish them. The ancestors-fn is used with the fact-type-fn
during graph analysis to see how those connect to LHS. This may be in fact what you mean by
"conventional" and if it is then this ok because our assumption covers our bases well enough. We know
the instance type that is going to be inserted in these cases. So we know that fact-type-fn and
ancestors-fn called upon it will yield its connection the LHS.

(d) Concerning bullet (7) with text "reconstructed-fallback intern stubs — emit `(def name)` for every
interned non-production var." I need you to elaborate on this. I do not understand the use case.
