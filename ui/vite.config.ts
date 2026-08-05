import { defineConfig } from 'vitest/config';
import { playwright } from '@vitest/browser-playwright';
import { sveltekit } from '@sveltejs/kit/vite';

// The /v1 API proxy target.  e2e runs two SvelteKit dev servers, each
// proxying to a different explorer backend (loan-app vs hierarchy ruleset);
// the static demo build leaves this unset and serves /demo-data instead.
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:9001';

export default defineConfig({
	plugins: [sveltekit()],
	server: {
		proxy: {
			'/v1': apiProxyTarget
		}
	},
	preview: {
		proxy: {
			'/v1': apiProxyTarget
		}
	},
	test: {
		expect: { requireAssertions: true },
		projects: [
			{
				extends: './vite.config.ts',
				test: {
					name: 'client',
					browser: {
						enabled: true,
						provider: playwright(),
						instances: [{ browser: 'chromium', headless: true }]
					},
					include: ['src/**/*.svelte.{test,spec}.{js,ts}'],
					exclude: ['src/lib/server/**']
				}
			},

			{
				extends: './vite.config.ts',
				test: {
					name: 'server',
					environment: 'node',
					include: ['src/**/*.{test,spec}.{js,ts}'],
					exclude: ['src/**/*.svelte.{test,spec}.{js,ts}']
				}
			}
		]
	}
});
