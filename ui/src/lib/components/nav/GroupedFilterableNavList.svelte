<script lang="ts" generics="T extends { name: string }">
	import { SvelteSet } from 'svelte/reactivity';
	import type { Snippet } from 'svelte';
	import ReferenceListItem from '$lib/components/nav/ReferenceListItem.svelte';
	import { toUrlId } from '$lib/utils';
	import { page } from '$app/state';

	interface Props {
		items: T[];
		/** Extract the namespace/group key from an item. Empty string = ungrouped. */
		groupKey: (item: T) => string;
		hrefPrefix: (name: string) => string;
		activeColor?: string;
		searchPlaceholder?: string;
		/** Fields to search against (default: [item.name]) */
		searchFields?: (item: T) => string[];
		/** Optional snippet for content to the right of the name */
		itemRight?: Snippet<[T]>;
		/** Route parameter name for active detection (default: 'id') */
		paramName?: string;
		/** Items to show per page (default: 100) */
		pageSize?: number;
		/** Whether to show a border on the container (default: true) */
		border?: boolean;
	}

	let {
		items,
		groupKey,
		hrefPrefix,
		activeColor = '#0d6efd',
		searchPlaceholder = 'Search...',
		searchFields = (item: T) => [item.name],
		itemRight,
		paramName = 'id',
		pageSize = 100,
		border = true
	}: Props = $props();

	// ── State ────────────────────────────────────────────────────────────────

	let searchTerm = $state('');
	let selectedNamespace = $state('');
	// SvelteSet mutation is reactive but the template reference requires
	// $state for re-render on reassignment.
	// eslint-disable-next-line svelte/no-unnecessary-state-wrap
	let expandedGroups = $state(new SvelteSet<string>());
	const DEFAULT_PAGE_SIZE = 100;

	let groupVisibleCounts = $state<Record<string, number>>({});
	// pageSize is captured once as a reactive prop — the $effect below
	// syncs flatVisibleCount to the actual pageSize on mount / item changes.
	let flatVisibleCount = $state(DEFAULT_PAGE_SIZE);

	// ── Derived view model ───────────────────────────────────────────────────

	const view = $derived.by(() => {
		// Determine namespaces in original order of first appearance
		const nsOrder: string[] = [];
		const seenNs = new SvelteSet<string>();
		for (const item of items) {
			const ns = groupKey(item) || '(no namespace)';
			if (!seenNs.has(ns)) {
				seenNs.add(ns);
				nsOrder.push(ns);
			}
		}

		// Filter by namespace
		const nsFiltered =
			selectedNamespace !== ''
				? items.filter((item) => {
						const ns = groupKey(item) || '(no namespace)';
						return ns === selectedNamespace;
					})
				: items;

		// Filter by search term
		const searchActive = searchTerm.length > 0;
		const searchFiltered = searchActive
			? nsFiltered.filter((item) =>
					searchFields(item).some((field) => field.toLowerCase().includes(searchTerm.toLowerCase()))
				)
			: nsFiltered;

		// Group items by namespace (only when not searching)
		const groupedItems: { ns: string; items: T[]; totalCount: number }[] = [];
		if (!searchActive) {
			const groupMap: Record<string, T[]> = {};
			for (const item of searchFiltered) {
				const ns = groupKey(item) || '(no namespace)';
				if (!groupMap[ns]) groupMap[ns] = [];
				groupMap[ns].push(item);
			}

			// Preserve nsOrder but only include namespaces with matching items
			for (const ns of nsOrder) {
				const groupItems = groupMap[ns];
				if (groupItems && groupItems.length > 0) {
					groupedItems.push({ ns, items: groupItems, totalCount: groupItems.length });
				}
			}
		}

		return {
			nsOrder,
			nsFiltered,
			searchFiltered,
			groupedItems,
			searchActive,
			totalMatching: searchFiltered.length
		};
	});

	// ── Auto-expand groups based on namespace count ──────────────────────────

	let prevNsLength = 0;
	$effect(() => {
		const currentLength = view.nsOrder.length;
		if (currentLength !== prevNsLength) {
			prevNsLength = currentLength;
			expandedGroups.clear();
			if (currentLength <= 5) {
				for (const ns of view.nsOrder) {
					expandedGroups.add(ns);
				}
			}
			// Reset pagination counts
			groupVisibleCounts = {};
			flatVisibleCount = pageSize;
		}
	});

	// ── Helpers ──────────────────────────────────────────────────────────────

	function isActive(name: string) {
		const targetId = toUrlId(name);
		const params = page.params as Record<string, string | undefined>;
		return params[paramName] === targetId;
	}

	function toggleGroup(ns: string) {
		if (expandedGroups.has(ns)) {
			expandedGroups.delete(ns);
		} else {
			expandedGroups.add(ns);
		}
	}

	function visibleCount(ns: string, total: number) {
		return groupVisibleCounts[ns] ?? Math.min(pageSize, total);
	}

	function showMore(ns: string) {
		const total = view.groupedItems.find((g) => g.ns === ns)?.totalCount ?? 0;
		const current = visibleCount(ns, total);
		groupVisibleCounts = {
			...groupVisibleCounts,
			[ns]: Math.min(current + pageSize, total)
		};
	}

	function showMoreFlat() {
		flatVisibleCount = Math.min(flatVisibleCount + pageSize, view.totalMatching);
	}
