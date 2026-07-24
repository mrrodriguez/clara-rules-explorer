import { test, expect } from '@playwright/test';
import { ui } from './support/ui';

const LOAN_DOC_NS = 'clara.server.tools.graph.rules.loan-doc-rules';
const LOAN_APP_NS = 'clara.server.tools.graph.rules.loan-app-rules';

test.describe('GroupedFilterableNavList — Rules page', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
	});

	// ── Search ────────────────────────────────────────────────────────────

	test('search filters rules in flat mode and clears back to grouped', async ({ page }) => {
		// Initially in grouped mode: 2 namespace group toggles visible
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(2);

		// Type a search that matches exactly 1 rule
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('approved');

		// Group toggles disappear in search mode
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(0);

		// Only the matching rule is visible (flat list)
		const items = page.locator('a.list-group-item');
		await expect(items).toHaveCount(1);
		await expect(items.first()).toContainText('app-outcome-approved');

		// Clear search — back to grouped mode
		await search.fill('');
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(2);
	});

	test('search shows empty state for non-matching term', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('zzzxyz');

		// No items, empty state message visible
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
		await expect(ui.groupedNav.emptyState(page)).toBeVisible();
		await expect(ui.groupedNav.emptyState(page)).toContainText('No matches found for "zzzxyz"');
	});

	test('search is case-insensitive', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('APPROVED');
		await expect(page.locator('a.list-group-item')).toHaveCount(1);
		await expect(page.locator('a.list-group-item').first()).toContainText('app-outcome-approved');
	});

	// ── Namespace groups ──────────────────────────────────────────────────

	test('namespace groups start collapsed with multiple namespaces', async ({ page }) => {
		// 2 namespace group toggles
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(2);

		// Both groups are collapsed — no rule item links visible
		await expect(page.locator('a.list-group-item')).toHaveCount(0);

		// Group toggles show chevron-right (collapsed)
		const docToggle = ui.groupedNav.groupToggle(page, LOAN_DOC_NS);
		const appToggle = ui.groupedNav.groupToggle(page, LOAN_APP_NS);
		await expect(docToggle.locator('i.bi-chevron-right')).toBeVisible();
		await expect(appToggle.locator('i.bi-chevron-right')).toBeVisible();
	});

	test('clicking a group toggle expands it and shows items', async ({ page }) => {
		// Expand the loan-doc-rules group
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();

		// Chevron changes to down
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_DOC_NS).locator('i.bi-chevron-down')
		).toBeVisible();

		// Items appear — 6 rules in this namespace
		await expect(page.locator('a.list-group-item')).toHaveCount(6);
	});

	test('clicking an expanded group toggle collapses it', async ({ page }) => {
		// Expand
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(6);

		// Collapse
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
	});

	test('groups expand/collapse independently', async ({ page }) => {
		// Expand loan-doc-rules only
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(6);

		// loan-app-rules is still collapsed
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_APP_NS).locator('i.bi-chevron-right')
		).toBeVisible();

		// Expand loan-app-rules too
		await ui.groupedNav.groupToggle(page, LOAN_APP_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(9);

		// Collapse only loan-doc-rules
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(3);
	});

	// ── Expand All / Collapse All ─────────────────────────────────────────

	test('Expand all and Collapse all work', async ({ page }) => {
		// All collapsed initially
		await expect(page.locator('a.list-group-item')).toHaveCount(0);

		await ui.groupedNav.expandAllButton(page).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(9);
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_DOC_NS).locator('i.bi-chevron-down')
		).toBeVisible();
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_APP_NS).locator('i.bi-chevron-down')
		).toBeVisible();

		await ui.groupedNav.collapseAllButton(page).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
	});

	// ── Namespace filter dropdown ─────────────────────────────────────────

	test('namespace filter dropdown opens and shows checkboxes', async ({ page }) => {
		await ui.groupedNav.namespaceFilterButton(page).click();

		const dropdown = ui.groupedNav.namespaceFilterDropdown(page);
		await expect(dropdown).toBeVisible();

		// 2 checkboxes + "Show all namespaces" button
		await expect(dropdown.locator('input[type="checkbox"]')).toHaveCount(2);
		await expect(ui.groupedNav.showAllNamespacesButton(page)).toBeVisible();
	});

	test('unchecking a namespace hides its group and updates filter button text', async ({ page }) => {
		// Expand both groups first so we can see items disappear
		await ui.groupedNav.expandAllButton(page).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(9);

		// Open filter and uncheck loan-doc-rules
		await ui.groupedNav.namespaceFilterButton(page).click();
		const docCheckbox = ui.groupedNav.namespaceCheckbox(page, LOAN_DOC_NS).locator('input');
		await docCheckbox.uncheck();

		// Filter button now shows "1 of 2 namespaces"
		await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('1 of 2');

		// Only loan-app-rules group visible
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(1);
		await expect(page.locator('a.list-group-item')).toHaveCount(3);
	});

	test('rechecking a namespace restores it', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();

		// Hide loan-doc-rules
		await ui.groupedNav.namespaceFilterButton(page).click();
		const docCheckbox = ui.groupedNav.namespaceCheckbox(page, LOAN_DOC_NS).locator('input');
		await docCheckbox.uncheck();
		await expect(page.locator('a.list-group-item')).toHaveCount(3);

		// Close the dropdown (click outside) then reopen it
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await ui.groupedNav.namespaceFilterButton(page).click();
		await ui.groupedNav.namespaceCheckbox(page, LOAN_DOC_NS).locator('input').check();
		await expect(page.locator('a.list-group-item')).toHaveCount(9);

		// Filter button back to "All namespaces"
		await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('All namespaces');
	});

	test('"Show all namespaces" button resets filters', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();

		// Hide both namespaces via checkboxes
		await ui.groupedNav.namespaceFilterButton(page).click();
		const checkboxes = ui.groupedNav.namespaceFilterDropdown(page).locator('input[type="checkbox"]');
		const cbCount = await checkboxes.count();
		for (let i = 0; i < cbCount; i++) {
			await checkboxes.nth(i).uncheck();
		}

		// No groups or items visible
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(0);

		// Close the dropdown, then reopen it to click "Show all namespaces"
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await ui.groupedNav.namespaceFilterButton(page).click();
		await ui.groupedNav.showAllNamespacesButton(page).click();

		// All groups restored
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(2);
		await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('All namespaces');
	});

	test('filter dropdown closes on clicking outside', async ({ page }) => {
		await ui.groupedNav.namespaceFilterButton(page).click();
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).toBeVisible();

		// Click on the search input (outside the dropdown)
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).not.toBeVisible();
	});

	// ── Item selection ────────────────────────────────────────────────────

	test('clicking a rule item navigates to its summary', async ({ page }) => {
		// Expand a group to see items
		await ui.groupedNav.groupToggle(page, LOAN_APP_NS).click();

		// Click a specific rule
		const ruleItem = page.locator('a.list-group-item').filter({ hasText: 'app-outcome-approved' });
		await ruleItem.click();

		// Summary header matches the rule
		await expect(ui.summary.title(page, 'app-outcome-approved')).toBeVisible();
		await expect(page.getByText('Select a rule from the list')).not.toBeVisible();
	});

	test('active item is highlighted in the list', async ({ page }) => {
		// Expand a group
		await ui.groupedNav.groupToggle(page, LOAN_APP_NS).click();

		// Click a rule
		await page.locator('a.list-group-item').filter({ hasText: 'app-outcome-denied' }).click();
		await expect(ui.summary.title(page, 'app-outcome-denied')).toBeVisible();

		// The active item in the list has the .active class
		const activeItem = page.locator('a.list-group-item.active');
		await expect(activeItem).toHaveCount(1);
		await expect(activeItem).toContainText('app-outcome-denied');
	});

	// ── Group badge counts ────────────────────────────────────────────────

	test('group headers show correct item counts', async ({ page }) => {
		const docBadge = ui.groupedNav.groupToggle(page, LOAN_DOC_NS).locator('.badge');
		const appBadge = ui.groupedNav.groupToggle(page, LOAN_APP_NS).locator('.badge');

		await expect(docBadge).toContainText('6 rules');
		await expect(appBadge).toContainText('3 rules');
	});
});

