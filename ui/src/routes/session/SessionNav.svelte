<script lang="ts">
	import type { SessionFactTypeInfo } from '$lib/types/api';
	import { toRouteId, splitQualifiedName } from '$lib/utils';
	import GroupedFilterableNavList from '$lib/components/rulebase/nav/GroupedFilterableNavList.svelte';
	import { page } from '$app/state';

	let { onFilteredOutChange }: { onFilteredOutChange?: (filteredOut: boolean) => void } = $props();

	const factTypes = $derived<SessionFactTypeInfo[]>(
		page.data.sessionFactTypes?.types
			? [...page.data.sessionFactTypes.types].sort((a, b) => a.name.localeCompare(b.name))
			: []
	);

	function sessionPath(name: string) {
		return `/session/fact-types/${encodeURIComponent(toRouteId(name))}`;
	}

	function isTypeActive(typeName: string) {
		return page.params.typeName === toRouteId(typeName);
	}

	const groupKey = (ft: SessionFactTypeInfo) => splitQualifiedName(ft.name).namespace;
	const activeId = $derived(page.params.typeName);
</script>

{#snippet itemRight(type: SessionFactTypeInfo)}
	<span
		class="badge rounded-pill {isTypeActive(type.name)
			? 'bg-white text-primary'
			: 'bg-secondary bg-opacity-10 text-muted'}"
	>
		{type.count}
	</span>
{/snippet}

<GroupedFilterableNavList
	items={factTypes}
	{groupKey}
	hrefPrefix={sessionPath}
	activeColor="#0d6efd"
	searchPlaceholder="Search session facts..."
	itemLabel="types"
	{itemRight}
	{activeId}
	{onFilteredOutChange}
/>
