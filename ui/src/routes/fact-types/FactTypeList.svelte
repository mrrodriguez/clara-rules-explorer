<script lang="ts">
	import type { FactTypeSummary } from '$lib/types/api';
	import GroupedFilterableNavList from '$lib/components/nav/GroupedFilterableNavList.svelte';
	import { factPath, splitQualifiedName } from '$lib/utils';

	interface Props {
		factTypes: FactTypeSummary[];
	}

	let { factTypes }: Props = $props();

	const groupKey = (ft: FactTypeSummary) => splitQualifiedName(ft.name).namespace;
</script>

<GroupedFilterableNavList
	items={factTypes}
	{groupKey}
	hrefPrefix={factPath}
	activeColor="#0dcaf0"
	searchPlaceholder="Search fact types..."
/>
