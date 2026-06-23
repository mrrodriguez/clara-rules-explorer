<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { ProductionReference } from '$lib/types/api';
	import ProductionReferenceLink from '$lib/components/rulebase/ProductionReferenceLink.svelte';
	import FactTypeReferenceLink from '$lib/components/rulebase/FactTypeReferenceLink.svelte';

	interface Props {
		title: string;
		icon?: string;
		items?: (ProductionReference | string)[];
		fullView?: boolean;
		class?: string;
		children?: Snippet;
	}

	let {
		title,
		icon,
		items = [],
		fullView = false,
		class: className = '',
		children
	}: Props = $props();
</script>

<div class="mb-3 {className}">
	<h6 class="text-muted text-uppercase small fw-bold d-flex align-items-center mb-2">
		{#if icon}
			<i class="bi {icon} me-2 opacity-75"></i>
		{/if}
		{title}
	</h6>

	{#if items.length > 0}
		<div class="list-group list-group-flush border rounded shadow-sm overflow-hidden">
			{#each items as item (typeof item === 'string' ? item : item.name)}
				{#if typeof item === 'string'}
					<FactTypeReferenceLink type={item} />
				{:else}
					<ProductionReferenceLink ref={item} {fullView} />
				{/if}
			{/each}
		</div>
	{:else if children}
		{@render children()}
	{:else}
		<p class="text-muted small fst-italic ps-2 mb-0">None</p>
	{/if}
</div>

<style>
	.list-group-item {
		transition: background-color 0.2s;
	}
	.list-group-item:hover {
		background-color: rgba(0, 0, 0, 0.02);
	}
</style>
