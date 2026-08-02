We need to take into account fact type hierarchies, which we do respect with building our
analysis graph of the session productions currently, but we fail to represent in API-level
important flows. Here is an example:

Rule X inserts type A
Type A is a descendant of type B
Rule Y requires type B (via LHS condition fact type matching via ancestors-fn hierarchy)

Our analysis currently captures these details:

- Rule X is upstream of Rule Y
- Rule Y is downstream of Rule X
- Rule X inserts type A
- Rule Y requires type B

This means the upstream/downstream linkage is working to find a dependency that is
realized via a
derived hierarchy of types.

However, we fail to show important details to make this clear:

- When viewing type A, it does not mention what it is a descendant of. The
  API has no way to see
  that type A is a type B.
- When we view downstream Rule Y we see it requires a type B. When we follow the upstream to Rule A we see it inserts a type A. Nothing tells us from these views that the type A is actually what satisfied type B. This makes it confusing. I may have several upstream rules found, and I know I have eg. a type B I'm requiring, but I don't know which rule is contributing it since none of them even mention their type is a descendant of B.

a. The API needs to bring clarity to hierarchies. The easiest first step is to include on a fact type the information of what its ancestors-fn returns. There may be several layers of hierarchy, so we must make sure the ancestors-fn shows this clearly and ideally in hierarchy ordering.

b. When I am looking at a rule/query (aka. production) summary, the required types should remain concretely what they are declared since that is clear as a requirement of the rule/query.

Each upstream production contributes a match that may be a different concrete type. This should be represented per upstream so it is clear the type it contributes and how that relatees to the type required. Something like upstream Rule A produces type A which is a type B. We skip hierarchy not important in this bridgin from produced type to required type for brevity and clarity.

Each downstream production requires the output/insert type of a rule. We should make it clear the concrete type they require relative ot the output type the rule is inserting. If a downstream Rule Y requires a type B and we are looking at Rule X that inserts type A, it should be make it clear that type A being inserted is what is satisfying the required type B. Skip the rest of any intermediate hierarchy here for brevity and clarity.

You must now make a design plan to work through this in stages. The fact type hierarchy respresentation being the first step. The goal will be to extend the server API to account for these details in a way that is concise but provides these crucial details.
