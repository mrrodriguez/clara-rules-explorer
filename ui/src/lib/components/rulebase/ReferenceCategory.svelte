<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { ProductionReference, TypeReference } from '$lib/types/api';
	import ProductionReferenceLink from '$lib/components/rulebase/ProductionReferenceLink.svelte';
	import FactTypeReferenceLink from '$lib/components/rulebase/FactTypeReferenceLink.svelte';

	type ReferenceItem = ProductionReference | TypeReference;

	interface Props {
		title: string;
		/**
		 * What kind of reference each `items` entry is.  Explicit so the
		 * rendering choice is caller-declared rather than inferred at
		 * runtime from item shape (a fragile check — `SessionFact` also
		 * carries a `type` key).
		 */
		itemKind?: 'production' | 'type';
		icon?: string;
		items?: ReferenceItem[];
		fullView?: boolean;
		class?: string;
		maxVisibleItems?: number;
		children?: Snippet;
	}

	let {
		title,
		itemKind = 'production',
		icon,
		items = [],
		fullView = false,
		class: className = '',
		maxVisibleItems = 6,
		children
	}: Props = $props();

	const scrollable = $derived(items.length >= maxVisibleItems);

	// Per-item height: py-2 (1rem vertical) + QualifiedName two-line text (~2rem) = ~3rem.
	// Add 0.25rem buffer so the last visible item renders nearly fully.
	const scrollMaxHeight = $derived(`${maxVisibleItems * 3 + 0.25}rem`);
</script>

<div class="mb-3 {className}">
	<h6 class="text-muted text-uppercase small fw-bold d-flex align-items-center mb-2">
		{#if icon}
			<i class="bi {icon} me-2 opacity-75"></i>
		{/if}
		{title}
		{#if items.length > 0}
			<span class="badge bg-secondary-subtle text-secondary border border-secondary-subtle ms-2"
				>{items.length}</span
			>
		{/if}
	</h6>

	{#if items.length > 0}
		<div
			class="list-group list-group-flush border rounded shadow-sm"
			style={scrollable ? `max-height: ${scrollMaxHeight}; overflow-y: auto;` : ''}
		>
			{#each items as item (item.id)}
				{#if itemKind === 'type'}
					<FactTypeReferenceLink type={item as TypeReference} />
				{:else}
					<ProductionReferenceLink ref={item as ProductionReference} {fullView} />
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
