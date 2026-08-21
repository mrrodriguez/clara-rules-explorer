import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

test.describe('Type match popover on dependency rows', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		await ui.groupedNav.expandAll(page);
	});

	test('opens, lists match types as fact-type links, and navigates on click', async ({ page }) => {
		// app-outcome-pending? has upstream entries carrying :match type pairs.
		await page.locator('a.list-group-item').filter({ hasText: 'app-outcome-pending?' }).click();
		await expect(ui.summary.title(page, 'app-outcome-pending?')).toBeVisible();

		await ui.summary.dependenciesTab(page).click();

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

		// app-outcome-approved? satisfies app-outcome-pending? with the exact
		// same type, so the popover shows the type standalone — no "satisfies"
		// bridge for a direct (non-hierarchy) match.
		// FactTypeInlineLink renders both a name link and an open-icon link to
		// the same fact type, so filter to the primary name links for the count.
		const typeLinks = popover.locator('a.text-decoration-none[href*="/fact-types/"]');
		await expect(typeLinks).toHaveCount(1);
		await expect(typeLinks.first()).toBeVisible();
		await expect(popover.getByText('satisfies')).toHaveCount(0);

		// Clicking the standalone match type navigates to its fact-type detail.
		await typeLinks.first().click();
		await expect(page).toHaveURL(/\/fact-types\//);
		await expect(page.locator('.card-header').first()).toBeVisible();
	});
});
