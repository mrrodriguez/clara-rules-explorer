<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import FactTypeList from './FactTypeList.svelte';
	let { data, children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<FactTypeList
			factTypes={data.factTypes || []}
			onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)}
		/>
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
