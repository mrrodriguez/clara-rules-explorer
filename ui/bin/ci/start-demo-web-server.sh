#!/bin/bash
# Start a demo-mode SvelteKit preview server on port 4173.
# Used by Playwright's webServer config for e2e tests.
set -euo pipefail

cd "$(dirname "$0")/../.."

export VITE_DEMO_MODE=true

pnpm run build
pnpm run preview
