import { describe, it, expect } from 'vitest';
import { render } from 'vitest-browser-svelte';
import LhsCondition from './LhsCondition.svelte';
import type { LhsElement, TypeReference } from '$lib/types/api';

const type: TypeReference = { name: ':t/item', id: ':t/item', known: false };

function collapseWhitespace(text: string | null | undefined): string {
	return (text ?? '').replace(/\s+/g, ' ').trim();
}

describe('LhsCondition bindings summary', () => {
	it('renders a collapsible bindings summary in fixed group order', async () => {
		const leaf: LhsElement = {
			type,
			bindings: {
				'binding-keys': ['?app-id'],
				'new-bindings': ['?docs']
			}
		};

		const screen = await render(LhsCondition, { props: { condition: leaf } });

		const toggle = screen.getByRole('button', { name: /bindings/ });
		await expect.element(toggle).toHaveAttribute('aria-expanded', 'false');
		expect(screen.container.textContent).not.toContain('?app-id');

		await toggle.click();

		await expect.element(toggle).toHaveAttribute('aria-expanded', 'true');
		expect(collapseWhitespace(screen.container.textContent)).toContain('New ?docs');
		expect(collapseWhitespace(screen.container.textContent)).toContain('Joins ?app-id');
	});

	it('does not render a bindings toggle when the leaf has no binding groups', async () => {
		const leaf: LhsElement = { type };

		const screen = await render(LhsCondition, { props: { condition: leaf } });

		expect(screen.container.querySelectorAll('button[aria-expanded]')).toHaveLength(0);
	});

	it('renders group entries (condition-type + children) as nested conditions', async () => {
		const group: LhsElement = {
			'condition-type': 'not',
			children: [{ type }]
		};

		const screen = await render(LhsCondition, { props: { condition: group } });

		expect(screen.container.querySelector('.nested-badge')?.textContent?.trim()).toBe('not');
		expect(screen.container.querySelectorAll('.lhs-condition')).toHaveLength(2);
	});

	it('renders group-level bindings as the union of the children', async () => {
		const group: LhsElement = {
			'condition-type': 'or',
			bindings: {
				'binding-keys': ['?app-id'],
				'new-bindings': ['?k']
			},
			children: [
				{ type, bindings: { 'binding-keys': ['?app-id'], 'new-bindings': ['?k'] } },
				{ type, bindings: { 'binding-keys': ['?app-id'], 'new-bindings': [] } }
			]
		};

		const screen = await render(LhsCondition, { props: { condition: group } });

		const toggles = screen.container.querySelectorAll<HTMLElement>('button[aria-expanded]');
		// one toggle for the group plus one per bound child
		expect(toggles.length).toBe(3);

		await toggles[0].click();
		expect(collapseWhitespace(screen.container.textContent)).toContain('Joins ?app-id');
	});

	it('renders no bindings card for a group without bindings', async () => {
		const group: LhsElement = {
			'condition-type': 'not',
			children: [{ type }]
		};

		const screen = await render(LhsCondition, { props: { condition: group } });

		expect(screen.container.querySelectorAll('button[aria-expanded]')).toHaveLength(0);
		expect(screen.container.textContent).not.toContain('Bindings');
	});
});
