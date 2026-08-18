1. DONE hierarchy shows ancestors in UI, but no descendants which is also useful.
1a. DONE in the full view sidebar popout for upstream/downstream/input types/insert types/retract types,
there is not enough space to show the full width even on wide screens so it is cropped and have to
horizontal scroll which we do not want. We want it to take the space it needs to not be cropped.
2. provenance chain is confusing when you only have the boundary-to-constructor-path - rule name
   goes in middle of the path.
3. default provenance expanded
4. DONE production full view show "dynamic insert callsites" info before "current session activity"
5. DONE Make "curent session activity" collapsible. Use tabs for "active matches" vs "inserted
   facts" vs "retracted facts" sections within.
6. why do clojure.lang and java.util types of ancestors/fact types get classified as "no namespace"?
