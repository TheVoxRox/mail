import adapter from '@sveltejs/adapter-static';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		// Tauri SPA: every unknown route falls back to index.html and the Svelte router resolves it.
		adapter: adapter({ fallback: 'index.html' })
	}
};

export default config;
