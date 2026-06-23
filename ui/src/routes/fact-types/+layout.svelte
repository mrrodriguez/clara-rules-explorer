<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import FactTypeList from './FactTypeList.svelte';
	let { data, children } = $props();
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<FactTypeList factTypes={data.factTypes || []} />
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
