<script lang="ts">
	import type { Snippet } from 'svelte';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import CopyableTitle from '$lib/components/ui/CopyableTitle.svelte';
	import LocateInListButton from '$lib/components/ui/LocateInListButton.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		type: 'rule' | 'query' | 'fact';
		name: string;
		/** Server-issued route id — passed to the locate-in-list button. */
		id: string;
		fullView?: boolean;
		href: string;
		children?: Snippet;
	}

	let { type, name, id, fullView = false, href, children }: Props = $props();
	let color = $derived(type === 'rule' ? 'primary' : type === 'query' ? 'success' : 'info');

	const resolvedHref = $derived(resolve(href as Pathname));
</script>

<div class="card-header bg-white py-2">
	<!-- Row 1: Name + Full View button -->
	<div class="d-flex justify-content-between align-items-center">
		<CopyableTitle fullName={name} size="lg" class="text-{color} flex-grow-1" />
		<div class="d-flex gap-1 flex-shrink-0 ms-2">
			{#if !fullView}
				<LocateInListButton {id} />
			{/if}
			{#if !fullView && type !== 'fact'}
				<a href={resolvedHref} class="btn btn-outline-{color} btn-sm">
					<i class="bi bi-arrows-fullscreen me-1"></i> Full View
				</a>
			{/if}
		</div>
	</div>
	<!-- Row 2: Type badge + other badges -->
	{#if type !== 'fact'}
		<div class="d-flex align-items-center flex-wrap gap-1 mt-1">
			<RulebaseComponentTypeBadge {type} />
			{@render children?.()}
		</div>
	{/if}
</div>
