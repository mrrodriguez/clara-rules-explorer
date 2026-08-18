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

function twoCategories(): ActivityCategory[] {
	return [
		{ title: 'Active Matches', type: 'matches', items: [match] },
		{ title: 'Inserted Facts', type: 'facts', items: [fact] }
	];
}

describe('SessionActivityBlock', () => {
	it('renders a collapsible header with one tab per active category', async () => {
		const screen = await render(SessionActivityBlock, {
			props: { categories: twoCategories() }
		});

		const header = screen.getByRole('button', { name: /Current Session Activity/ });
		await expect.element(header).toBeVisible();
		await expect.element(header).toHaveAttribute('aria-expanded', 'true');

		// Both categories are represented as tabs, each with its count.
		expect(screen.getByRole('tab').elements()).toHaveLength(2);
		expect(screen.container.textContent).toContain('Active Matches (1)');
		expect(screen.container.textContent).toContain('Inserted Facts (1)');

		// Only the active tab's list is rendered.
		expect(screen.container.querySelectorAll('.session-activity-row')).toHaveLength(1);
	});

	it('switches to the selected tab', async () => {
		const screen = await render(SessionActivityBlock, {
			props: { categories: twoCategories() }
		});

		// Active Matches is the first tab and is active by default.
		expect(screen.container.textContent).toContain('Fact ID: 2');

		await screen.getByRole('tab', { name: /Inserted Facts/ }).click();

		expect(screen.container.textContent).toContain('Fact ID: 1');
		expect(screen.container.textContent).not.toContain('Fact ID: 2');
		expect(screen.container.querySelectorAll('.session-activity-row')).toHaveLength(1);
	});

	it('collapses and re-expands the whole block', async () => {
		const screen = await render(SessionActivityBlock, {
			props: { categories: twoCategories() }
		});

		const header = screen.getByRole('button', { name: /Current Session Activity/ });

		await header.click();
		await expect.element(header).toHaveAttribute('aria-expanded', 'false');
		expect(screen.container.querySelector('.session-activity-tabs')).toBeNull();

		await header.click();
		await expect.element(header).toHaveAttribute('aria-expanded', 'true');
		expect(screen.container.querySelector('.session-activity-tabs')).not.toBeNull();
	});

	it('renders its empty text when no category has items', async () => {
		const screen = await render(SessionActivityBlock, {
			props: { categories: [], emptyText: 'Nothing recorded.' }
		});

		expect(screen.container.textContent).toContain('Nothing recorded.');
	});
});
