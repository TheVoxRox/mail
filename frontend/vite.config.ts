import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// Tauri SPA: every unknown route falls back to index.html and the Svelte router resolves it.
			adapter: adapter({ fallback: 'index.html' }),
			// SvelteKit 3 polls for a new deployment every hour by default. The app
			// ships inside its own installer and is replaced by the updater, so
			// there is no deployment to find. The check SvelteKit still makes when
			// the window regains focus fetches version.json from the bundle itself
			// and cannot change within a build.
			version: { pollInterval: 0 }
		})
	],
	server: { watch: { ignored: ['**/src-tauri/**'] } }
});