</script>

<div
	class="d-flex flex-column {border ? 'border-end' : ''}"
	style="height: calc(100vh - var(--navbar-height));"
>
	<!-- Search box -->
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

	<!-- Namespace filter dropdown (only when not searching and > 1 namespace) -->
	{#if !view.searchActive && view.nsOrder.length > 1}
		<div class="p-2 border-bottom bg-light">
			<select class="form-select form-select-sm" bind:value={selectedNamespace}>
				<option value="">All namespaces ({view.totalMatching})</option>
				{#each view.nsOrder as ns (ns)}
					{@const nsCount = view.groupedItems.find((g) => g.ns === ns)?.totalCount ?? 0}
					{#if nsCount > 0}
						<option value={ns}>{ns} ({nsCount})</option>
					{/if}
				{/each}
			</select>
		</div>
	{/if}

	<!-- Content area -->
	<div class="list-group list-group-flush flex-grow-1 overflow-auto">
		{#if view.searchActive}
			<!-- Flat search results with global pagination -->
			{#each view.searchFiltered.slice(0, flatVisibleCount) as item (item.name)}
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

			{#if flatVisibleCount < view.totalMatching}
				<button
					class="list-group-item list-group-item-action text-center text-muted small py-2 border-0"
					onclick={showMoreFlat}
				>
					Show {Math.min(pageSize, view.totalMatching - flatVisibleCount)} more ({flatVisibleCount} of
					{view.totalMatching})
				</button>
			{/if}
		{:else}
			<!-- Grouped view -->
			{#each view.groupedItems as group (group.ns)}
				{@const expanded = expandedGroups.has(group.ns)}
				{@const shown = visibleCount(group.ns, group.totalCount)}

				<button
					class="list-group-item list-group-item-action d-flex justify-content-between align-items-center py-2 px-3 border-start-0 border-end-0 bg-light fw-medium small"
					onclick={() => toggleGroup(group.ns)}
				>
					<span class="text-truncate">
						<i class="bi bi-{expanded ? 'chevron-down' : 'chevron-right'} me-1 opacity-50"></i>
						{group.ns}
					</span>
					<span class="badge bg-secondary rounded-pill ms-2 flex-shrink-0">{group.totalCount}</span>
				</button>

				{#if expanded}
					{#each group.items.slice(0, shown) as item (item.name)}
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

					{#if shown < group.totalCount}
						<button
							class="list-group-item list-group-item-action text-center text-muted small py-1 ps-4 border-0"
							onclick={() => showMore(group.ns)}
						>
							Show {Math.min(pageSize, group.totalCount - shown)} more ({shown} of {group.totalCount})
						</button>
					{/if}
				{/if}
			{/each}
		{/if}

		{#if view.totalMatching === 0}
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
