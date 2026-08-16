<script lang="ts">
	import SummaryListLayout from '$lib/components/rulebase/SummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import RuleList from './RuleList.svelte';
	let { data, children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<SummaryListLayout>
	{#snippet sidebar()}
		<RuleList
			rules={data.rules}
			onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)}
		/>
	{/snippet}
	{@render children()}
</SummaryListLayout>
