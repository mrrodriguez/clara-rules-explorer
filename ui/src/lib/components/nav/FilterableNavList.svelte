<script lang="ts" generics="T extends { name: string }">
	import type { Snippet } from 'svelte';
	import ReferenceListItem from '$lib/components/nav/ReferenceListItem.svelte';
	import { toUrlId } from '$lib/utils';
	import { page } from '$app/state';

	interface Props {
		items: T[];
		hrefPrefix: (name: string) => string;
		activeColor?: string;
		searchPlaceholder?: string;
		// A function that returns an array of strings to search against for an item
		searchFields?: (item: T) => string[];
		// Optional snippet for content to the right of the name
		itemRight?: Snippet<[T]>;
		// Optional ID parameter name in the route (defaults to 'id')
		paramName?: string;
		// Optional border on the container
		border?: boolean;
	}

	let {
		items,
		hrefPrefix,
		activeColor = '#0d6efd',
		searchPlaceholder = 'Search...',
		searchFields = (item) => [item.name],
		itemRight,
		paramName = 'id',
		border = true
	}: Props = $props();

	let searchTerm = $state('');
	let filteredItems = $derived(
		searchTerm
			? items.filter((item) =>
					searchFields(item).some((field) => field.toLowerCase().includes(searchTerm.toLowerCase()))
				)
			: items
	);

	function isActive(name: string) {
		const targetId = toUrlId(name);
		const params = page.params as Record<string, string | undefined>;
		return params[paramName] === targetId;
	}
</script>

<div
	class="d-flex flex-column {border ? 'border-end' : ''}"
	style="height: calc(100vh - var(--navbar-height));"
>
	{#if searchPlaceholder}
		<div class="p-2 border-bottom bg-light">
			<div class="input-group input-group-sm">
				<span class="input-group-text bg-transparent border-end-0">
					<i class="bi bi-search text-muted"></i>
				</span>
				<input
					type="text"
					class="form-control border-start-0"
					placeholder={searchPlaceholder}
					bind:value={searchTerm}
				/>
			</div>
		</div>
	{/if}

	<div class="list-group list-group-flush flex-grow-1 overflow-auto">
		{#each filteredItems as item (item.name)}
			{#snippet badge()}
				{#if itemRight}
					{@render itemRight(item)}
				{/if}
			{/snippet}

			<ReferenceListItem
				href={hrefPrefix(item.name)}
				active={isActive(item.name)}
				title={item.name}
				fullName={item.name}
				{activeColor}
				{badge}
			/>
		{/each}

		{#if filteredItems.length === 0}
			<div class="p-4 text-center text-muted small fst-italic">
				{#if searchTerm}
					No matches found for "{searchTerm}"
				{:else}
					No items available
				{/if}
			</div>
		{/if}
	</div>
</div>

<style>
	/* Ensure the list group doesn't have borders that clash with our container */
	.list-group-flush :global(> .list-group-item:last-child) {
		border-bottom-width: 0;
	}
</style>
