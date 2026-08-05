import type { Snippet } from 'svelte';

/**
 * A single filter option displayed as a checkbox in the filter menu.
 */
export interface FilterOption<T> {
	/** Unique identifier for this filter (used as the toggle key). */
	id: string;
	/** Human-readable label shown in the dropdown. */
	label: string;
	/** Predicate that returns `true` when an item matches this filter. */
	predicate: (item: T) => boolean;
}

/**
 * Props for {@link GroupedFilterableNavList}. Extracted to a standalone module
 * because Svelte's instance `<script>` does not support `export` on types that
 * depend on component-level generics. Consumers import this type for type-safe
 * configuration arrays, e.g.:
 *
 * ```ts
 * import type { RuleListItem } from '$lib/types/api';
 * import type { GroupedFilterableNavListProps } from '$lib/components/rulebase/nav/GroupedFilterableNavListProps';
 * const cfg: GroupedFilterableNavListProps<RuleListItem> = { ... };
 * ```
 */
export interface GroupedFilterableNavListProps<T extends { name: string; id: string }> {
	items: T[];
	/** Extract the namespace/group key from an item. Empty string = ungrouped. */
	groupKey: (item: T) => string;
	/** Build a link href from an item (use its server-issued `id`). */
	hrefPrefix: (item: T) => string;
	activeColor?: string;
	searchPlaceholder?: string;
	/** Fields to search against (default: [item.name]) */
	searchFields?: (item: T) => string[];
	/** Optional snippet for content to the right of the name */
	itemRight?: Snippet<[T]>;
	/**
	 * Route id of the currently active item (from route params — passed
	 * through verbatim, never encoded). Compared against `item.id`. When
	 * `undefined`, no item is highlighted. Callers should derive this from
	 * `page.params` on their own route, where SvelteKit provides
	 * compile-time type safety.
	 */
	activeId?: string;
	/** Whether to show a border on the container (default: true) */
	border?: boolean;
	/** Label used to qualify counts (e.g. "rules", "queries", "fact types") */
	itemLabel?: string;
	/**
	 * Optional rulebase attribute filter options. When provided, a filter
	 * menu button appears between the search box and the namespace filter.
	 * Multiple checked filters are combined with OR logic.
	 */
	filters?: FilterOption<T>[];
	/**
	 * Callback invoked whenever the active item's visibility under current
	 * filters changes. The parent layout typically wires this to update a
	 * reactive context that summary views read to disable the "Locate in
	 * List" button.
	 */
	onFilteredOutChange?: (filteredOut: boolean) => void;
}
