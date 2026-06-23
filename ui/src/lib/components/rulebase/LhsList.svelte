<script lang="ts">
	import type { LhsElement } from '$lib/types/api';
	import LhsCondition from '$lib/components/rulebase/LhsCondition.svelte';

	interface Props {
		lhs: LhsElement[];
	}

	let { lhs }: Props = $props();
</script>

<div style="overflow-x: auto;">
	{#each lhs as condition, i (i)}
		<LhsCondition {condition} />
		{#if i < lhs.length - 1}
			<hr class="my-2 opacity-25" />
		{/if}
	{/each}
</div>
