<script lang="ts">
	import type { QueryListItem } from '$lib/types/api';
	import FilterableNavList from '$lib/components/nav/FilterableNavList.svelte';
	import { queryPath } from '$lib/utils';

	interface Props {
		queries: QueryListItem[];
	}

	let { queries }: Props = $props();
</script>

<FilterableNavList
	items={queries}
	hrefPrefix={queryPath}
	activeColor="#198754"
	searchPlaceholder="Search queries..."
/>
