<script lang="ts">
	import { fromRouteId } from '$lib/utils';
	import FactTypeSummary from './FactTypeSummary.svelte';

	let { data } = $props();

	let factTypeName = $derived(fromRouteId(data.id));
</script>

{#if data.factType}
	<FactTypeSummary factType={data.factType} />
{:else}
	<div class="alert alert-danger">
		Fact type "{factTypeName}" not found.
	</div>
{/if}
