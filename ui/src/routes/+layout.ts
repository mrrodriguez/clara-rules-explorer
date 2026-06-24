import { fetchRulebaseSummary, fetchSessionFactTypes } from '$lib/api';
import type { LayoutLoad } from './$types';

export const prerender = true;
export const ssr = false;

export const load: LayoutLoad = async ({ fetch }) => {
	const [summary, sessionFactTypes] = await Promise.all([
		fetchRulebaseSummary(fetch),
		fetchSessionFactTypes(fetch)
	]);

	return {
		summary,
		sessionFactTypes
	};
};
