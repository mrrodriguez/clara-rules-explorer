<script lang="ts">
	import type { FactTypeSummary } from '$lib/types/api';
	import { page } from '$app/state';
	import GroupedFilterableNavList from '$lib/components/rulebase/nav/GroupedFilterableNavList.svelte';
	import { factPath, splitQualifiedName } from '$lib/utils';

	interface Props {
		factTypes: FactTypeSummary[];
		onFilteredOutChange?: (filteredOut: boolean) => void;
	}

	let { factTypes, onFilteredOutChange }: Props = $props();

	const groupKey = (ft: FactTypeSummary) => splitQualifiedName(ft.name).namespace;
	const activeId = $derived(page.params.id);
</script>

<GroupedFilterableNavList
	items={factTypes}
	{groupKey}
	hrefPrefix={factPath}
	activeColor="#0dcaf0"
	searchPlaceholder="Search fact types..."
	itemLabel="fact types"
	{activeId}
	{onFilteredOutChange}
/>
