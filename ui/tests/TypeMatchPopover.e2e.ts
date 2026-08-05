import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

test.describe('Type match popover on dependency rows', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		await ui.groupedNav.expandAllButton(page).click();
	});

	test('opens, lists match types as fact-type links, and navigates on click', async ({
		page
	}) => {
		// app-outcome-pending? has upstream entries carrying :match type pairs.
		await page.locator('a.list-group-item').filter({ hasText: 'app-outcome-pending?' }).click();
		await expect(ui.summary.title(page, 'app-outcome-pending?')).toBeVisible();

		const upstreamCard = page
			.locator('h6', { hasText: 'upstream' })
			.locator('xpath=ancestor::div[contains(@class,"mb-3")][1]');
		const firstItem = upstreamCard.locator('div.list-group-item').first();

		// Open the match popover from the link icon button.
		const infoBtn = firstItem.locator('button[aria-label="Show type matches"]');
		await expect(infoBtn).toBeVisible();
		await infoBtn.click();

		const popover = page.locator('.popover-panel');
		await expect(popover).toBeVisible();
		await expect(popover.locator('h6')).toContainText('Type matches');

		// Match values are TypeReferences — design 2b makes the rows directly
		// linkable: each producer/consumer name is a link to its fact type.
		const typeLinks = popover.locator('a[href*="/fact-types/"]');
		await expect(typeLinks.first()).toBeVisible();
		expect(await typeLinks.count()).toBeGreaterThanOrEqual(2);

		// Clicking a match type navigates to its fact-type detail.
		await typeLinks.first().click();
		await expect(page).toHaveURL(/\/fact-types\//);
		await expect(page.locator('.card-header').first()).toBeVisible();
	});
});
