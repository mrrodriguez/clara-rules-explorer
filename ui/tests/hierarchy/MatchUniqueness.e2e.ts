import { test, expect, type Page } from '@playwright/test';
import { ui } from '../support/ui';

// `pairwise` joins one Config with each of three Items, so the Config fact
// appears in :matches once with three distinct binding sets.
const PAIRWISE = 'clara.server.tools.graph.rules.match-uniqueness-test-rules/pairwise';

async function openPairwiseFullView(page: Page) {
	await ui.sidebar.navigateTo(page, 'Rules');
	await expect(page).toHaveURL(/\/rules/);
	await ui.groupedNav.expandAll(page);
	await page.locator(`a.list-group-item[title="${PAIRWISE}"]`).click();
	await expect(ui.summary.title(page, 'pairwise')).toBeVisible();
	// The summary header and footer both carry a "Full View" link.
	await page.getByRole('link', { name: 'Full View' }).first().click();
	await expect(page).toHaveURL(/\/rules\/.*\/full$/);
}

function collectPageErrors(page: Page): string[] {
	const errors: string[] = [];
	page.on('pageerror', (error) => errors.push(error.message));
	return errors;
}

test.describe('Working-memory match uniqueness (multi-binding match rows)', () => {
	test('renders a multi-binding match row after client-side navigation with no page errors', async ({
		page
	}) => {
		const errors = collectPageErrors(page);

		await page.goto('/');
		await openPairwiseFullView(page);

		// The Config fact matched under three activations → three expandable
		// binding blocks, each labelled by ordinal.
		await expect(page.getByText('Active Matches (4)')).toBeVisible();
		await expect(page.getByText('Show binding 3')).toBeVisible();

		expect(errors).toEqual([]);
	});

	test('renders a multi-binding match row after direct load with no page errors', async ({
		page
	}) => {
		const errors = collectPageErrors(page);

		await page.goto('/');
		await openPairwiseFullView(page);
		const fullUrl = page.url();

		// Fresh document load (no client-side state) — the hydration path.
		await page.goto(fullUrl);
		await expect(page.getByText('Active Matches (4)')).toBeVisible();
		await expect(page.getByText('Show binding 3')).toBeVisible();

		expect(errors).toEqual([]);
	});
});
