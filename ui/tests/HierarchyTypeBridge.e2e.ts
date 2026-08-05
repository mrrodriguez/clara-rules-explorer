import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

const NS = 'clara.server.tools.graph.rules.loan-hierarchy-rules';
const INSERT = `clara.server.tools.graph.rules.loan-hierarchy-rules/insert-income-document`;
const REVIEW = `clara.server.tools.graph.rules.loan-hierarchy-rules/review-supporting-document`;
const INCOME = `:${NS}/income-document`;
const SUPPORTING = `:${NS}/supporting-document`;

test.describe('Type-bridge :match rows (hierarchy ruleset)', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		// The hierarchy ruleset has a single namespace, so the grouped nav
		// auto-expands and offers no "Expand all" button.
		const expandAll = ui.groupedNav.expandAllButton(page);
		if (await expandAll.isVisible()) await expandAll.click();
	});

	test('downstream entry shows the hierarchy bridge and its types link to the fact-type pages', async ({
		page
	}) => {
		// insert-income-document produces ::income-document, which satisfies
		// review-supporting-document's ::supporting-document LHS via the
		// derive chain — producer and consumer differ (a hierarchy bridge).
		await page.locator(`a.list-group-item[title="${INSERT}"]`).click();
		await expect(ui.summary.title(page, 'insert-income-document')).toBeVisible();

		const downstreamCard = page
			.locator('h6', { hasText: 'downstream' })
			.locator('xpath=ancestor::div[contains(@class,"mb-3")][1]');
		const bridgeItem = downstreamCard
			.locator('div.list-group-item')
			.filter({ hasText: 'review-supporting-document' });
		await expect(bridgeItem).toBeVisible();

		await bridgeItem.locator('button[aria-label="Show type matches"]').click();

		const popover = page.locator('.popover-panel');
		await expect(popover).toBeVisible();

		// Bridge pair: producer ::income-document → consumer ::supporting-document.
		const producerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'income-document' });
		const consumerLink = popover
			.locator('a[href*="/fact-types/"]')
			.filter({ hasText: 'supporting-document' });
		await expect(producerLink).toBeVisible();
		await expect(consumerLink).toBeVisible();
		await expect(popover.getByText('satisfies')).toBeVisible();

		// Both ends of the bridge are known types → the consumer link resolves.
		await consumerLink.click();
		await expect(page).toHaveURL(/\/fact-types\//);
		await expect(
			page.locator('.card-header').filter({ hasText: 'supporting-document' })
		).toBeVisible();
	});
});
