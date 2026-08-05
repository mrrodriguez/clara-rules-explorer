<script lang="ts">
	import type { SessionFactTypeInfo } from '$lib/types/api';
	import GroupedFilterableNavList from '$lib/components/rulebase/nav/GroupedFilterableNavList.svelte';
	import { page } from '$app/state';

	let { onFilteredOutChange }: { onFilteredOutChange?: (filteredOut: boolean) => void } = $props();

	const factTypes = $derived<SessionFactTypeInfo[]>(
		page.data.sessionFactTypes?.types
			? [...page.data.sessionFactTypes.types].sort((a, b) => a.name.localeCompare(b.name))
			: []
	);

	function sessionPath(ft: SessionFactTypeInfo) {
		return `/session/fact-types/${ft.id}`;
	}

	function isTypeActive(type: SessionFactTypeInfo) {
		return page.params.id === type.id;
	}

	const groupKey = (ft: SessionFactTypeInfo) => ft.ns ?? '';
	const activeId = $derived(page.params.id);
</script>

{#snippet itemRight(type: SessionFactTypeInfo)}
	<span
		class="badge rounded-pill {isTypeActive(type)
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
