<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import SessionActivityRow from '$lib/components/rulebase/SessionActivityRow.svelte';

	interface Props {
		items: SessionFact[];
		type: 'facts';
	}

	let { items, type }: Props = $props();
</script>

<div class="session-activity-list list-group shadow-sm border rounded overflow-hidden">
	{#each items as item (item.id)}
		<SessionActivityRow {item} {type} />
	{/each}
</div>

<style>
	/* Remove redundant bottom border from the last item */
	:global(.session-activity-list .session-activity-row:last-child) {
		border-bottom: none !important;
	}
</style>
