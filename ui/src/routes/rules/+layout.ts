import { fetchRulesList } from '$lib/api';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async ({ fetch }) => {
	const rules = await fetchRulesList(fetch);
	return {
		rules
	};
};
