<script lang="ts">
	import type { QueryListItem } from '$lib/types/api';
	import GroupedFilterableNavList from '$lib/components/nav/GroupedFilterableNavList.svelte';
	import { queryPath, splitQualifiedName } from '$lib/utils';

	interface Props {
		queries: QueryListItem[];
	}

	let { queries }: Props = $props();

	// The backend may return empty ns for queries — fall back to extracting
	// the namespace portion from the fully-qualified name.
	const groupKey = (query: QueryListItem) => query.ns || splitQualifiedName(query.name).namespace;
</script>

<GroupedFilterableNavList
	items={queries}
	{groupKey}
	hrefPrefix={queryPath}
	activeColor="#198754"
	searchPlaceholder="Search queries..."
	itemLabel="queries"
/>
