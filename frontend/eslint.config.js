import path from 'node:path';
import { includeIgnoreFile } from '@eslint/compat';
import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';
import { defineConfig } from 'eslint/config';
import globals from 'globals';
import ts from 'typescript-eslint';
import svelteConfig from './svelte.config.js';

const gitignorePath = path.resolve(import.meta.dirname, '.gitignore');

export default defineConfig(
	includeIgnoreFile(gitignorePath),
	{
		// Rust/Tauri build artifacts must not be linted.
		ignores: [
			'src-tauri/target/**',
			'src-tauri/gen/**',
			'build/**',
			'.svelte-kit/**',
			'static/mockServiceWorker.js'
		]
	},
	js.configs.recommended,
	ts.configs.recommended,
	svelte.configs.recommended,
	{
		languageOptions: { globals: { ...globals.browser, ...globals.node } },
		rules: {
			// typescript-eslint strongly recommend that you do not use the no-undef lint rule on TypeScript projects.
			// see: https://typescript-eslint.io/troubleshooting/faqs/eslint/#i-get-errors-from-the-no-undef-rule-about-global-variables-not-being-defined-even-though-there-are-no-typescript-errors
			'no-undef': 'off',
			'@typescript-eslint/no-unused-vars': [
				'error',
				{ argsIgnorePattern: '^_', varsIgnorePattern: '^_' }
			]
		}
	},
	{
		files: ['**/*.svelte', '**/*.svelte.ts', '**/*.svelte.js'],
		languageOptions: {
			parserOptions: {
				projectService: true,
				extraFileExtensions: ['.svelte'],
				parser: ts.parser,
				svelteConfig
			}
		}
	},
	{
		// Type-aware rules for app TS code. A dropped promise rejection in the
		// boot/sidecar path surfaces as a silent hang or an unhandled-rejection
		// boot error (see lib/backend/sidecar.ts, lib/api/session.ts) — make
		// "fire and forget" explicit with `void` or handle the rejection.
		files: ['src/**/*.ts'],
		ignores: ['**/*.svelte.ts'],
		languageOptions: {
			parserOptions: { projectService: true }
		},
		rules: {
			'@typescript-eslint/no-floating-promises': 'error',
			'@typescript-eslint/await-thenable': 'error'
		}
	},
	{
		rules: {
			'svelte/button-has-type': 'error',
			// Povolujeme dynamické goto() s query-stringy a složkové názvy ze stavu
			// (`folderHref` vrací už `resolve()`-ovanou URL, rule to ale nepozná).
			'svelte/no-navigation-without-resolve': 'off'
		}
	}
);
