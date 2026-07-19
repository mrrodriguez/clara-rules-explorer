1. Our analyzed annotations format supports :clara-rules/dynamic-insert-types-detected and :clara-rules/dynamic-retract-types-detected . The API should represent these and also be able to show callsite info for this and resolve info when available. We need to extend our graph API to show this and we need to keep in mind our API schema and docs involved. clara.server.tools.graph.analyze-test has good example dynamic rules. we'll need to add a few dynamic rule cases to our main smoke testing rules so we can see it in our demo. That'll involved /Users/mrrodriguez/Projects/clara-rules-explorer/server/test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn and clara.server.tools.graph.rules.loan-doc-rules .

   Here is an example of a more sophisticated type of annotations we want to work with:
```
#:gateless-product-ruleset.checksum-data.core{conditions-report-checksum-case-metadata-rule
                                              #:clara-rules{:dynamic-insert-types-detected
                                                            {:callsites
                                                             [{:source-str
                                                               "(->fact :case/metadata-output {:path \"condition-report-checksum\", :old-value ?old-checksum, :new-value ?new-checksum})",
                                                               :ns-name-sym
                                                               gateless-product-ruleset.checksum-data.core,
                                                               :filename
                                                               "gateless_product_ruleset/checksum_data/core.clj",
                                                               :constructor
                                                               "facts.model.core/->fact",
                                                               :type-form
                                                               :case/metadata-output,
                                                               :status
                                                               :resolved,
                                                               :resolved-types
                                                               [:case/metadata-output]}],
                                                             :resolution
                                                             :full},
                                                            :insert-types
                                                            [:case/metadata-output]},
                                              gateless-aggregated-income-data-checksum-rule
                                              #:clara-rules{:dynamic-insert-types-detected
                                                            {:callsites
                                                             [{:source-str
                                                               "(->fact aggregated-fact-type (merge checksum {:priority priority}))",
                                                               :ns-name-sym
                                                               gateless-product-ruleset.checksum-data.core,
                                                               :filename
                                                               "gateless_product_ruleset/checksum_data/core.clj",
                                                               :constructor
                                                               "facts.model.core/->fact",
                                                               :type-form
                                                               aggregated-fact-type,
                                                               :status
                                                               :resolved-multi,
                                                               :resolution-method
                                                               :repl-config-enumeration,
                                                               :resolved-types
                                                               [:gateless/aggregated-irs-1040-income-data-checksum
                                                                :gateless/aggregated-irs-1099-r-income-data-checksum]}],
                                                             :resolution
                                                             :full},
                                                            :insert-types
                                                            [:gateless/aggregated-irs-1040-income-data-checksum
                                                             :gateless/aggregated-irs-1099-r-income-data-checksum]},
                                              some-unresolved-rule
                                              #:clara-rules{:dynamic-insert-types-detected
                                                            {:callsites
                                                             [{:source-str
                                                               "(build-checksum-fact loan-data)",
                                                               :ns-name-sym
                                                               gateless-product-ruleset.checksum-data.core,
                                                               :filename
                                                               "gateless_product_ruleset/checksum_data/core.clj",
                                                               :constructor
                                                               "gateless-product-ruleset.checksum-data.core/build-checksum-fact",
                                                               :status
                                                               :unresolved,
                                                               :reason
                                                               :non-fact-constructor}],
                                                             :resolution
                                                             :none}}}
```


2. The UI should be able to show details about the annotations dynamic type handling and resolution
   results when that is what it is working with. certain details are provided regarding resolved a dynamic type to its actual types for the graph
   vs partial vs none.
   

3. Setup an example of a special "functions as facts" to show how that can be discovered and tracked.

4. Consider an extension to analyze that can take a given callsite and call a helper function to
   resolve the type. Maybe use kondo for this too.
