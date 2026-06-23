<script lang="ts">
	import QuerySummary from './QuerySummary.svelte';
	let { data } = $props();
</script>

<QuerySummary query={data.query} />
