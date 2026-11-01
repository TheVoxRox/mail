import { defineConfig } from '@playwright/test';

export default defineConfig({
	testMatch: '**/*.e2e.{ts,js}',
	projects: [
		{ name: 'a11y', testMatch: '**/a11y.e2e.ts' },
		{ name: 'functional', testMatch: '**/*.functional.e2e.ts' }
	],
	use: {
		baseURL: 'http://127.0.0.1:4173',
		/*
		 * A test that fails in CI leaves a trace (the DOM at every step) in
		 * test-results/, which the CI jobs upload on failure. The error context
		 * alone held a page snapshot of the focused field and nothing else, and a
		 * failure that never happens locally (the palette's selection, #815) could
		 * not be read from it. Off locally, where the run can be repeated.
		 */
		trace: process.env.CI ? 'retain-on-failure' : 'off'
	},
	webServer: {
		command: 'node scripts/playwright-preview.mjs --mode=e2e',
		port: 4173,
		reuseExistingServer: true,
		timeout: 120000
	}
});
