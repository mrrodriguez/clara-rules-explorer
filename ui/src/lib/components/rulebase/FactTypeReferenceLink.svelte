<script lang="ts">
	import type { TypeReference } from '$lib/types/api';
	import { factPath } from '$lib/utils';
	import ReferenceListItem from '$lib/components/rulebase/nav/ReferenceListItem.svelte';

	interface Props {
		type: TypeReference;
		active?: boolean;
	}

	let { type, active = false }: Props = $props();
</script>

{#if type.known}
	<ReferenceListItem
		href={factPath(type.id)}
		title={type.name}
		fullName={type.name}
		activeColor="#0dcaf0"
		{active}
	/>
{:else}
	<div class="list-group-item d-flex align-items-center px-3 py-2" title={type.name}>
		<div class="text-muted fst-italic">{type.name}</div>
	</div>
{/if}
