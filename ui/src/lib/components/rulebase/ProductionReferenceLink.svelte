<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import ReferenceListItem from '$lib/components/rulebase/nav/ReferenceListItem.svelte';
	import Badge from '$lib/components/ui/Badge.svelte';
	import { rulePath, queryPath } from '$lib/utils';

	interface Props {
		ref: ProductionReference;
		fullView?: boolean;
		active?: boolean;
	}

	let { ref, fullView = false, active = false }: Props = $props();

	const path = $derived(
		ref.type === 'rule' ? rulePath(ref.id, fullView) : queryPath(ref.id, fullView)
	);

	const activeColor = $derived(ref.type === 'rule' ? '#0d6efd' : '#198754');

	const matches = $derived(ref.match ?? []);
</script>

{#snippet badge()}
	<RulebaseComponentTypeBadge type={ref.type} />
{/snippet}

{#snippet matchRows()}
	{#if matches.length > 0}
		<div class="d-flex flex-column gap-1">
			{#each matches as m (m['producer-type'].name + '>' + m['consumer-type'].name)}
				<div class="d-flex align-items-center gap-1" title={m['producer-type'].name}>
					<i class="bi bi-link-45deg text-muted"></i>
					<span class="font-monospace text-truncate small">{m['producer-type'].name}</span>
					<span class="text-muted small flex-shrink-0">→ satisfies</span>
					<span class="font-monospace text-truncate small">{m['consumer-type'].name}</span>
					{#if m.via === 'retract'}
						<Badge variant="secondary" size="sm" class="flex-shrink-0">retract</Badge>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
{/snippet}

<ReferenceListItem
	href={path}
	title={ref.name}
	fullName={ref.name}
	{activeColor}
	{badge}
	{matchRows}
	{active}
/>
