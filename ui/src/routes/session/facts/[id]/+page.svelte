<script lang="ts">
	import FactDetail from './FactDetail.svelte';

	let { data } = $props();
	const fact = $derived(data.fact);
</script>

<div class="p-4">
	{#if fact}
		<FactDetail {fact} />
	{:else}
		<div class="alert alert-danger m-3">
			<i class="bi bi-exclamation-triangle-fill me-2"></i>
			Fact not found.
		</div>
	{/if}
</div>
