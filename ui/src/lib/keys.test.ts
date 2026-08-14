import { describe, it, expect } from 'vitest';
import { stableKeys } from './keys';

describe('stableKeys', () => {
	it('leaves a duplicate-free list of string keys unchanged', () => {
		const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
		expect(stableKeys(items, (item) => item.id)).toEqual(['a', 'b', 'c']);
	});

	it('leaves a duplicate-free list of number keys unchanged (and numeric)', () => {
		const items = [{ id: 1 }, { id: 2 }, { id: 3 }];
		expect(stableKeys(items, (item) => item.id)).toEqual([1, 2, 3]);
	});

	it('returns distinct keys for a list containing duplicate ids', () => {
		const items = [{ id: 'a' }, { id: 'a' }, { id: 'b' }];
		const keys = stableKeys(items, (item) => item.id);
		expect(new Set(keys).size).toBe(keys.length);
		expect(keys).toEqual(['a', 'a__dup-1', 'b']);
	});

	it('numbers the second and later occurrences deterministically', () => {
		const items = [{ id: 'x' }, { id: 'x' }, { id: 'x' }];
		expect(stableKeys(items, (item) => item.id)).toEqual(['x', 'x__dup-1', 'x__dup-2']);
	});

	it('treats each base key independently', () => {
		const items = [{ id: 'a' }, { id: 'b' }, { id: 'a' }];
		expect(stableKeys(items, (item) => item.id)).toEqual(['a', 'b', 'a__dup-1']);
	});

	it('returns an empty array for an empty list', () => {
		expect(stableKeys([], (item: { id: string }) => item.id)).toEqual([]);
	});
});
