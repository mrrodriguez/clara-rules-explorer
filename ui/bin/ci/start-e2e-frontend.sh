#!/bin/bash
# Start the e2e Node.js server (built with E2E_BUILD=true + adapter-node)
# wrapped with a /v1 proxy to the live backend.
#   API_PROXY_TARGET — backend origin (default: http://localhost:9001)
#   PORT             — listen port (default: 4173)
#   E2E_PID_FILE     — if set, writes PID so `make e2e-stop` can kill it
# Used by Playwright's webServer config for e2e tests.
set -euo pipefail

cd "$(dirname "$0")/../.."

export API_PROXY_TARGET="${API_PROXY_TARGET:-http://localhost:9001}"
PORT="${PORT:-4173}"

if [ -n "${E2E_PID_FILE:-}" ]; then
	mkdir -p "$(dirname "$E2E_PID_FILE")"
	echo $$ > "$E2E_PID_FILE"
fi

exec node bin/ci/e2e-server.js
