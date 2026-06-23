import { fetchQuery } from '$lib/api';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async ({ params, fetch }) => {
	const query = await fetchQuery(params.id, fetch);

	return {
		query
	};
};
