import { fetchFactType } from '$lib/api';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ params, fetch }) => {
	const factType = await fetchFactType(params.id, fetch);
	return {
		id: params.id,
		factType
	};
};
