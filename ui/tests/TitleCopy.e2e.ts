import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

test.describe('Summary title copy', () => {
	test('clicking the summary title copies the fully qualified name to clipboard', async ({
		page,
		context
	}) => {
		await context.grantPermissions(['clipboard-read', 'clipboard-write']);

		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);

		// Open a rule summary.
		await ui.groupedNav.expandAllButton(page).click();
		const ruleName = 'collect-app-given-docs';
		await page.locator('a.list-group-item').filter({ hasText: ruleName }).click();
		await expect(ui.summary.title(page, ruleName)).toBeVisible();

		// The title is a copy button with an explanatory tooltip.
		const titleBtn = page.locator('button.copyable-title').first();
		await expect(titleBtn).toHaveAttribute('title', 'Click to copy fully qualified name');

		// Click — the tooltip flips to confirm, and the FQ name lands on the clipboard.
		await titleBtn.click();
		await expect(titleBtn).toHaveAttribute('title', 'Copied to clipboard');

		const clipboard = await page.evaluate(() => navigator.clipboard.readText());
		expect(clipboard).toMatch(/collect-app-given-docs$/);
	});
});
