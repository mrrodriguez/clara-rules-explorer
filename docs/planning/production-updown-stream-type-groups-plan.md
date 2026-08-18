# Plan

In the UI when viewing a rule/query production as a summary/full view, the upstream/downstream
productions are listed in some order independent from the actual fact types they are involved in.

It may be more friendly to instead organize this by fact types instead.
A production can have (optional depending on rule/query and underlying structure), input types and
insert/retract types. Anything for insert types applies equally to retract types, so will leave that
out for concision in this Problem statement.

When someone is looking at a production, they are typically thinking about what it consumes from the
input types vs what it produces via the insert types.

Once you find the particular fact type of interest in those 2 categories, that is when you want to
know which upstream/downstream production actually satisfies that type and also what concrete type that
other production actually produces/consumes.

A "type" @ui/src/lib/components/rulebase/FactTypeReferenceLink.svelte cannot be just a link for this
to work right. The way it links needs to be more like @ui/src/lib/components/rulebase/ProductionReferenceLink.svelte 
where navigating to the type is a dedicated icon. It should be default collapsed, but expandable.
When expanded it should show the upstream rules that satisfy the LHS types (input) and the
downstream productions that the insert types (output) satisfies. When the satisfied types are not
the same as the types directly involved on the production in view, that should be shown too. When
they are the same, nothing should be shown.
This concept is similar to the existing "show type matches" popover right now, except we do not need
to show the satisfies hierarchy linkage when it is the same type.

For the full view, the input/insert/retract types are on the sidebar. However, it should behave the
same. We can keep the upstream/downstream options in the full screen sidebar for when someone wants
to navigate that way directly still.
The current "show type matches" popover though should be fixed to not show the "satifies" part if
the types are the same, It should just show the type involved standalone instead of "X satisfies X"
since that just makes it harder to know when a direct type is satisfied vs not.

In the summary view the upstream/downstream dedicated components can also stay, but they should be
moved to a secondary tab that can be clicked to see them.
