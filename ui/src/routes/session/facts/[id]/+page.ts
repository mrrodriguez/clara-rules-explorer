import { fetchSessionFactDetail } from '$lib/api';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ params, fetch }) => {
	const fact = await fetchSessionFactDetail(params.id, fetch);
	return {
		fact
	};
};
