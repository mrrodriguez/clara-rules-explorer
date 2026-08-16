<script lang="ts">
	import SummaryListLayout from '$lib/components/rulebase/SummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import QueryList from './QueryList.svelte';
	let { data, children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<SummaryListLayout>
	{#snippet sidebar()}
		<QueryList
			queries={data.queries}
			onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)}
		/>
	{/snippet}
	{@render children()}
</SummaryListLayout>
