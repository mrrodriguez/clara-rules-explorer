import { fetchFactTypesList } from '$lib/api';
import type { LayoutLoad } from './$types';

export const load: LayoutLoad = async ({ fetch }) => {
	const factTypes = await fetchFactTypesList(fetch);
	return {
		factTypes
	};
};
