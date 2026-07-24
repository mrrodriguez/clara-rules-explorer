<script lang="ts">
	import type { QueryListItem } from '$lib/types/api';
	import { page } from '$app/state';
	import GroupedFilterableNavList from '$lib/components/nav/GroupedFilterableNavList.svelte';
	import { queryPath } from '$lib/utils';

	interface Props {
		queries: QueryListItem[];
	}

	let { queries }: Props = $props();

	const groupKey = (query: QueryListItem) => query.ns;
	const activeId = $derived(page.params.id);
</script>

<GroupedFilterableNavList
	items={queries}
	{groupKey}
	hrefPrefix={queryPath}
	activeColor="#198754"
	searchPlaceholder="Search queries..."
	itemLabel="queries"
	{activeId}
/>
