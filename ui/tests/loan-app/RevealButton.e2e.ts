import { test, expect } from '@playwright/test';
import { ui } from '../support/ui';

test.describe('Reveal button', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
	});

	// ── Existence and basic behavior ──────────────────────────────────────

	test('appears enabled on rule summary and expands collapsed namespace', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);

		// Expand all, then click a specific rule to load its summary.
		await ui.groupedNav.expandAll(page);
		const ruleItem = page.locator('a.list-group-item').filter({ hasText: 'collect-app-given-docs' });
		await ruleItem.click();

		// The Reveal button should be visible and enabled.
		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeVisible();
		await expect(reveal).toBeEnabled();

		// Collapse all groups — the active item disappears from the DOM.
		await ui.groupedNav.collapseAll(page);
		await expect(page.locator('a.list-group-item.active')).toHaveCount(0);

		// Click Reveal — it should auto-expand the namespace and scroll to the item.
		await reveal.click();
		const activeItem = page.locator('a.list-group-item.active');
		await expect(activeItem).toBeVisible();
		await expect(activeItem).toContainText('collect-app-given-docs');
	});

	test('appears enabled on query summary', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Queries');
		await expect(page).toHaveURL(/\/queries/);

		// Expand all, then click a specific query.
		await ui.groupedNav.expandAll(page);

		const queryItem = page.locator('a.list-group-item').first();
		await queryItem.click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeVisible();
		await expect(reveal).toBeEnabled();
	});

	test('appears enabled on fact-type summary', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Fact Types');
		await expect(page).toHaveURL(/\/fact-types/);

		// Expand all, then click a fact type.
		await ui.groupedNav.expandAll(page);

		const factTypeItem = page.locator('a.list-group-item').first();
		await factTypeItem.click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeVisible();
		await expect(reveal).toBeEnabled();
	});

	// ── Disabled state: search filters out the active item ────────────────

	test('disabled when search excludes the active rule', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);

		// Expand and pick a rule.
		await ui.groupedNav.expandAll(page);
		await page.locator('a.list-group-item').filter({ hasText: 'collect-app-given-docs' }).click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeEnabled();

		// Search for something that does NOT match the active rule.
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('nonexistentzzz');

		// The Reveal button should now be disabled.
		await expect(reveal).toBeDisabled();

		// Clear search — the button should become enabled again.
		await search.fill('');
		await expect(reveal).toBeEnabled();
	});

	// ── Disabled state: rulebase attribute filter hides the active item ───

	test('disabled when rulebase filter excludes the active rule', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);

		// Pick 'app-outcome-approved?' — it has source-rule: false, so the
		// "Source Rule" filter will hide it.
		await ui.groupedNav.expandAll(page);
		await page
			.locator('a.list-group-item')
			.filter({ hasText: 'app-outcome-approved?' })
			.click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeEnabled();

		// Apply the "Source Rule" filter — should hide extract-doc-meta-rule.
		await ui.groupedNav.filterMenuButton(page).click();
		await expect(ui.groupedNav.filterMenuDropdown(page)).toBeVisible();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();
		// Close dropdown by clicking outside.
		await page.locator('body').click({ position: { x: 0, y: 0 } });

		await expect(reveal).toBeDisabled();

		// Clear filters — the button should become enabled again.
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.clearAllFiltersButton(page).click();
		await expect(reveal).toBeEnabled();
	});

	// ── Disabled state: namespace filter hides the active item ────────────

	test('disabled when namespace filter hides the active namespace', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);

		// Expand and pick a rule in loan-doc-rules namespace.
		await ui.groupedNav.expandAll(page);
		await page
			.locator('a.list-group-item')
			.filter({ hasText: 'collect-app-given-docs' })
			.click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeEnabled();

		// Open namespace filter and click the loan-doc-rules namespace to
		// hide it (first click = exclusive select, hiding all others). Then
		// click the loan-app-rules namespace to make that the only visible one.
		await ui.groupedNav.namespaceFilterButton(page).click();
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).toBeVisible();

		const LOAN_DOC_NS = 'clara.server.tools.graph.rules.loan-doc-rules';
		const LOAN_APP_NS = 'clara.server.tools.graph.rules.loan-app-rules';

		// First click: exclusive select loan-app-rules (hides loan-doc-rules).
		await ui.groupedNav.namespaceToggle(page, LOAN_APP_NS).click();

		// Now loan-doc-rules is hidden, so the active rule's Reveal button should be disabled.
		await expect(reveal).toBeDisabled();

		// Show all namespaces again.
		await ui.groupedNav.showAllNamespacesButton(page).click();
		await expect(reveal).toBeEnabled();
	});

	// ── Session domain ────────────────────────────────────────────────────

	test('appears enabled on session fact-type detail', async ({ page }) => {
		await ui.sidebar.navigateTo(page, 'Session');
		await expect(page).toHaveURL(/\/session/);

		// Expand all, then click a fact type.
		await ui.groupedNav.expandAll(page);

		const factTypeItem = page.locator('a.list-group-item').first();
		await factTypeItem.click();

		const reveal = page.locator('button').filter({ hasText: 'Reveal' });
		await expect(reveal).toBeVisible();
		await expect(reveal).toBeEnabled();
	});
});
