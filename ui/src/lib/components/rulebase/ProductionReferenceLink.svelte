<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import ReferenceListItem from '$lib/components/nav/ReferenceListItem.svelte';
	import { rulePath, queryPath } from '$lib/utils';

	interface Props {
		ref: ProductionReference;
		fullView?: boolean;
		active?: boolean;
	}

	let { ref, fullView = false, active = false }: Props = $props();

	const path = $derived(
		ref.type === 'rule' ? rulePath(ref.name, fullView) : queryPath(ref.name, fullView)
	);

	const activeColor = $derived(ref.type === 'rule' ? '#0d6efd' : '#198754');
</script>

{#snippet badge()}
	<RulebaseComponentTypeBadge type={ref.type} />
{/snippet}

<ReferenceListItem
	href={path}
	title={ref.name}
	fullName={ref.name}
	{activeColor}
	{badge}
	{active}
/>
