import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

test.describe('Rule Indicators — Unlinked vs No-Output', () => {
	const NO_OUTPUT_RULE = 'collect-all-missing-required-docs';

	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
		// Groups start collapsed — expand all to make items visible
		await ui.groupedNav.expandAll(page);
	});

	test('should show "No Output" muted badge on rule summary for no-output-type rules', async ({ page }) => {
		// 1. Find and click the no-output-types rule in the list
		const ruleItem = ui.list.item(page, NO_OUTPUT_RULE);
		await expect(ruleItem).toBeVisible();
		await ruleItem.click();

		// 2. Verify the summary header shows the No Output muted badge
		const badge = ui.indicators.noOutputBadge(page);
		await expect(badge).toBeVisible();
		await expect(badge).toContainText('No Output');

		// 3. Verify tooltip text confirms the rule has been reviewed
		await expect(badge).toHaveAttribute('title', /reviewed.*no downstream/);

		// 4. Verify the "Select a rule" hint is gone
		await expect(page.getByText('Select a rule from the list')).not.toBeVisible();
	});

	test('should show correct icon in rule list for no-output indicator', async ({ page }) => {
		// Verify no-output rule shows sign-stop icon in the list
		const noOutputItem = ui.list.item(page, NO_OUTPUT_RULE);
		await expect(noOutputItem.locator('i.bi-sign-stop.text-secondary')).toBeVisible();
	});

	test('should not show unlinked badge on no-output rule summary', async ({ page }) => {
		// Click the no-output rule
		const ruleItem = ui.list.item(page, NO_OUTPUT_RULE);
		await ruleItem.click();

		// Verify No Output badge is visible
		await expect(ui.indicators.noOutputBadge(page)).toBeVisible();

		// Verify unlinked RHS badge is NOT visible
		await expect(ui.indicators.unlinkedBadge(page)).not.toBeVisible();
	});
});
