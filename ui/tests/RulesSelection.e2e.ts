import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

test.describe('Rules Navigation and Selection', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
	});

	test('should navigate to rules list and select the first rule', async ({ page }) => {
		// 1. Use the reusable sidebar helper to navigate
		await ui.sidebar.navigateTo(page, 'Rules');

		// 2. Verify we are on the rules page
		await expect(page).toHaveURL(/\/rules/);

		// 3. Select the first rule from the EntityList
		const firstRule = ui.list.firstItem(page);
		const ruleName = await firstRule.textContent();
		
		// Ensure we actually have a rule name before clicking
		expect(ruleName).toBeTruthy();
		
		await firstRule.click();

		// 4. Observe the summary page on the right
		// The summary header should match the rule name we clicked
		// Since ruleName might contain extra text from badges and the namespace, 
		// we take the first part (the simple name)
		const simpleName = ruleName?.trim().split(/\s+/)[0] || '';
		await expect(ui.summary.title(page, simpleName)).toBeVisible();

		// 5. Verify the "Select a rule" hint is gone
		await expect(page.getByText('Select a rule from the list')).not.toBeVisible();
	});
});
