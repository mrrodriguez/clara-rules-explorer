<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import ReferenceCategory from '$lib/components/rulebase/ReferenceCategory.svelte';

	interface Props {
		items: ProductionReference[] | undefined;
		direction: 'upstream' | 'downstream';
		fullView?: boolean;
	}

	let { items = [], direction, fullView = false }: Props = $props();
</script>

<ReferenceCategory
	title={direction}
	icon="bi-arrow-{direction === 'upstream' ? 'up' : 'down'}-circle"
	{items}
	{fullView}
/>
