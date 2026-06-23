<script lang="ts">
	import type { SessionProductionActivityResponse } from '$lib/types/api';
	import SessionActivityBlock from '$lib/components/rulebase/SessionActivityBlock.svelte';
	import type { ActivityCategory } from '$lib/components/rulebase/SessionActivityBlock.svelte';

	interface Props {
		activity?: SessionProductionActivityResponse;
	}

	let { activity }: Props = $props();

	const categories = $derived<ActivityCategory[]>(
		activity
			? (
					[
						{
							title: 'Active Matches',
							type: 'facts',
							items: activity.matches ?? []
						},
						{
							title: 'Inserted Facts',
							type: 'facts',
							items: activity['inserted-facts'] ?? []
						}
					] as ActivityCategory[]
				).filter((c) => c.items.length > 0)
			: []
	);
</script>

<SessionActivityBlock {categories} emptyText="No session activity recorded for this production." />
