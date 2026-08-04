import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

test.describe('Dependency item buttons — tooltips', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		await ui.groupedNav.expandAllButton(page).click();
	});

	test('upstream items show tooltips on the info and jump buttons', async ({ page }) => {
		// app-outcome-pending? has upstreams with type matches.
		await page.locator('a.list-group-item').filter({ hasText: 'app-outcome-pending?' }).click();
		await expect(ui.summary.title(page, 'app-outcome-pending?')).toBeVisible();

		const upstreamCard = page
			.locator('h6', { hasText: 'upstream' })
			.locator('xpath=ancestor::div[contains(@class,"mb-3")][1]');
		const firstItem = upstreamCard.locator('div.list-group-item').first();

		// Info button tooltip names the match count.
		const infoBtn = firstItem.locator('button[aria-label="Show type matches"]');
		await infoBtn.hover();
		const infoTip = page.locator('.truncation-tooltip');
		await expect(infoTip).toBeVisible();
		await expect(infoTip).toHaveText(/Show type matches \(\d+\)/);

		// Move the mouse away so the tooltip clears before the next hover.
		await page.mouse.move(5, 5);
		await expect(infoTip).toHaveCount(0);

		// Jump button tooltip names the production (short name).
		const jumpBtn = firstItem.locator('a[aria-label^="Open "]');
		await jumpBtn.hover();
		await expect(infoTip).toBeVisible();
		await expect(infoTip).toHaveText(/^Open .+$/);
		// The tooltip shows a short name, not a full qualified path.
		const tipText = (await infoTip.textContent()) ?? '';
		expect(tipText.split('/').length).toBeLessThanOrEqual(2);
		expect(tipText.split('.').length).toBeLessThanOrEqual(3);
	});
});
