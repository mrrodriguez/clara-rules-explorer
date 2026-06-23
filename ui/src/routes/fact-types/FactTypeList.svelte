<script lang="ts">
	import type { FactTypeSummary } from '$lib/types/api';
	import FilterableNavList from '$lib/components/nav/FilterableNavList.svelte';
	import { factPath } from '$lib/utils';

	interface Props {
		factTypes: FactTypeSummary[];
	}

	let { factTypes }: Props = $props();
</script>

<FilterableNavList
	items={factTypes}
	hrefPrefix={factPath}
	activeColor="#0dcaf0"
	searchPlaceholder="Search fact types..."
/>
