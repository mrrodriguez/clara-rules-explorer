import { describe, it, expect } from 'vitest';
import { render } from 'vitest-browser-svelte';
import SessionActivityBlock from './SessionActivityBlock.svelte';
import type { ActivityCategory } from './SessionActivityBlock.svelte';
import type { FactMatch, SessionFact } from '$lib/types/api';

const fact: SessionFact = {
	id: 1,
	type: { name: ':t/config', id: ':t/config', known: true },
	data: { name: 'c1' },
	'is-root': true,
	'inserted-from': [],
	'used-by': []
};

const match: FactMatch = {
	fact: {
		id: 2,
		type: { name: ':t/item', id: ':t/item', known: true },
		data: { tag: 'a' },
		'is-root': true,
		'inserted-from': [],
		'used-by': []
	},
	bindings: [{ '?config': { name: 'c1' }, '?item': { tag: 'a' } }]
};

describe('SessionActivityBlock', () => {
	it('renders a facts category and a matches category side by side in one block', async () => {
		const categories: ActivityCategory[] = [
			{ title: 'Active Matches', type: 'matches', items: [match] },
			{ title: 'Inserted Facts', type: 'facts', items: [fact] }
		];

		const screen = await render(SessionActivityBlock, { props: { categories } });

		// Both categories render their header + one row each.
		const text = screen.container.textContent ?? '';
		expect(text).toContain('Active Matches (1)');
		expect(text).toContain('Inserted Facts (1)');
		expect(screen.container.querySelectorAll('.session-activity-row')).toHaveLength(2);
	});

	it('renders its empty text when no category has items', async () => {
		const screen = await render(SessionActivityBlock, {
			props: { categories: [], emptyText: 'Nothing recorded.' }
		});

		expect(screen.container.textContent).toContain('Nothing recorded.');
	});
});
