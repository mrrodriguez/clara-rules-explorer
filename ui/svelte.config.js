import autoAdapter from '@sveltejs/adapter-auto';
import nodeAdapter from '@sveltejs/adapter-node';
import staticAdapter from '@sveltejs/adapter-static';

const isDemo = process.env.VITE_DEMO_MODE === 'true';
const isE2E = process.env.E2E_BUILD === 'true';

// BASE_PATH must be a root-relative path (e.g. /clara-rules-explorer) for
// the static demo build; anything else falls back to the site root.
const basePath = process.env.BASE_PATH || '';
const kitBase = /** @type {'' | `/${string}`} */ (basePath.startsWith('/') ? basePath : '');

function chooseAdapter() {
	if (isDemo) {
		// Static SPA — serves /demo-data JSON files, no backend needed.
		return staticAdapter({
			pages: 'build',
			assets: 'build',
			fallback: '404.html',
			precompress: false,
			strict: true
		});
	}
	if (isE2E) {
		// Production-like Node server — SSR on, proxies /v1 to a live backend.
		return nodeAdapter({ out: 'build' });
	}
	return autoAdapter();
}

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		adapter: chooseAdapter(),
		paths: {
			base: kitBase
		},
		prerender: {
			handleUnseenRoutes: 'ignore'
		}
	}
};

export default config;