test.describe('GroupedFilterableNavList — Single namespace (Queries page)', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Queries');
		await expect(page).toHaveURL(/\/queries/);
	});

	test('single namespace auto-expands with no filter controls', async ({ page }) => {
		// Queries demo data has 2 queries in the same (empty) namespace — "(no namespace)".
		// Single namespace → auto-expanded, no group toggle button, no filter controls.
		// Items are rendered directly as links.
		const items = page.locator('a.list-group-item');
		await expect(items).toHaveCount(2);

		// No namespace filter button (single namespace)
		await expect(ui.groupedNav.namespaceFilterButton(page)).not.toBeVisible();

		// No expand/collapse buttons
		await expect(ui.groupedNav.expandAllButton(page)).not.toBeVisible();
		await expect(ui.groupedNav.collapseAllButton(page)).not.toBeVisible();
	});

	test('search works on single-namespace page', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search queries...');
		await search.fill('find-document');

		await expect(page.locator('a.list-group-item')).toHaveCount(1);
		await expect(page.locator('a.list-group-item').first()).toContainText('find-document-check');
	});

	test('empty state shows for no search matches', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search queries...');
		await search.fill('nonexistent');

		await expect(page.locator('a.list-group-item')).toHaveCount(0);
		await expect(ui.groupedNav.emptyState(page)).toBeVisible();
		await expect(ui.groupedNav.emptyState(page)).toContainText('No matches found for "nonexistent"');
	});
});
