<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import RuleList from './RuleList.svelte';
	let { data, children } = $props();
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<RuleList rules={data.rules} />
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
