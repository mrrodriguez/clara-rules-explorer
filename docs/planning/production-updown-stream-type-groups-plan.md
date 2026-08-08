# Problem statement

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

It seems that we may not have a need at all for showing upstream/downstream productions
independently on production summary/full views.

Instead we can have our input types/insert types be expandable sections and when expanded the
upstream/downstream are enumerated respectively with navigation to go to their production views, but
also with information about their concrete type that satisfies/is satisfied by the input/insert
types in question.
