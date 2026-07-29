<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import SessionNav from './SessionNav.svelte';
	let { children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<SessionNav onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)} />
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
