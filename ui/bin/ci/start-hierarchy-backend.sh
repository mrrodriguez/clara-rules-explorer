#!/bin/bash
# Start the loan-hierarchy-rules explorer backend on port 9201.
# Used by Playwright's webServer config for the hierarchy e2e project.
# If E2E_PID_FILE is set, writes the PID so `make e2e-stop` can kill it.
set -euo pipefail

if [ -n "${E2E_PID_FILE:-}" ]; then
	mkdir -p "$(dirname "$E2E_PID_FILE")"
	echo $$ > "$E2E_PID_FILE"
fi

cd "$(dirname "$0")/../../../server"

exec clojure -M:hierarchy-run 9201
