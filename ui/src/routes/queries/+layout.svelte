<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import QueryList from './QueryList.svelte';
	let { data, children } = $props();
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<QueryList queries={data.queries} />
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
