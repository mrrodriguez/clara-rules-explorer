import { describe, it, expect } from 'vitest';
import { render } from 'vitest-browser-svelte';
import SessionActivityRow from './SessionActivityRow.svelte';
import type { FactMatch, SessionFact } from '$lib/types/api';

function makeFact(overrides: Partial<SessionFact> = {}): SessionFact {
	return {
		id: 2,
		type: { name: ':t/item', id: ':t/item', known: true },
		data: { tag: 'a' },
		'is-root': true,
		'inserted-from': [],
		'used-by': [],
		...overrides
	};
}

function collapseWhitespace(text: string | null | undefined): string {
	return (text ?? '').replace(/\s+/g, ' ').trim();
}

describe('SessionActivityRow (match rows)', () => {
	it('renders one row with one expandable block per binding set, labelled by ordinal', async () => {
		const match: FactMatch = {
			fact: makeFact(),
			bindings: [
				{ '?tagged': ['a', 'b'], '?all': ['a', 'b', null] },
				{ '?config': { name: 'c1' }, '?item': { tag: 'a' } }
			]
		};

		const screen = await render(SessionActivityRow, {
			props: { item: match, type: 'matches' }
		});

		expect(screen.container.querySelectorAll('.session-activity-row')).toHaveLength(1);

		const toggles = [...screen.container.querySelectorAll('button[aria-expanded]')];
		expect(toggles).toHaveLength(2);
		expect(collapseWhitespace(toggles[0]?.textContent)).toBe('Show binding 1');
		expect(collapseWhitespace(toggles[1]?.textContent)).toBe('Show binding 2');
	});

	it('renders a single-binding match row like a plain fact row (no ordinal)', async () => {
		const match: FactMatch = {
			fact: makeFact(),
			bindings: [{ '?tagged': ['a'] }]
		};

		const screen = await render(SessionActivityRow, {
			props: { item: match, type: 'matches' }
		});

		const toggles = [...screen.container.querySelectorAll('button[aria-expanded]')];
		expect(toggles).toHaveLength(1);
		expect(collapseWhitespace(toggles[0]?.textContent)).toBe('Show expression');
	});
});
