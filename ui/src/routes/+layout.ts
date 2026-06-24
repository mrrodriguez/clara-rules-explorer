import { fetchRulebaseSummary, fetchSessionFactTypes } from '$lib/api';
import type { LayoutLoad } from './$types';

export const prerender = import.meta.env.VITE_DEMO_MODE === 'true' ? true : false;
export const ssr = import.meta.env.VITE_DEMO_MODE === 'true' ? false : true;

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
