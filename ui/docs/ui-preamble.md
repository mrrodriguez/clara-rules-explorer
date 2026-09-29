All source path links here are relative to this project repo root.

This is a project to add UI explorer to clara-rules
There is an HTTP API available in Clojure: explorer/src/clara/explorer/server/api.clj
We can run the server-side via server/test/clara/explorer/server/smoke_test.clj for now as seen in the rich `comment` block.
That will default to localhost:9001

There is ui/README.md initialized to be the client for this server. This is a new and modern svelte kit app.
