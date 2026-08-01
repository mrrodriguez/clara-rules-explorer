import { defineConfig } from '@playwright/test';

export default defineConfig({
	webServer: {
		command: 'bash bin/ci/start-demo-web-server.sh',
		port: 4173,
		reuseExistingServer: true
	},
	testMatch: '**/*.e2e.{ts,js}',
	use: {
		baseURL: 'http://localhost:4173',
		launchOptions: {
			args: process.env.PI_CODING_AGENT ? ['--no-sandbox', '--disable-setuid-sandbox'] : undefined
		}
	}
});
