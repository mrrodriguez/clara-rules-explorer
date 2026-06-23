<script lang="ts">
	import AnalysisSummaryListLayout from '$lib/components/rulebase/AnalysisSummaryListLayout.svelte';
	import SessionNav from './SessionNav.svelte';
	let { children } = $props();
</script>

<AnalysisSummaryListLayout>
	{#snippet sidebar()}
		<SessionNav />
	{/snippet}
	{@render children()}
</AnalysisSummaryListLayout>
