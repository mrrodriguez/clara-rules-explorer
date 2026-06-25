<script lang="ts">
	import FactDetail from './FactDetail.svelte';

	let { data } = $props();
	const fact = $derived(data.fact);
</script>

<div class="p-4">
	<FactDetail {fact} />
</div>
