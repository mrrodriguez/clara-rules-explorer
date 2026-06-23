<script lang="ts">
	import type { Snippet } from 'svelte';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		type: 'rule' | 'query' | 'fact';
		name: string;
		fullView?: boolean;
		href: string;
		children?: Snippet;
	}

	let { type, name, fullView = false, href, children }: Props = $props();
	let color = $derived(type === 'rule' ? 'primary' : type === 'query' ? 'success' : 'info');

	const resolvedHref = $derived(resolve(href as Pathname));
</script>

<div class="card-header bg-white d-flex justify-content-between align-items-center py-2">
	<div class="d-flex align-items-center min-width-0 flex-grow-1">
		{#if type !== 'fact'}
			<RulebaseComponentTypeBadge {type} />
			<QualifiedName fullName={name} size="lg" class="ms-3 text-{color}" />
		{:else}
			<QualifiedName fullName={name} size="lg" class="text-{color}" />
		{/if}

		{@render children?.()}
	</div>
	{#if !fullView && type !== 'fact'}
		<a href={resolvedHref} class="btn btn-outline-{color} btn-sm">
			<i class="bi bi-arrows-fullscreen me-1"></i> Full View
		</a>
	{/if}
</div>
