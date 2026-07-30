<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import QueryList from './QueryList.svelte';
	let { data, children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<QueryList
			queries={data.queries}
			onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)}
		/>
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
