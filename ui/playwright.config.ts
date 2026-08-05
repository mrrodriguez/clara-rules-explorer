import { defineConfig } from '@playwright/test';

// Two e2e projects, each with its own live Clojure backend + SvelteKit dev
// frontend and its own test directory:
//
//   loan-app   — tests/loan-app — the loan-doc-rules + loan-app-rules session
//                (backend 9101, frontend 4173).  Covers the shared
//                rulebase/session UI.
//   hierarchy  — tests/hierarchy — the loan-hierarchy-rules session: keyword
//                derive hierarchy, vector-tuple fact types (backend 9201,
//                frontend 4174).  Covers ancestors, type-bridge :match rows,
//                session facts for hierarchy-derived types.
//
// Tests are grouped by directory, not filename prefix — each project's
// `testDir` scopes it to its own folder, so no regex filtering is needed.
//
// IMPORTANT: `webServer` is only supported at the TOP LEVEL of the config —
// Playwright does not expose it on a project, and silently ignores it there
// (no server would ever start).  All four servers are therefore started for
// any run; a `--project=`-filtered run still boots the other project's pair,
// which is wasted but harmless (distinct ports, and `reuseExistingServer`
// reuses anything already running — e.g. a local `make hierarchy-run`).
//
// Ports 9101/9201 deliberately avoid the `9001` default used by local
// REPL/integration-test helpers.  `reuseExistingServer: true` reuses a
// backend/frontend already running on the port and starts one otherwise —
// so local runs and CI behave identically.
const loanAppBackend = {
	command: 'bash bin/ci/start-loan-app-backend.sh',
	port: 9101,
	reuseExistingServer: true,
	timeout: 180_000
};

const hierarchyBackend = {
	command: 'bash bin/ci/start-hierarchy-backend.sh',
	port: 9201,
	reuseExistingServer: true,
	timeout: 180_000
};

function frontend(proxyTarget: string, port: number) {
	return {
		command: `API_PROXY_TARGET=${proxyTarget} PORT=${port} bash bin/ci/start-e2e-frontend.sh`,
		url: `http://localhost:${port}`,
		reuseExistingServer: true,
		timeout: 120_000
	};
}

export default defineConfig({
	testDir: 'tests',
	testMatch: '**/*.e2e.{ts,js}',
	// Serialize tests across both projects: the SvelteKit dev servers compile
	// routes on demand, and parallel workers hammering the same dev server
	// cause click actionability timeouts.  workers: 1 trades a slower suite
	// for deterministic runs (the first compile of each route is cached).
	workers: 1,
	// Tolerate a flaky test for now: a test that fails once and passes on its
	// retry is marked "flaky" but does not fail the run (revisit once the
	// remaining cold-start flakes are understood).
	retries: 1,
	// Generous timeout: a cold SvelteKit dev server compiles the first-hit
	// routes on demand, which can take well over 30s on the first test.
	timeout: 120_000,
	webServer: [
		loanAppBackend,
		hierarchyBackend,
		frontend('http://localhost:9101', 4173),
		frontend('http://localhost:9201', 4174)
	],
	use: {
		baseURL: 'http://localhost:4173',
		// Fail fast on locator actions (click/fill/etc.) — server startup is
		// the webServer layer's job (its own 120-180s timeouts), and a lost
		// click should surface in seconds, not minutes.
		actionTimeout: 10_000,
		launchOptions: {
			args: process.env.PI_CODING_AGENT ? ['--no-sandbox', '--disable-setuid-sandbox'] : undefined
		}
	},
	projects: [
		{
			name: 'loan-app',
			testDir: 'tests/loan-app'
		},
		{
			name: 'hierarchy',
			testDir: 'tests/hierarchy',
			use: { baseURL: 'http://localhost:4174' }
		}
	]
});
