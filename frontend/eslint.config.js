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
		// No `projectService` here on purpose: not one rule that applies to
		// .svelte reads type information (verified against every enabled rule's
		// meta.docs.requiresTypeChecking), so full type analysis over every
		// component was pure overhead — `eslint .` took 93 s with it and 5.8 s
		// without, findings identical. `parser` stays: it is what parses
		// `lang="ts"`, and that needs no program. Turning the two type-aware
		// rules below on for components as well is the only thing this gives
		// up; measured over src it finds nothing today.
		files: ['**/*.svelte', '**/*.svelte.ts', '**/*.svelte.js'],
		languageOptions: {
			parserOptions: {
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
			// Allow dynamic goto() with query strings and folder names taken from
			// state (`folderHref` already returns a `resolve()`-ed URL, but the
			// rule cannot tell).
			'svelte/no-navigation-without-resolve': 'off'
		}
	}
);
