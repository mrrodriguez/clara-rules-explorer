1. DONE clara.server.graph.server/swap-session! behavior does not make sense in terms of annotation
   reloading in that if you do not pass :annotations, you just get whatever annotations went with
   the last session. This is maybe ok if the session still aligns with them, but it could be something
   completely different. The config-atom itself should be updated with the new :annotations when they
   are given explicitly. When they are not given explicitly, we should just not use annotations at all
   and clear them from config-atom since we have no idea if they still align. An option could be given
   to reuse the current annotations explicitly though if they still exist.
