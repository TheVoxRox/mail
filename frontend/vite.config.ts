import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig, normalizePath, type Plugin } from 'vite';
import tailwindcss from '@tailwindcss/vite';

/**
 * Refuses a dev server whose root SvelteKit and Vite spell differently.
 *
 * SvelteKit takes the root from the working directory as the shell spelled it;
 * Vite takes the directory's real path, as Windows spells it once Vite's
 * network-drive probe (`net use`) has answered. When they differ, as they do
 * for a shell sitting in `c:\...`, SvelteKit's client is served under two URLs,
 * the app's `$app/navigation` gets the copy that was never started, and every
 * `goto` throws. The page still renders, so it looks like a dead button rather
 * than a broken server. Only the dev server: a production build bundles one
 * client either way, and `vite preview` serves that build.
 */
function sameRootAsSvelteKit(): Plugin {
	return {
		name: 'voxrox:same-root-as-sveltekit',
		configureServer(server) {
			const kitRoot = normalizePath(process.cwd());
			const viteRoot = server.config.root;
			if (kitRoot === viteRoot) return;
			throw new Error(
				`SvelteKit took the project root as ${kitRoot}, Vite as ${viteRoot}. ` +
					'With the two apart every goto() fails in the dev server. ' +
					`Start it from ${viteRoot} instead (npm run tauri:dev does so itself).`
			);
		}
	};
}

export default defineConfig({
	plugins: [
		sameRootAsSvelteKit(),
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
