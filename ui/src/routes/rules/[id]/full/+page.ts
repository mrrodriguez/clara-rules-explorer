import { fetchRule, fetchSessionRuleActivity } from '$lib/api';
import { getShortName, rulePath } from '$lib/utils';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ fetch, params }) => {
	const rulePromise = fetchRule(params.id, fetch);
	const activityPromise = fetchSessionRuleActivity(params.id, fetch).catch((e: Error) => {
		if (e.message?.includes('404')) return { matches: [], 'inserted-facts': [] };
		throw e;
	});

	const [rule, activity] = await Promise.all([rulePromise, activityPromise]);

	return {
		rule,
		activity,
		breadcrumbs: [
			{ label: 'Rules', href: '/rules' },
			{ label: getShortName(rule.name), href: rulePath(rule.name) },
			{ label: 'Full Details' }
		]
	};
};
