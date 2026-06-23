<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';

	interface Props {
		items: ProductionReference[] | undefined;
		direction: 'upstream' | 'downstream';
		fullView?: boolean;
	}

	let { items = [], direction, fullView = false }: Props = $props();
</script>

<ProductionReferenceCategory
	title={direction}
	icon="bi-arrow-{direction === 'upstream' ? 'up' : 'down'}-circle"
	{items}
	{fullView}
/>
