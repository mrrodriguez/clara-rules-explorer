import { defineConfig } from '@playwright/test';

// Two e2e projects, each running a SvelteKit dev server proxying /v1 to its
// own live explorer backend:
//
//   loan-app   — the loan-doc-rules + loan-app-rules session (backend 9101,
//                frontend 4173).  Covers the shared rulebase/session UI.
//   hierarchy  — the loan-hierarchy-rules session: keyword derive hierarchy,
//                vector-tuple fact types (backend 9201, frontend 4174).
//                Covers ancestors, type-bridge :match rows, session facts
//                for hierarchy-derived types.
//
// Backends are started on 9101/9201 — clear of the 9001 default the local
// REPL integration-test helper binds.  `reuseExistingServer: true` reuses a
// backend/frontend already running on the port (e.g. a developer has one up)
// and starts one otherwise — so local runs and CI behave identically.
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
	// Generous timeout: a cold SvelteKit dev server compiles the first-hit
	// routes on demand, which can take well over 30s on the first test.
	timeout: 120_000,
	use: {
		baseURL: 'http://localhost:4173',
		launchOptions: {
			args: process.env.PI_CODING_AGENT ? ['--no-sandbox', '--disable-setuid-sandbox'] : undefined
		}
	},
	projects: [
		{
			name: 'loan-app',
			testIgnore: /Hierarchy.*\.e2e\.ts$/,
			webServer: [loanAppBackend, frontend('http://localhost:9101', 4173)]
		},
		{
			name: 'hierarchy',
			use: { baseURL: 'http://localhost:4174' },
			testMatch: /Hierarchy.*\.e2e\.ts$/,
			webServer: [hierarchyBackend, frontend('http://localhost:9201', 4174)]
		}
	]
});
