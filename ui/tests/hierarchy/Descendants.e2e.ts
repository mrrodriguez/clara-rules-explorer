import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

const NS = 'clara.server.tools.graph.rules.loan-hierarchy-rules';
const BASE = `:${NS}/base-document`;
const LOAN = `:${NS}/loan-document`;
const SUPPORTING = `:${NS}/supporting-document`;
const INCOME = `:${NS}/income-document`;
const REVIEWED = `:${NS}/document-reviewed`;

test.describe('Hierarchy descendants section (hierarchy ruleset)', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Fact Types');
		await expect(page).toHaveURL(/\/fact-types/);
		await ui.groupedNav.expandAll(page);
	});

	test('renders descendants as links in hierarchy order (direct descendants first)', async ({
		page
	}) => {
		// base-document <: loan-document <: supporting-document <: income-document.
		await page.locator(`a.list-group-item[title="${BASE}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Descendants)' }) });
		await expect(category).toBeVisible();

		// Descendants arrive direct-first: loan, supporting, income.
		const rows = category.locator('.list-group > *');
		await expect(rows.nth(0)).toContainText('loan-document');
		await expect(rows.nth(1)).toContainText('supporting-document');
		await expect(rows.nth(2)).toContainText('income-document');

		// All three derive-keyword descendants are known → each carries the
		// dedicated open icon, not a whole-row link.
		for (const name of [INCOME, SUPPORTING, LOAN]) {
			const row = category.locator(`div.list-group-item[title="${name}"]`);
			await expect(row.locator('a.list-group-item')).toHaveCount(0);
			await expect(row.locator('a[aria-label^="Open "]')).toHaveAttribute(
				'href',
				/\/fact-types\//
			);
		}

		// The ordering note accompanies a non-empty descendant list.
		await expect(page.getByText('Descendants are listed in hierarchy order')).toBeVisible();
	});

	test('a known descendant link resolves to that fact type detail', async ({ page }) => {
		await page.locator(`a.list-group-item[title="${BASE}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Descendants)' }) });
		await category
			.locator(`div.list-group-item[title="${SUPPORTING}"]`)
			.locator('a[aria-label^="Open "]')
			.click();

		await expect(page).toHaveURL(/\/fact-types\//);
		await expect(
			page.locator('.card-header').filter({ hasText: 'supporting-document' })
		).toBeVisible();
	});

	test('shows the leaf-of-hierarchy empty state for types without descendants', async ({
		page
	}) => {
		// document-reviewed is an underived keyword — descendants: [].
		await page.locator(`a.list-group-item[title="${REVIEWED}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Descendants)' }) });
		await expect(category).toBeVisible();
		await expect(
			category.getByText('No descendants — this type sits at a leaf of its hierarchy.')
		).toBeVisible();

		// No ordering note for a type with no descendants.
		await expect(page.getByText('Descendants are listed in hierarchy order')).toHaveCount(0);
	});
});
