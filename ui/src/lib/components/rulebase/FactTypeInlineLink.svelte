<script lang="ts">
	import type { TypeReference } from '$lib/types/api';
	import { factPath } from '$lib/utils';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import OpenReferenceLink from '$lib/components/rulebase/nav/OpenReferenceLink.svelte';
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
	<div class="d-flex justify-content-between align-items-center gap-2 w-100 min-width-0">
		<a {href} class="text-decoration-none flex-grow-1 min-width-0 d-block" title={type.name}>
			{@render name()}
		</a>
		<div class="actions-col d-flex align-items-center flex-shrink-0">
			<OpenReferenceLink path={factPath(type.id)} name={type.name} />
		</div>
	</div>
{:else}
	<div class="text-muted fst-italic" title={type.name}>
		{@render name()}
	</div>
{/if}

<style>
	.min-width-0 {
		min-width: 0;
	}
	.actions-col {
		flex: 0 0 auto;
	}
</style>
