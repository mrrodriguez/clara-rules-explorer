import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

const NS = 'clara.server.tools.graph.rules.loan-hierarchy-rules';
const INCOME = `:${NS}/income-document`;
const SUPPORTING = `:${NS}/supporting-document`;
const LOAN = `:${NS}/loan-document`;
const BASE = `:${NS}/base-document`;
const REVIEWED = `:${NS}/document-reviewed`;

test.describe('Hierarchy ancestors section (hierarchy ruleset)', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Fact Types');
		await expect(page).toHaveURL(/\/fact-types/);
		await ui.groupedNav.expandAll(page);
	});

	test('renders known ancestors as links and ghosts as non-linkable rows, in hierarchy order', async ({
		page
	}) => {
		// income-document <: supporting-document <: loan-document <: base-document.
		await page.locator(`a.list-group-item[title="${INCOME}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Ancestors)' }) });
		await expect(category).toBeVisible();

		// Ancestors arrive hierarchy-ordered: descendants before their ancestors.
		const rows = category.locator('.list-group > *');
		await expect(rows.nth(0)).toContainText('supporting-document');
		await expect(rows.nth(1)).toContainText('loan-document');
		await expect(rows.nth(2)).toContainText('base-document');

		// supporting/loan are on an LHS → known → linkable via their id.
		const supportingLink = category.locator(`a.list-group-item[title="${SUPPORTING}"]`);
		const loanLink = category.locator(`a.list-group-item[title="${LOAN}"]`);
		await expect(supportingLink).toHaveAttribute('href', /\/fact-types\//);
		await expect(loanLink).toHaveAttribute('href', /\/fact-types\//);

		// base-document is never on an LHS → ghost → muted italic row, no link.
		const ghostRow = category.locator('div.list-group-item').filter({ hasText: 'base-document' });
		await expect(ghostRow).toBeVisible();
		await expect(ghostRow.locator('.text-muted.fst-italic')).toBeVisible();
		await expect(ghostRow.locator('a')).toHaveCount(0);

		// The ordering note accompanies a non-empty ancestor list.
		await expect(page.getByText('Ancestors are listed in hierarchy order')).toBeVisible();
	});

	test('a known ancestor link resolves to that fact type detail', async ({ page }) => {
		await page.locator(`a.list-group-item[title="${INCOME}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Ancestors)' }) });
		await category.locator(`a.list-group-item[title="${SUPPORTING}"]`).click();

		await expect(page).toHaveURL(/\/fact-types\//);
		await expect(page.locator('.card-header').filter({ hasText: 'supporting-document' })).toBeVisible();
	});

	test('shows the root-of-hierarchy empty state for types without ancestors', async ({
		page
	}) => {
		// document-reviewed is an underived keyword — ancestors: [].
		await page.locator(`a.list-group-item[title="${REVIEWED}"]`).click();

		const category = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Hierarchy (Ancestors)' }) });
		await expect(category).toBeVisible();
		await expect(
			category.getByText('No ancestors — this type sits at the root of its hierarchy.')
		).toBeVisible();

		// No ordering note for a type with no ancestors.
		await expect(page.getByText('Ancestors are listed in hierarchy order')).toHaveCount(0);
	});
});
