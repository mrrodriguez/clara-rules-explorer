1. Our analyzed annotations format supports :clara-rules/dynamic-insert-types-detected and :clara-rules/dynamic-retract-types-detected . The UI should represent these and also be able to show callsite info for this and resolve info when available.

2. The UI and API should be able to show details about the annotations dynamic type handling when
   certain details are provided regarding resolved a dynamic type to its actual types for the graph
   vs partial vs none.
   
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
