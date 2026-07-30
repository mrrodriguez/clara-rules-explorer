import { defineConfig } from '@playwright/test';

export default defineConfig({
	webServer: {
		command: 'VITE_DEMO_MODE=true pnpm run build && pnpm run preview',
		port: 4173
	},
	testMatch: '**/*.e2e.{ts,js}',
	use: {
		baseURL: 'http://localhost:4173',
		launchOptions: {
			args: process.env.PI_CODING_AGENT ? ['--no-sandbox', '--disable-setuid-sandbox'] : undefined
		}
	}
});
