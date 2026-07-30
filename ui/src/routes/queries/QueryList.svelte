<script lang="ts">
	import type { QueryListItem } from '$lib/types/api';
	import { page } from '$app/state';
	import GroupedFilterableNavList from '$lib/components/rulebase/nav/GroupedFilterableNavList.svelte';
	import { queryPath } from '$lib/utils';

	interface Props {
		queries: QueryListItem[];
		onFilteredOutChange?: (filteredOut: boolean) => void;
	}

	let { queries, onFilteredOutChange }: Props = $props();

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
	{onFilteredOutChange}
/>
