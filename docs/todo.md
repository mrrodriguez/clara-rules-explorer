1. When looking at a fact type for rules clara.server.tools.graph.rules.loan-hierarchy-rules clara.server.tools.graph.rules.loan_hierarchy_rules.LoanApplication it is used by insert-income-document rule. It is also used by clara.server.tools.graph.rules.loan-hierarchy-rules/find-map-facts though just as an ancestor.

I'd expect the fact type "used by" rules/queries and "retracted by rules" to be able to detect
producer/consumer types the same way as we see on query/rule views.

Also, clara.server.tools.graph.rules.loan-hierarchy-rules/find-map-facts is not detecting an
upstream rule at all, even though there is an upstrem rule satisfying it just with different
producer/consumer types.

This relates to the work recently done (on this branch) for @docs/extend-api-hierarchy-details/README.md (which is the primary purpose of this branch).

2. rebuild demo-data
