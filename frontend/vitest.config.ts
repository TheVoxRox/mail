import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
	/*
	 * Compiles .svelte files for the few tests that mount a component — the
	 * check e2e cannot make for anything shown only inside the native shell,
	 * which the mocked build never has. The same runes rule as vite.config.ts;
	 * SvelteKit itself stays out, so a mounted component sees the $app stubs
	 * below rather than the framework.
	 */
	plugins: [
		svelte({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			}
		})
	],
	resolve: {
		alias: [
			{ find: /^#lib\/(.*)$/, replacement: path.resolve(here, './src/lib/$1') },
			{
				find: /^\$app\/env$/,
				replacement: path.resolve(here, './src/test-stubs/app-env.ts')
			},
			{
				find: /^\$app\/state$/,
				replacement: path.resolve(here, './src/test-stubs/app-state.ts')
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
		exclude: ['**/node_modules/**', '**/*.e2e.ts'],
		globals: false,
		clearMocks: true,
		restoreMocks: true,
		/*
		 * The gate suites are a project of their own only because they need
		 * different timeouts. They run their script as a process against a
		 * throwaway git repo, so a single test is a handful of `git` and `node`
		 * spawns: the slowest sit at 2–3 s on an idle machine, which the 5 s
		 * default survives right up until the machine is busy (a parallel e2e run
		 * was enough to time out check-audit-freshness). Raising it for `src` too
		 * would cost the fast fail on a genuinely hung unit test, which is worth
		 * keeping.
		 *
		 * The component tests (`*.svelte.test.ts`, Svelte's own naming) are one
		 * because they need the browser build of Svelte: under Node's conditions
		 * `mount` resolves to the server build, which refuses to run, and the
		 * lifecycle functions to its no-ops.
		 */
		projects: [
			{
				extends: true,
				test: {
					name: 'unit',
					environment: 'node',
					include: ['src/**/*.test.ts'],
					exclude: ['src/**/*.svelte.test.ts']
				}
			},
			{
				extends: true,
				resolve: { conditions: ['browser'] },
				test: {
					name: 'components',
					environment: 'jsdom',
					include: ['src/**/*.svelte.test.ts']
				}
			},
			{
				extends: true,
				test: {
					name: 'gates',
					environment: 'node',
					include: ['scripts/**/*.test.mjs'],
					testTimeout: 30000,
					hookTimeout: 30000
				}
			}
		],
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
