import { fetchQueriesList } from '$lib/api';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async ({ fetch }) => {
	const queries = await fetchQueriesList(fetch);
	return {
		queries
	};
};
