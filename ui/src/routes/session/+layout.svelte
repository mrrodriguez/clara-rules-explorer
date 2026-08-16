<script lang="ts">
	import SummaryListLayout from '$lib/components/rulebase/SummaryListLayout.svelte';
	import { setListFilterState, type ListFilterState } from '$lib/state/listFilterContext.svelte';
	import SessionNav from './SessionNav.svelte';
	let { children } = $props();

	let filterState: ListFilterState = $state({ activeItemFilteredOut: false });
	setListFilterState(filterState);
</script>

<SummaryListLayout>
	{#snippet sidebar()}
		<SessionNav onFilteredOutChange={(v) => (filterState.activeItemFilteredOut = v)} />
	{/snippet}
	{@render children()}
</SummaryListLayout>
