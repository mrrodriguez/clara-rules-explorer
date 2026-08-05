import { env } from '$env/dynamic/private';
import type { Handle } from '@sveltejs/kit';

/**
 * When API_PROXY_TARGET is set (e2e test runs with adapter-node), proxy
 * /v1 requests to the live explorer backend.  In production and dev mode
 * the env var is absent, making this a trivial no-op.
 *
 * This hook is required because adapter-node does SSR: SvelteKit server-side
 * fetch() calls bypass the HTTP layer and cannot be intercepted by an
 * external proxy wrapper.  In dev mode Vite's proxy handles /v1 before
 * requests reach the SvelteKit server, so the hook never sees them.
 */
export const handle: Handle = async ({ event, resolve }) => {
	const proxyTarget = env.API_PROXY_TARGET;
	if (!proxyTarget || !event.url.pathname.startsWith('/v1/')) {
		return resolve(event);
	}

	const upstream = new URL(event.url.pathname + event.url.search, proxyTarget);
	const headers = new Headers(event.request.headers);
	headers.delete('host');
	headers.delete('connection');

	const upstreamResp = await fetch(upstream, {
		method: event.request.method,
		headers,
		body:
			event.request.method !== 'GET' && event.request.method !== 'HEAD'
				? await event.request.text()
				: undefined
	});

	return new Response(upstreamResp.body, {
		status: upstreamResp.status,
		statusText: upstreamResp.statusText,
		headers: upstreamResp.headers
	});
};
