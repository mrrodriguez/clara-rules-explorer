import { test, expect } from '@playwright/test';

test.describe('Session fact detail', () => {
	test('title-bar name copies to clipboard; arrow button navigates to the fact type', async ({
		page,
		context
	}) => {
		await context.grantPermissions(['clipboard-read', 'clipboard-write']);

		// Fact 1 is a root GivenDocument with two consumers.
		await page.goto('/session/facts/1');
		await expect(page.locator('h5', { hasText: 'Fact 1' })).toBeVisible();

		// Clicking the name copies the fact type name (cohesive with summary titles).
		const titleBtn = page.locator('button.copyable-title');
		await expect(titleBtn).toBeVisible();
		await titleBtn.click();
		await expect(titleBtn).toHaveAttribute('title', 'Copied to clipboard');
		const clipboard = await page.evaluate(() => navigator.clipboard.readText());
		expect(clipboard).toContain('GivenDocument');

		// The arrow button navigates to the fact-type view.
		const typeLink = page.locator('a[aria-label="View fact type"]');
		await expect(typeLink).toHaveAttribute('href', /\/fact-types\//);
		await typeLink.click();
		await expect(page).toHaveURL(/\/fact-types\//);
	});
});
