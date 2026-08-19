import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

const NS = 'clara.server.tools.graph.rules.loan-hierarchy-rules';
const INSERT = `${NS}/insert-income-document`;
const REVIEW = `${NS}/review-supporting-document`;
const INCOME = `:${NS}/income-document`;

test.describe('Copyable reference names', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		await ui.groupedNav.expandAll(page);
	});

	test('clicking a fact type name copies its fully qualified name', async ({ page, context }) => {
		await context.grantPermissions(['clipboard-read', 'clipboard-write']);

		await page.locator(`a.list-group-item[title="${INSERT}"]`).click();
		await expect(ui.summary.title(page, 'insert-income-document')).toBeVisible();

		const insertCategory = page
			.locator('div.mb-3')
			.filter({ has: page.locator('h6', { hasText: 'Insert Types (Output)' }) });
		const incomeRow = insertCategory.locator(`div.list-group-item[data-fullname="${INCOME}"]`);
		const nameBtn = incomeRow.locator('button.copyable-title');
		await expect(nameBtn).toBeVisible();

		await nameBtn.click();
		await expect(nameBtn).toHaveAttribute('title', 'Copied to clipboard');

		const clipboard = await page.evaluate(() => navigator.clipboard.readText());
		expect(clipboard).toBe(INCOME);
	});

	test('clicking a production name copies its fully qualified name', async ({ page, context }) => {
		await context.grantPermissions(['clipboard-read', 'clipboard-write']);

		await page.locator(`a.list-group-item[title="${INSERT}"]`).click();
		await expect(ui.summary.title(page, 'insert-income-document')).toBeVisible();

		await ui.summary.dependenciesTab(page).click();

		const downstreamCard = page
			.locator('h6', { hasText: 'downstream' })
			.locator('xpath=ancestor::div[contains(@class,"mb-3")][1]');
		const reviewRow = downstreamCard
			.locator('div.list-group-item')
			.filter({ hasText: 'review-supporting-document' });
		const nameBtn = reviewRow.locator('button.copyable-title');
		await expect(nameBtn).toBeVisible();

		await nameBtn.click();
		await expect(nameBtn).toHaveAttribute('title', 'Copied to clipboard');

		const clipboard = await page.evaluate(() => navigator.clipboard.readText());
		expect(clipboard).toBe(REVIEW);
	});
});
