import { fetchRule } from '$lib/api';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async ({ params, fetch }) => {
	const rule = await fetchRule(params.id, fetch);

	return {
		rule
	};
};
