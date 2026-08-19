<script lang="ts">
	import type { TypeReference } from '$lib/types/api';
	import { factPath } from '$lib/utils';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		type: TypeReference;
	}

	let { type }: Props = $props();

	const href = $derived(resolve(factPath(type.id) as Pathname));
</script>

{#snippet name()}
	<QualifiedName fullName={type.name} size="sm" />
{/snippet}

{#if type.known}
	<a {href} class="text-decoration-none d-block" title={type.name}>
		{@render name()}
	</a>
{:else}
	<div class="text-muted fst-italic" title={type.name}>
		{@render name()}
	</div>
{/if}
