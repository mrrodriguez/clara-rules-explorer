#!/bin/bash
# Start the loan-app-rules explorer backend (the session the static demo and
# most e2e tests exercise) on port 9101.  Used by Playwright's webServer
# config for the loan-app e2e project.  Port 9101 keeps clear of the 9001
# default used by local REPL/integration-test helpers.
set -euo pipefail

cd "$(dirname "$0")/../../../server"

exec clojure -M:demo-run -p 9101 -s demo-data/session.bin -l test-resources/clara/server/tools/graph/annotations/loan-doc-rules-annotations.edn
