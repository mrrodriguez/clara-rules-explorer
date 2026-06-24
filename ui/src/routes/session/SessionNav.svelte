<script lang="ts">
	import type { SessionFactTypeInfo } from '$lib/types/api';
	import { toUrlId } from '$lib/utils';
	import FilterableNavList from '$lib/components/nav/FilterableNavList.svelte';
	import { page } from '$app/state';

	const factTypes = $derived<SessionFactTypeInfo[]>(
		page.data.sessionFactTypes?.types
			? [...page.data.sessionFactTypes.types].sort((a, b) => a.name.localeCompare(b.name))
			: []
	);

	function sessionPath(name: string) {
		return `/session/fact-types/${toUrlId(name)}`;
	}

	function isTypeActive(typeName: string) {
		return page.params.typeName === toUrlId(typeName);
	}
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

<FilterableNavList
	items={factTypes}
	hrefPrefix={sessionPath}
	activeColor="#0d6efd"
	searchPlaceholder="Search session facts..."
	{itemRight}
	paramName="typeName"
/>
