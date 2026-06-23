import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

test.describe('Rule Indicators — Unlinked vs No-Output', () => {
	const UNLINKED_RULE = 'collect-app-id-card-given-docs';
	const NO_OUTPUT_RULE = 'collect-all-missing-required-docs';

	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
	});

	test('should show "Unlinked RHS" warning badge on rule summary for unlinked rules', async ({ page }) => {
		// 1. Find and click the unlinked rule in the list
		const ruleItem = ui.list.item(page, UNLINKED_RULE);
		await expect(ruleItem).toBeVisible();
		await ruleItem.click();

		// 2. Verify the summary header shows the unlinked RHS warning badge
		const badge = ui.indicators.unlinkedBadge(page);
		await expect(badge).toBeVisible();
		await expect(badge).toContainText('Unlinked RHS');

		// 3. Verify tooltip text indicates the reason
		await expect(badge).toHaveAttribute('title', /no declared insert-types or retract-types/);

		// 4. Verify the "Select a rule" hint is gone
		await expect(page.getByText('Select a rule from the list')).not.toBeVisible();
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

	test('should show correct icons in rule list for both indicator types', async ({ page }) => {
		// 1. Verify unlinked rule shows warning icon in the list
		const unlinkedItem = ui.list.item(page, UNLINKED_RULE);
		await expect(unlinkedItem.locator('i.bi-exclamation-triangle-fill.text-warning')).toBeVisible();

		// 2. Verify no-output rule shows sign-stop icon in the list
		const noOutputItem = ui.list.item(page, NO_OUTPUT_RULE);
		await expect(noOutputItem.locator('i.bi-sign-stop.text-secondary')).toBeVisible();
	});

	test('should not show unlinked badge on no-output rule summary', async ({ page }) => {
		// 1. Click the no-output rule
		const ruleItem = ui.list.item(page, NO_OUTPUT_RULE);
		await ruleItem.click();

		// 2. Verify No Output badge is visible
		await expect(ui.indicators.noOutputBadge(page)).toBeVisible();

		// 3. Verify unlinked RHS badge is NOT visible
		await expect(ui.indicators.unlinkedBadge(page)).not.toBeVisible();
	});

	test('should not show No Output badge on unlinked rule summary', async ({ page }) => {
		// 1. Click the unlinked rule
		const ruleItem = ui.list.item(page, UNLINKED_RULE);
		await ruleItem.click();

		// 2. Verify unlinked RHS badge is visible
		await expect(ui.indicators.unlinkedBadge(page)).toBeVisible();

		// 3. Verify No Output badge is NOT visible
		await expect(ui.indicators.noOutputBadge(page)).not.toBeVisible();
	});
});
