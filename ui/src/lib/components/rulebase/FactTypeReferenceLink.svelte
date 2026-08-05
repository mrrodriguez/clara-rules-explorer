<script lang="ts">
	import type { TypeReference } from '$lib/types/api';
	import { factPath } from '$lib/utils';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import ReferenceListItem from '$lib/components/rulebase/nav/ReferenceListItem.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		type: TypeReference;
		active?: boolean;
		/**
		 * Compact inline presentation (e.g. inside a popover): known types
		 * render as a bare link, ghosts as muted text — no list-group row.
		 */
		compact?: boolean;
	}

	let { type, active = false, compact = false }: Props = $props();

	const href = $derived(resolve(factPath(type.id) as Pathname));
</script>

{#if compact}
	{#if type.known}
		<a {href} class="text-decoration-none d-block" title={type.name}>
			<QualifiedName fullName={type.name} size="sm" />
		</a>
	{:else}
		<div class="text-muted fst-italic" title={type.name}>
			<QualifiedName fullName={type.name} size="sm" />
		</div>
	{/if}
{:else if type.known}
	<ReferenceListItem
		href={factPath(type.id)}
		title={type.name}
		fullName={type.name}
		activeColor="#0dcaf0"
		{active}
	/>
{:else}
	<ReferenceListItem title={type.name} fullName={type.name} muted />
{/if}
