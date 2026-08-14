/**
 * Render-key backstop for keyed `{#each}` blocks over server-supplied data.
 *
 * Svelte's `each_key_duplicate` is a fatal hydration abort — a single repeated
 * id/name in a server response blanks the whole page, not just the one list.
 * `stableKeys` appends a deterministic occurrence ordinal on collision so a
 * server-side regression degrades to a redundant re-render instead.
 *
 * Keys for a duplicate-free list are returned unchanged (same value, same
 * type); only subsequent occurrences of a repeated base key are suffixed.
 */
export function stableKeys<T>(
	items: T[],
	keyFn: (item: T) => string | number
): (string | number)[] {
	const seen = new Map<string | number, number>();
	return items.map((item) => {
		const base = keyFn(item);
		const count = seen.get(base) ?? 0;
		seen.set(base, count + 1);
		return count === 0 ? base : `${base}__dup-${count}`;
	});
}
