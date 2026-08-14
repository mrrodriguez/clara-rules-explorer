<script lang="ts">
	import type { SessionFact, FactMatch } from '$lib/types/api';
	import SessionActivityRow from '$lib/components/rulebase/SessionActivityRow.svelte';
	import { stableKeys } from '$lib/keys';

	interface Props {
		items: SessionFact[] | FactMatch[];
		type: 'facts' | 'matches';
	}

	let { items, type }: Props = $props();

	// Fact rows key on the fact id; match rows key on their wrapped fact's id.
	// Unique in both by construction, and stableKeys keeps a server-side
	// regression from aborting hydration on a duplicate.
	function rowId(item: SessionFact | FactMatch): number {
		return 'fact' in item ? item.fact.id : item.id;
	}

	const keys = $derived(stableKeys<SessionFact | FactMatch>(items, rowId));
</script>

<div class="session-activity-list list-group shadow-sm border rounded overflow-hidden">
	{#each items as item, i (keys[i])}
		<SessionActivityRow {item} {type} />
	{/each}
</div>

<style>
	/* Remove redundant bottom border from the last item */
	:global(.session-activity-list .session-activity-row:last-child) {
		border-bottom: none !important;
	}
</style>
