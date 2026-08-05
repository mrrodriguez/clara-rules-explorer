#!/bin/bash
# Start a SvelteKit dev server that proxies /v1 to a live explorer backend.
#   API_PROXY_TARGET — backend origin (default: http://localhost:9001)
#   PORT            — dev server port (default: 4173)
# Used by Playwright's webServer config for e2e tests.  e2e runs against live
# backends, not the static demo-data build (that is only for hosting the
# static demo).
set -euo pipefail

cd "$(dirname "$0")/../.."

export API_PROXY_TARGET="${API_PROXY_TARGET:-http://localhost:9001}"
PORT="${PORT:-4173}"

exec pnpm exec vite dev --port "$PORT" --strictPort
