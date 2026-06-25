import { fetchSessionFactTypeInstances } from '$lib/api';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ params, fetch }) => {
	const detail = await fetchSessionFactTypeInstances(params.typeName, fetch);
	return {
		detail
	};
};
