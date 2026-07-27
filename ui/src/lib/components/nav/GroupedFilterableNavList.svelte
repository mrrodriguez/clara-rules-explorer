<script lang="ts" generics="T extends { name: string }">
	import { SvelteMap, SvelteSet } from 'svelte/reactivity';
	import { clickOutside } from '$lib/actions/clickOutside';
	import { tooltip } from '$lib/actions/popover';
	import ReferenceListItem from '$lib/components/nav/ReferenceListItem.svelte';
	import type { GroupedFilterableNavListProps } from '$lib/components/nav/GroupedFilterableNavListProps';
	import { NamespaceFilter } from '$lib/components/nav/namespaceFilter.svelte';
	import { RulebaseFilter } from '$lib/components/nav/rulebaseFilter.svelte';
	import { toRouteId } from '$lib/utils';

	let {
		items,
		groupKey,
		hrefPrefix,
		activeColor = '#0d6efd',
		searchPlaceholder = 'Search...',
		searchFields = (item: T) => [item.name],
		itemRight,
		activeId,
		border = true,
		itemLabel = 'items',
		filters = []
	}: GroupedFilterableNavListProps<T> = $props();

	// ── State ────────────────────────────────────────────────────────────────

	let searchTerm = $state('');
	let expandedGroups = $state<Record<string, boolean>>({});

	const nsFilter = new NamespaceFilter();
	const rulebaseFilter = new RulebaseFilter();

	// ── Rulebase filter helpers ──────────────────────────────────────────────

	function matchesRulebaseFilter(item: T): boolean {
		if (!rulebaseFilter.active) return true;
		const activeIds = Object.keys(rulebaseFilter.activeFilters);
		return activeIds.some((id) => {
			const opt = filters.find((f) => f.id === id);
			return opt ? opt.predicate(item) : false;
		});
	}

	// ── Precomputed namespace index (only recomputes when items changes) ─────

	const nsIndex = $derived.by(() => {
		const nsOrder: string[] = [];
		const seenNs = new SvelteSet<string>();
		const byNs = new SvelteMap<string, T[]>();
		const routeIdToNs = new SvelteMap<string, string>();

		for (const item of items) {
			const ns = groupKey(item) || '(no namespace)';
			const routeId = toRouteId(item.name);

			if (!seenNs.has(ns)) {
				seenNs.add(ns);
				nsOrder.push(ns);
			}
			const bucket = byNs.get(ns);
			if (bucket) {
				bucket.push(item);
			} else {
				byNs.set(ns, [item]);
			}
			routeIdToNs.set(routeId, ns);
		}

		return { nsOrder, byNs, routeIdToNs };
	});

	// ── Derived view model (cheap filtering over precomputed index) ──────────

	const view = $derived.by(() => {
		const { nsOrder, byNs } = nsIndex;
		const searchActive = searchTerm.length > 0;
		const activeNsFilter = nsFilter.active;
		const needle = searchTerm.toLowerCase();
		const filterActive = rulebaseFilter.active;

		// Per-namespace visible counts (for dropdown checkboxes)
		const nsItemCounts = new SvelteMap<string, number>();
		let totalSearchResults: number;
		let visibleNsCount = 0;

		// Search results (flat list, no grouping)
		let searchFiltered: T[] | null = null;
		if (searchActive) {
			searchFiltered = [];
			for (const item of items) {
				const matches = searchFields(item).some((f) => f.toLowerCase().includes(needle));
				if (matches) {
					searchFiltered.push(item);
					const ns = groupKey(item) || '(no namespace)';
					nsItemCounts.set(ns, (nsItemCounts.get(ns) ?? 0) + 1);
				}
			}
			totalSearchResults = searchFiltered.length;
			// Visible ns count (all namespaces with matching items)
			for (const ns of nsOrder) {
				const count = nsItemCounts.get(ns) ?? 0;
				if (count > 0 && !nsFilter.hiddenNamespaces[ns]) visibleNsCount++;
			}
		} else {
			// Non-search mode: populate counts for ALL namespaces (dropdown checkboxes
			// need counts even for hidden namespaces so toggles remain clickable).
			for (const ns of nsOrder) {
				const bucket = byNs.get(ns);
				const count = bucket ? bucket.length : 0;
				nsItemCounts.set(ns, count);
				if (count > 0 && !nsFilter.hiddenNamespaces[ns]) visibleNsCount++;
			}
			totalSearchResults = items.length;
		}

		// Build groupedItems for non-search mode (respects namespace visibility)
		let groupedItems: { ns: string; items: T[]; totalCount: number }[] = [];
		if (!searchActive) {
			for (const ns of nsOrder) {
				if (nsFilter.hiddenNamespaces[ns]) continue;
				const bucket = byNs.get(ns);
				if (bucket && bucket.length > 0) {
					groupedItems.push({ ns, items: bucket, totalCount: bucket.length });
				}
			}
		}

		// ── Rulebase attribute filter (applies in both search and grouped mode) ──

		if (filterActive) {
			if (searchActive && searchFiltered) {
				searchFiltered = searchFiltered.filter((item) => matchesRulebaseFilter(item));
				totalSearchResults = searchFiltered.length;
			} else {
				groupedItems = groupedItems
					.map((g) => ({
						...g,
						items: g.items.filter((item) => matchesRulebaseFilter(item)),
						totalCount: g.items.length
					}))
					.filter((g) => g.items.length > 0);
			}
		}

		return {
			nsOrder,
			searchFiltered,
			groupedItems,
			searchActive,
			totalSearchResults,
			activeNsFilter,
			visibleNsCount,
			nsItemCounts,
			filterActive,
			totalMatching: searchActive
				? (searchFiltered?.length ?? 0)
				: activeNsFilter || filterActive
					? groupedItems.reduce((sum, g) => sum + g.items.length, 0)
					: items.length
		};
	});

	// ── Namespace of the currently active item (O(1) Map lookup) ─────────────

	const activeNs = $derived(activeId ? (nsIndex.routeIdToNs.get(activeId) ?? null) : null);

	// ── Auto-expand logic ────────────────────────────────────────────────────

	// Effect guards: track previous values for comparison inside $effect blocks.
	// These are NOT $state — they exist solely to diff against across effect runs
	// and should never trigger re-rendering.
	let prevNsLength = 0;
	let prevVisibleNsCount = 0;
	let prevGroupCount = 0;
	$effect(() => {
		const totalNs = view.nsOrder.length;
		const visibleNs = view.visibleNsCount;
		const groupCount = view.groupedItems.length;

		// Full data set changed — reset
		if (totalNs !== prevNsLength) {
			prevNsLength = totalNs;
			prevVisibleNsCount = visibleNs;
			prevGroupCount = groupCount;
			expandedGroups = {};
			if (totalNs === 1) {
				expandedGroups[view.nsOrder[0]] = true;
			}
			return;
		}

		// Auto-expand when narrowed to exactly 1 group (namespace filter or rulebase filter)
		if (groupCount === 1 && groupCount !== prevGroupCount) {
			for (const g of view.groupedItems) {
				expandedGroups[g.ns] = true;
			}
		}
		// Namespace filter narrowed to 1 visible NS but rulebase filter may have
		// cleared all items from some groups — still expand the sole survivor.
		else if (visibleNs === 1 && visibleNs !== prevVisibleNsCount) {
			for (const g of view.groupedItems) {
				expandedGroups[g.ns] = true;
			}
		}

		prevVisibleNsCount = visibleNs;
		prevGroupCount = groupCount;
	});

	// Expand the namespace containing the currently active item
	// Effect guard (see note above) — NOT $state.
	let prevActiveNs: string | null = null;
	$effect(() => {
		const ns = activeNs;
		if (ns && ns !== prevActiveNs) {
			prevActiveNs = ns;
			if (!expandedGroups[ns]) {
				expandedGroups[ns] = true;
			}
		}
	});

	// ── Filtered namespace list for dropdown search ─────────────────────────

	const filteredNsOrder = $derived(nsFilter.getFiltered(view.nsOrder));

	// ── Group helpers ────────────────────────────────────────────────────────

	function toggleGroup(ns: string) {
		if (expandedGroups[ns]) {
			delete expandedGroups[ns];
		} else {
			expandedGroups[ns] = true;
		}
	}

	function expandAll() {
		for (const ns of view.nsOrder) {
			expandedGroups[ns] = true;
		}
	}

	function collapseAll() {
		expandedGroups = {};
	}

	// ── Route helpers ────────────────────────────────────────────────────────

	function isActive(name: string) {
		return activeId !== undefined && activeId === toRouteId(name);
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

	<!-- Rulebase attribute filter (only when filter options are provided) -->
	{#if filters.length > 0}
		<div class="p-2 border-bottom bg-light">
			<div
				class="position-relative"
				use:clickOutside={() => {
					rulebaseFilter.filterDropdownOpen = false;
				}}
			>
				<button
					class="btn btn-sm btn-outline-secondary w-100 text-truncate text-start"
					onclick={() => (rulebaseFilter.filterDropdownOpen = !rulebaseFilter.filterDropdownOpen)}
				>
					<i class="bi bi-funnel-fill me-1 opacity-75"></i>
					{#if rulebaseFilter.active}
						{Object.keys(rulebaseFilter.activeFilters).length} filter{Object.keys(
							rulebaseFilter.activeFilters
						).length !== 1
							? 's'
							: ''}
					{:else}
						Filters
					{/if}
				</button>

				{#if rulebaseFilter.filterDropdownOpen}
					<div class="dropdown-menu show w-100 p-1" style="max-height: 320px; overflow-y: auto;">
						<button class="dropdown-item small py-1" onclick={() => rulebaseFilter.clearAll()}>
							<i class="bi bi-eraser me-1 opacity-50"></i> Clear all filters
						</button>
						<div class="dropdown-divider my-1"></div>
						{#each filters as filter (filter.id)}
							{@const checked = !!rulebaseFilter.activeFilters[filter.id]}
							<button
								class="dropdown-item small d-flex align-items-center gap-1 mb-0 py-1"
								onclick={() => rulebaseFilter.toggle(filter.id)}
							>
								<i class="bi bi-{checked ? 'check-square' : 'square'} me-1 opacity-75"></i>
								<span class="text-truncate">{filter.label}</span>
							</button>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	{/if}

	<!-- Namespace multi-select filter (only when > 1 namespace, not searching) -->
	{#if !view.searchActive && view.nsOrder.length > 1}
		<div class="p-2 border-bottom bg-light">
			<div
				class="position-relative"
				use:clickOutside={() => {
					nsFilter.filterDropdownOpen = false;
					nsFilter.nsFilterText = '';
				}}
			>
				<button
					class="btn btn-sm btn-outline-secondary w-100 text-truncate text-start"
					onclick={() => (nsFilter.filterDropdownOpen = !nsFilter.filterDropdownOpen)}
				>
					<i class="bi bi-funnel me-1 opacity-75"></i>
					{#if nsFilter.active}
						{view.visibleNsCount} of {view.nsOrder.length} namespaces
					{:else}
						All namespaces
					{/if}
					<span class="ms-1 text-muted">· {view.totalSearchResults} {itemLabel}</span>
				</button>

				{#if nsFilter.filterDropdownOpen}
					<div class="dropdown-menu show w-100 p-1" style="max-height: 320px; overflow-y: auto;">
						<button class="dropdown-item small py-1" onclick={() => nsFilter.showAll()}>
							<i class="bi bi-check-all me-1 opacity-50"></i> Show all namespaces
						</button>
						<div class="dropdown-divider my-1"></div>
						<div class="px-1 mb-1">
							<input
								type="text"
								class="form-control form-control-sm"
								placeholder="Filter namespaces..."
								bind:value={nsFilter.nsFilterText}
							/>
						</div>
						{#each filteredNsOrder as ns (ns)}
							{@const count = view.nsItemCounts.get(ns) ?? 0}
							{@const checked = !nsFilter.hiddenNamespaces[ns]}
							<button
								class="dropdown-item small d-flex align-items-center gap-1 mb-0 py-1 {count === 0
									? 'text-muted'
									: ''}"
								disabled={count === 0}
								onclick={() => nsFilter.toggle(ns, view.nsOrder)}
								data-ns={ns}
							>
								<i class="bi bi-{checked ? 'check-square' : 'square'} me-1 opacity-75"></i>
								<span class="text-truncate" title={ns}>{ns}</span>
								<span class="ms-auto text-muted ps-1 small">{count}</span>
							</button>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	{/if}

	<!-- Collapse / Expand all (only in grouped mode, > 1 namespace) -->
	{#if !view.searchActive && view.nsOrder.length > 1}
		<div class="d-flex justify-content-end gap-2 px-2 pt-1 small">
			<button
				class="btn btn-link btn-sm text-muted text-decoration-none py-0 px-1"
				onclick={expandAll}
			>
				Expand all
			</button>
			<button
				class="btn btn-link btn-sm text-muted text-decoration-none py-0 px-1"
				onclick={collapseAll}
			>
				Collapse all
			</button>
		</div>
	{/if}

	<!-- Content area -->
	<div class="list-group list-group-flush flex-grow-1 overflow-auto">
		{#if view.searchActive}
			<!-- Flat search results -->
			{#each view.searchFiltered as item (item.name)}
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
		{:else}
			<!-- Grouped view -->
			{#each view.groupedItems as group (group.ns)}
				{@const expanded = !!expandedGroups[group.ns]}

				<button
					class="list-group-item list-group-item-action d-flex flex-column py-2 px-3 border-start-0 border-end-0 bg-light fw-medium small text-start"
					onclick={() => toggleGroup(group.ns)}
				>
					<span class="d-flex align-items-center w-100">
						<i
							class="bi bi-{expanded
								? 'chevron-down'
								: 'chevron-right'} me-1 opacity-50 flex-shrink-0"
						></i>
						<span class="text-truncate" use:tooltip>
							{group.ns}
						</span>
					</span>
					<span class="badge bg-secondary rounded-pill mt-1">{group.totalCount} {itemLabel}</span>
				</button>

				{#if expanded}
					{#each group.items as item (item.name)}
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
