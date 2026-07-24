import type { Snippet } from 'svelte';

/**
 * Props for {@link GroupedFilterableNavList}. Extracted to a standalone module
 * because Svelte's instance `<script>` does not support `export` on types that
 * depend on component-level generics. Consumers import this type for type-safe
 * configuration arrays, e.g.:
 *
 * ```ts
 * import type { RuleListItem } from '$lib/types/api';
 * import type { GroupedFilterableNavListProps } from '$lib/components/nav/GroupedFilterableNavListProps';
 * const cfg: GroupedFilterableNavListProps<RuleListItem> = { ... };
 * ```
 */
export interface GroupedFilterableNavListProps<T extends { name: string }> {
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
	/**
	 * URL-encoded ID of the currently active item (from route params).
	 * Compared against `toUrlId(item.name)`. When `undefined`, no item
	 * is highlighted. Callers should derive this from `page.params` on
	 * their own route, where SvelteKit provides compile-time type safety.
	 */
	activeId?: string;
	/** Whether to show a border on the container (default: true) */
	border?: boolean;
	/** Label used to qualify counts (e.g. "rules", "queries", "fact types") */
	itemLabel?: string;
}
