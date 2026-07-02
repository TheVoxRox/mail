import { defineConfig } from 'vitest/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
	resolve: {
		alias: [
			{ find: /^\$lib\/(.*)$/, replacement: path.resolve(here, './src/lib/$1') },
			{ find: /^\$lib$/, replacement: path.resolve(here, './src/lib') },
			{
				find: /^\$app\/environment$/,
				replacement: path.resolve(here, './src/test-stubs/app-environment.ts')
			},
			{
				find: /^\$app\/stores$/,
				replacement: path.resolve(here, './src/test-stubs/app-stores.ts')
			},
			{
				find: /^\$app\/paths$/,
				replacement: path.resolve(here, './src/test-stubs/app-paths.ts')
			},
			{
				find: /^@tauri-apps\/api\/path$/,
				replacement: path.resolve(here, './src/test-stubs/tauri-path.ts')
			},
			{
				find: /^@tauri-apps\/plugin-fs$/,
				replacement: path.resolve(here, './src/test-stubs/tauri-fs.ts')
			},
			{
				find: /^@tauri-apps\/plugin-http$/,
				replacement: path.resolve(here, './src/test-stubs/tauri-http.ts')
			}
		]
	},
	test: {
		environment: 'node',
		include: ['src/**/*.test.ts', 'scripts/**/*.test.mjs'],
		exclude: ['**/node_modules/**', '**/*.e2e.ts'],
		globals: false,
		clearMocks: true,
		restoreMocks: true,
		coverage: {
			provider: 'v8',
			// Floors set ~5 points below the actual baseline so an accidental
			// regression fails CI but small refactors don't. Raise them as the
			// codebase grows test coverage. Security-critical files (XSS
			// sanitizer, HTTP client with auth header handling) have stricter
			// per-file targets because Playwright e2e doesn't count toward
			// this report.
			thresholds: {
				lines: 65,
				statements: 65,
				branches: 65,
				functions: 55,
				'src/lib/mail/content-sanitizer.ts': {
					lines: 90,
					statements: 90,
					branches: 85,
					functions: 90
				},
				'src/lib/api/client.ts': {
					lines: 85,
					statements: 85,
					branches: 80,
					functions: 80
				}
			}
		}
	}
});
