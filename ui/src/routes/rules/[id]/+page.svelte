<script lang="ts">
	import RuleSummary from './RuleSummary.svelte';
	let { data } = $props();
</script>

<RuleSummary rule={data.rule} />
