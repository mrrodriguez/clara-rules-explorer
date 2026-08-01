1.  One upstream bug I did not work around. validate-layers' :ambiguous-callsite-reference over-reports: assign-callsite-ids scopes ordinals to one rule+dimension, but lint-ambiguous-references computes duplicate-group sizes across every rule in the merge. Two rules in one namespace sharing a source form collide on the prefix — e.g. one-rule and two-rule both containing (->fact fact-type fact-map)). Grouping by [rule dimension prefix] would fix it in annotations/report.clj. I documented it in docs/annotation-plugins.md and the skill's lint table rather than suppressing the finding.

`:ambiguous-callsite-reference` over-reports.** A callsite id addresses one rule
 *and* one dimension: `annotations.callsite/assign-callsite-ids` assigns ordinals within a single
 rule's callsite vector, and its own docstring says identity is only needed at that scope. But
 `annotations.report/lint-ambiguous-references` computes duplicate-group sizes over
 `annotation-callsites` of the *whole* merged map, so two rules in one namespace that share a
 source form collide on the group prefix and every reference to either is flagged. 

 Grouping by `[rule dimension prefix]` rather than `prefix` would fix it. Until then, treat these
 as advisory and rely on the two `:error` findings.

2. 
