import { defineConfig } from '@playwright/test';

export default defineConfig({
	testMatch: '**/*.e2e.{ts,js}',
	projects: [
		{ name: 'a11y', testMatch: '**/a11y.e2e.ts' },
		{ name: 'functional', testMatch: '**/*.functional.e2e.ts' }
	],
	use: {
		baseURL: 'http://127.0.0.1:4173'
	},
	webServer: {
		command: 'node scripts/playwright-preview.mjs --mode=e2e',
		port: 4173,
		reuseExistingServer: true,
		timeout: 120000
	}
});
