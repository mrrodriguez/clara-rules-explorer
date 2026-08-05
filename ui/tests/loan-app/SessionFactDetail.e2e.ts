import { test, expect } from '@playwright/test';

test.describe('Session fact detail', () => {
	test('title-bar name copies to clipboard; arrow button navigates to the fact type', async ({
		page,
		context
	}) => {
		await context.grantPermissions(['clipboard-read', 'clipboard-write']);

		// Fact 2 is a root GivenDocument with two consumers.  (Fact 1 is the
		// root Application — fact ids follow the snapshot's rulebase-order
		// sort, and the demo working memory inserts Application first.)
		await page.goto('/session/facts/2');
		await expect(page.locator('h5', { hasText: 'Fact 2' })).toBeVisible();

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

		// The target page actually resolved — the fact-type summary header
		// rendered, rather than the "not found" fallback.  GivenDocument is a
		// rulebase-known type, so its id is a real route.
		await expect(page.locator('.card-header').filter({ hasText: 'GivenDocument' })).toBeVisible();
	});
});
