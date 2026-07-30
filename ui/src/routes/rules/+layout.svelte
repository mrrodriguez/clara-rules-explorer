<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import RuleList from './RuleList.svelte';
	let { data, children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<RuleList
			rules={data.rules}
			onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)}
		/>
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
