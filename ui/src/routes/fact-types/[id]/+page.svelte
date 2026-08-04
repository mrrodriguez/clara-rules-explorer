<script lang="ts">
	import FactTypeSummary from './FactTypeSummary.svelte';

	let { data } = $props();
</script>

{#if data.factType}
	<FactTypeSummary factType={data.factType} />
{:else}
	<div class="alert alert-danger">Fact type "{data.id}" not found.</div>
{/if}
