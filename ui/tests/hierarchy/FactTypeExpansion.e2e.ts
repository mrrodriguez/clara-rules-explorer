import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

const NS = 'clara.server.tools.graph.rules.loan-hierarchy-rules';
const INSERT = `${NS}/insert-income-document`;
const REVIEW = `${NS}/review-supporting-document`;
const INCOME = `:${NS}/income-document`;
const SUPPORTING = `:${NS}/supporting-document`;

test.describe('Expandable fact-type rows (production summaries)', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		await ui.groupedNav.expandAll(page);
	});

	test('an insert type expands to show the downstream production and its hierarchy bridge', async ({
		page
	}) => {
		await page.locator(`a.list-group-item[title="${INSERT}"]`).click();
		await expect(ui.summary.title(page, 'insert-income-document')).toBeVisible();

		const insertCategory = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Insert Types (Output)' }) });
		const incomeRow = insertCategory.locator(`div.list-group-item[title="${INCOME}"]`);
		await expect(incomeRow).toBeVisible();

		// The type row is not a whole-row link; navigation is a dedicated icon.
		await expect(incomeRow.locator('a.list-group-item')).toHaveCount(0);
		await expect(incomeRow.locator('a[aria-label^="Open "]')).toBeVisible();

		await incomeRow.locator('button[aria-label="Toggle upstream/downstream"]').click();

		const details = page.locator('div.fact-type-details');
		await expect(details).toBeVisible();

		const downstreamItem = details
			.locator('div.list-group-item')
			.filter({ hasText: 'review-supporting-document' });
		await expect(downstreamItem).toBeVisible();

		// The relationship is surfaced via the row's own type-matches popover,
		// not repeated inline inside the expanded panel.
		await expect(details.locator('.satisfies-label')).toHaveCount(0);

		await downstreamItem.locator('button[aria-label="Show type matches"]').click();
		const popover = page.locator('.popover-panel');
		await expect(popover).toBeVisible();
		const producerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'income-document' });
		const consumerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'supporting-document' });
		await expect(producerLink).toBeVisible();
		await expect(consumerLink).toBeVisible();
		await expect(popover.locator('.satisfies-label')).toHaveText('satisfies');
	});

	test('an input type expands to show the upstream production that satisfies it', async ({
		page
	}) => {
		await page.locator(`a.list-group-item[title="${REVIEW}"]`).click();
		await expect(ui.summary.title(page, 'review-supporting-document')).toBeVisible();

		const lhsCategory = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'LHS Types (Input)' }) });
		const supportingRow = lhsCategory.locator(`div.list-group-item[title="${SUPPORTING}"]`);
		await expect(supportingRow).toBeVisible();

		await supportingRow.locator('button[aria-label="Toggle upstream/downstream"]').click();

		const details = page.locator('div.fact-type-details');
		await expect(details).toBeVisible();

		const upstreamItem = details
			.locator('div.list-group-item')
			.filter({ hasText: 'insert-income-document' });
		await expect(upstreamItem).toBeVisible();

		// The relationship is surfaced via the row's own type-matches popover,
		// not repeated inline inside the expanded panel.
		await expect(details.locator('.satisfies-label')).toHaveCount(0);

		await upstreamItem.locator('button[aria-label="Show type matches"]').click();
		const popover = page.locator('.popover-panel');
		await expect(popover).toBeVisible();
		const producerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'income-document' });
		const consumerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'supporting-document' });
		await expect(producerLink).toBeVisible();
		await expect(consumerLink).toBeVisible();
		await expect(popover.locator('.satisfies-label')).toHaveText('satisfies');
	});
});
