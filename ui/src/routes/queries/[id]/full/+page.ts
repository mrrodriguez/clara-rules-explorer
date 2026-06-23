import { fetchQuery, fetchSessionQueryActivity } from '$lib/api';
import { getShortName, queryPath } from '$lib/utils';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ fetch, params }) => {
	const queryPromise = fetchQuery(params.id, fetch);
	const activityPromise = fetchSessionQueryActivity(params.id, fetch).catch((e: Error) => {
		if (e.message?.includes('404')) return { matches: [], 'inserted-facts': [] };
		throw e;
	});

	const [query, activity] = await Promise.all([queryPromise, activityPromise]);

	return {
		query,
		activity,
		breadcrumbs: [
			{ label: 'Queries', href: '/queries' },
			{ label: getShortName(query.name), href: queryPath(query.name) },
			{ label: 'Full Details' }
		]
	};
};
