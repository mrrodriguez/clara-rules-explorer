<script lang="ts">
	import FactTypeSummary from './FactTypeSummary.svelte';

	let { data } = $props();
</script>

{#if data.factType}
	<FactTypeSummary factType={data.factType} />
{:else}
	<div class="alert alert-danger">
		<i class="bi bi-exclamation-triangle me-2"></i>
		Fact type not found — it is not part of the current rulebase, or the link is stale.
	</div>
{/if}
