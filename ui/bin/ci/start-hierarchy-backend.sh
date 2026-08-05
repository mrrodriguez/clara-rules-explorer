#!/bin/bash
# Start the loan-hierarchy-rules explorer backend (keyword derive hierarchy,
# vector-tuple fact types, a record fact type) on port 9201.  Used by
# Playwright's webServer config for the hierarchy e2e project.
set -euo pipefail

cd "$(dirname "$0")/../../../server"

exec clojure -M:hierarchy-run 9201
