import autoAdapter from '@sveltejs/adapter-auto';
import staticAdapter from '@sveltejs/adapter-static';

const isDemo = process.env.VITE_DEMO_MODE === 'true';

// BASE_PATH must be a root-relative path (e.g. /clara-rules-explorer) for
// the static demo build; anything else falls back to the site root.
const basePath = process.env.BASE_PATH || '';
const kitBase = /** @type {'' | `/${string}`} */ (basePath.startsWith('/') ? basePath : '');

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		adapter: isDemo
			? staticAdapter({
					pages: 'build',
					assets: 'build',
					fallback: '404.html',
					precompress: false,
					strict: true
				})
			: autoAdapter(),
		paths: {
			base: kitBase
		},
		prerender: {
			handleUnseenRoutes: 'ignore'
		}
	}
};

export default config;
