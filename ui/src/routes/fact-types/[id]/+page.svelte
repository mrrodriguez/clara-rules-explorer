<script lang="ts">
	import { fromUrlId } from '$lib/utils';
	import FactTypeSummary from './FactTypeSummary.svelte';

	let { data } = $props();

	let factTypeName = $derived(fromUrlId(data.id));
</script>

{#if data.factType}
	<FactTypeSummary factType={data.factType} />
{:else}
	<div class="alert alert-danger">
		Fact type "{factTypeName}" not found.
	</div>
{/if}
