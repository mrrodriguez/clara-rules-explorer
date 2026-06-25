import autoAdapter from '@sveltejs/adapter-auto';
import staticAdapter from '@sveltejs/adapter-static';

const isDemo = process.env.VITE_DEMO_MODE === 'true';

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
			base: process.env.BASE_PATH || ''
		},
		prerender: {
			handleUnseenRoutes: 'ignore'
		}
	}
};

export default config;
