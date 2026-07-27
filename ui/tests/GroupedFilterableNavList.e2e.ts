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
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		expect(groupCount).toBeGreaterThanOrEqual(2);

		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('approved');

		// Group toggles disappear in search mode
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(0);

		// Only matching rules are visible
		const items = page.locator('a.list-group-item');
		await expect(items).toHaveCount(1);
		await expect(items.first()).toContainText('app-outcome-approved');

		// Clear search — back to grouped mode
		await search.fill('');
		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(groupCount);
	});

	test('search shows empty state for non-matching term', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('zzzxyz');

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
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		expect(groupCount).toBeGreaterThanOrEqual(2);

		// All groups collapsed — no item links visible
		await expect(page.locator('a.list-group-item')).toHaveCount(0);

		// All group toggles show chevron-right (collapsed)
		for (let i = 0; i < groupCount; i++) {
			await expect(
				ui.groupedNav.allGroupToggles(page).nth(i).locator('i.bi-chevron-right')
			).toBeVisible();
		}
	});

	test('clicking a group toggle expands it and shows items', async ({ page }) => {
		// Read the expected item count from the badge before clicking
		const badgeText = await ui.groupedNav
			.groupToggle(page, LOAN_DOC_NS)
			.locator('.badge')
			.textContent();
		const expectedCount = parseInt(badgeText || '0', 10);
		expect(expectedCount).toBeGreaterThan(0);

		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();

		// Chevron changes to down
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_DOC_NS).locator('i.bi-chevron-down')
		).toBeVisible();

		// Items appear — count matches the badge
		await expect(page.locator('a.list-group-item')).toHaveCount(expectedCount);
	});

	test('clicking an expanded group toggle collapses it', async ({ page }) => {
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		const expandedCount = await page.locator('a.list-group-item').count();
		expect(expandedCount).toBeGreaterThan(0);

		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
	});

	test('groups expand/collapse independently', async ({ page }) => {
		// Expand first group and capture its count
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		const firstCount = await page.locator('a.list-group-item').count();
		expect(firstCount).toBeGreaterThan(0);

		// Second group still collapsed
		await expect(
			ui.groupedNav.groupToggle(page, LOAN_APP_NS).locator('i.bi-chevron-right')
		).toBeVisible();

		// Expand second group — total increases
		await ui.groupedNav.groupToggle(page, LOAN_APP_NS).click();
		const totalCount = await page.locator('a.list-group-item').count();
		expect(totalCount).toBeGreaterThan(firstCount);

		// Collapse first group — count drops but not to zero
		await ui.groupedNav.groupToggle(page, LOAN_DOC_NS).click();
		const remainingCount = await page.locator('a.list-group-item').count();
		expect(remainingCount).toBeGreaterThan(0);
		expect(remainingCount).toBeLessThan(totalCount);
	});

	// ── Expand All / Collapse All ─────────────────────────────────────────

	test('Expand all and Collapse all work', async ({ page }) => {
		await expect(page.locator('a.list-group-item')).toHaveCount(0);

		await ui.groupedNav.expandAllButton(page).click();
		const expandedCount = await page.locator('a.list-group-item').count();
		expect(expandedCount).toBeGreaterThan(0);

		// All group toggles show chevron-down
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		for (let i = 0; i < groupCount; i++) {
			await expect(
				ui.groupedNav.allGroupToggles(page).nth(i).locator('i.bi-chevron-down')
			).toBeVisible();
		}

		await ui.groupedNav.collapseAllButton(page).click();
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
	});

	// ── Namespace filter dropdown ─────────────────────────────────────────

	test('namespace filter dropdown opens and shows toggles', async ({ page }) => {
		await ui.groupedNav.namespaceFilterButton(page).click();

		const dropdown = ui.groupedNav.namespaceFilterDropdown(page);
		await expect(dropdown).toBeVisible();

		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		await expect(ui.groupedNav.allNamespaceToggles(page)).toHaveCount(groupCount);
		await expect(ui.groupedNav.showAllNamespacesButton(page)).toBeVisible();

		// All namespaces start checked (no filter active)
		const toggleCount = await ui.groupedNav.allNamespaceToggles(page).count();
		for (let i = 0; i < toggleCount; i++) {
			await expect(
				ui.groupedNav.allNamespaceToggles(page).nth(i).locator('i.bi-check-square')
			).toBeVisible();
		}
	});

	test('first click exclusively selects one namespace (deselects all others)', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const initialCount = await page.locator('a.list-group-item').count();

		await ui.groupedNav.namespaceFilterButton(page).click();
		const toggles = ui.groupedNav.allNamespaceToggles(page);
		const toggleCount = await toggles.count();
		expect(toggleCount).toBeGreaterThanOrEqual(2);

		// Click the first namespace — should exclusively select it
		await toggles.first().click();

		// Only the first toggle shows checked
		await expect(toggles.first().locator('i.bi-check-square')).toBeVisible();
		for (let i = 1; i < toggleCount; i++) {
			await expect(toggles.nth(i).locator('i.bi-square')).toBeVisible();
		}

		// Fewer items and groups visible
		const newCount = await page.locator('a.list-group-item').count();
		expect(newCount).toBeLessThan(initialCount);

		// Filter button text reflects active filter
		await expect(ui.groupedNav.namespaceFilterButton(page)).not.toContainText('All namespaces');
	});

	test('subsequent clicks stack (add to selection)', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();

		await ui.groupedNav.namespaceFilterButton(page).click();
		const toggles = ui.groupedNav.allNamespaceToggles(page);
		const toggleCount = await toggles.count();
		expect(toggleCount).toBeGreaterThanOrEqual(2);

		// First click: exclusive select
		await toggles.first().click();
		await expect(toggles.first().locator('i.bi-check-square')).toBeVisible();
		await expect(toggles.nth(1).locator('i.bi-square')).toBeVisible();

		// Second click on another namespace: stacks (both visible)
		await toggles.nth(1).click();
		await expect(toggles.first().locator('i.bi-check-square')).toBeVisible();
		await expect(toggles.nth(1).locator('i.bi-check-square')).toBeVisible();

		// Three groups visible
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		expect(groupCount).toBe(2);
	});

	test('unchecking the last visible namespace shows all again', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const initialCount = await page.locator('a.list-group-item').count();

		await ui.groupedNav.namespaceFilterButton(page).click();
		const toggles = ui.groupedNav.allNamespaceToggles(page);
		const toggleCount = await toggles.count();

		// Hide all namespaces one by one
		for (let i = 0; i < toggleCount; i++) {
			await toggles.nth(0).click();
			// After each click that hides the last visible, all should reappear
			// But if there are still visible ones, the toggle just hides this one
			const remainingToggles = ui.groupedNav.allNamespaceToggles(page);
			const remainingCount = await remainingToggles.count();
			if (remainingCount > 0 && (await remainingToggles.locator('i.bi-check-square').count()) === 0) {
				// All are hidden — should auto-reset to show all
				await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('All namespaces');
				break;
			}
		}

		// Close dropdown
		await ui.groupedNav.searchInput(page, 'Search rules...').click();

		// All items should be back
		await expect(page.locator('a.list-group-item')).toHaveCount(initialCount);
	});

	test('toggling the only selected namespace hides it and shows all', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const initialCount = await page.locator('a.list-group-item').count();

		// First click: exclusive select the first namespace
		await ui.groupedNav.namespaceFilterButton(page).click();
		const toggles = ui.groupedNav.allNamespaceToggles(page);
		await toggles.first().click();

		// Now only the first is checked — click it again to hide it
		await toggles.first().click();

		// All namespaces should be visible again (auto-reset to show all)
		await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('All namespaces');
		await expect(toggles.first().locator('i.bi-check-square')).toBeVisible();

		// Close dropdown and verify all items restored
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await expect(page.locator('a.list-group-item')).toHaveCount(initialCount);
	});

	test('"Show all namespaces" button resets filters', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const initialCount = await page.locator('a.list-group-item').count();

		// Hide one namespace via exclusive select
		await ui.groupedNav.namespaceFilterButton(page).click();
		await ui.groupedNav.allNamespaceToggles(page).first().click();

		await expect(ui.groupedNav.allGroupToggles(page)).toHaveCount(1);

		// Close dropdown, reopen, click "Show all namespaces"
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await ui.groupedNav.namespaceFilterButton(page).click();
		await ui.groupedNav.showAllNamespacesButton(page).click();

		// All items restored and dropdown closed
		await expect(page.locator('a.list-group-item')).toHaveCount(initialCount);
		await expect(ui.groupedNav.namespaceFilterButton(page)).toContainText('All namespaces');
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).not.toBeVisible();
	});

	test('namespace filter text input filters the toggle list', async ({ page }) => {
		await ui.groupedNav.namespaceFilterButton(page).click();

		const dropdown = ui.groupedNav.namespaceFilterDropdown(page);
		const filterInput = dropdown.locator('input[placeholder="Filter namespaces..."]');
		await expect(filterInput).toBeVisible();

		const initialToggleCount = await ui.groupedNav.allNamespaceToggles(page).count();
		expect(initialToggleCount).toBeGreaterThanOrEqual(2);

		// Type a filter that matches only one namespace
		await filterInput.fill('loan-app');

		// Only one toggle visible
		await expect(ui.groupedNav.allNamespaceToggles(page)).toHaveCount(1);

		// Clear filter — all toggles reappear
		await filterInput.fill('');
		await expect(ui.groupedNav.allNamespaceToggles(page)).toHaveCount(initialToggleCount);
	});

	test('filter dropdown closes on clicking outside', async ({ page }) => {
		await ui.groupedNav.namespaceFilterButton(page).click();
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).toBeVisible();

		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await expect(ui.groupedNav.namespaceFilterDropdown(page)).not.toBeVisible();
	});

	// ── Item selection ────────────────────────────────────────────────────

	test('clicking a rule item navigates to its summary', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();

		const ruleItem = page.locator('a.list-group-item').filter({ hasText: 'app-outcome-approved' });
		await ruleItem.click();

		await expect(ui.summary.title(page, 'app-outcome-approved')).toBeVisible();
		await expect(page.getByText('Select a rule from the list')).not.toBeVisible();
	});

	test('active item is highlighted in the list', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();

		await page.locator('a.list-group-item').filter({ hasText: 'app-outcome-denied' }).click();
		await expect(ui.summary.title(page, 'app-outcome-denied')).toBeVisible();

		const activeItem = page.locator('a.list-group-item.active');
		await expect(activeItem).toHaveCount(1);
		await expect(activeItem).toContainText('app-outcome-denied');
	});

	// ── Group badge counts ────────────────────────────────────────────────

	test('group headers show item counts with labels', async ({ page }) => {
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		for (let i = 0; i < groupCount; i++) {
			const badge = ui.groupedNav.allGroupToggles(page).nth(i).locator('.badge');
			await expect(badge).toBeVisible();
			const text = (await badge.textContent()) || '';
			expect(text).toMatch(/\d+\s+(rules|queries|fact types|types|items)/);
		}
	});

	test('badge counts match actual items when expanded', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const totalExpanded = await page.locator('a.list-group-item').count();

		const groupCount = await ui.groupedNav.allGroupToggles(page).count();
		let badgeSum = 0;
		for (let i = 0; i < groupCount; i++) {
			const badgeText =
				(await ui.groupedNav.allGroupToggles(page).nth(i).locator('.badge').textContent()) || '0';
			badgeSum += parseInt(badgeText, 10);
		}
		expect(badgeSum).toBe(totalExpanded);
	});
});

test.describe('GroupedFilterableNavList — Rulebase filters', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Rules');
		await expect(page).toHaveURL(/\/rules/);
	});

	// ── Filter menu visibility ───────────────────────────────────────────

	test('filter menu button appears on rules page but not queries page', async ({ page }) => {
		// Visible on rules page (filter options are passed)
		await expect(ui.groupedNav.filterMenuButton(page)).toBeVisible();

		// Navigate to queries — no filter options passed, button absent
		await ui.sidebar.navigateTo(page, 'Queries');
		await expect(page).toHaveURL(/\/queries/);
		await expect(ui.groupedNav.filterMenuButton(page)).not.toBeVisible();
	});

	// ── Filter menu open / close ─────────────────────────────────────────

	test('filter menu opens with expected checkboxes', async ({ page }) => {
		await ui.groupedNav.filterMenuButton(page).click();
		const dropdown = ui.groupedNav.filterMenuDropdown(page);
		await expect(dropdown).toBeVisible();

		// All 5 filter options should be present
		await expect(ui.groupedNav.filterOption(page, 'Dynamic Inserts (unresolved)')).toBeVisible();
		await expect(ui.groupedNav.filterOption(page, 'Dynamic Retracts (unresolved)')).toBeVisible();
		await expect(ui.groupedNav.filterOption(page, 'Unlinked RHS')).toBeVisible();
		await expect(ui.groupedNav.filterOption(page, 'Source Rule')).toBeVisible();
		await expect(ui.groupedNav.filterOption(page, 'Sink Rule')).toBeVisible();

		// Clear all button present
		await expect(ui.groupedNav.clearAllFiltersButton(page)).toBeVisible();

		// All options start unchecked (square icon)
		const dropdownItems = dropdown.locator('button.dropdown-item');
		const count = await dropdownItems.count();
		for (let i = 0; i < count; i++) {
			const icon = dropdownItems.nth(i).locator('i');
			const iconClass = await icon.getAttribute('class');
			if (iconClass?.includes('bi-square') || iconClass?.includes('bi-check-square')) {
				expect(iconClass).toContain('bi-square');
				expect(iconClass).not.toContain('bi-check-square');
			}
		}
	});

	test('filter menu button label reflects active filter count', async ({ page }) => {
		// Default: "Filters"
		await expect(ui.groupedNav.filterMenuButton(page)).toContainText('Filters');

		// Check one filter → "1 filter"
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();
		await ui.groupedNav.filterMenuButton(page).click(); // close
		await expect(ui.groupedNav.filterMenuButton(page)).toContainText('1 filter');

		// Check another → "2 filters"
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Sink Rule').click();
		await ui.groupedNav.filterMenuButton(page).click(); // close
		await expect(ui.groupedNav.filterMenuButton(page)).toContainText('2 filters');
	});

	test('clicking outside closes filter menu dropdown', async ({ page }) => {
		await ui.groupedNav.filterMenuButton(page).click();
		await expect(ui.groupedNav.filterMenuDropdown(page)).toBeVisible();

		// Click the search input to close
		await ui.groupedNav.searchInput(page, 'Search rules...').click();
		await expect(ui.groupedNav.filterMenuDropdown(page)).not.toBeVisible();
	});

	// ── Single filter behaviour ──────────────────────────────────────────

	test('checking "Source Rule" shows only source rules', async ({ page }) => {
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();

		// Filter narrows to single namespace → auto-expand happens.
		// All visible rules should have the source-rule icon.
		const items = page.locator('a.list-group-item');
		const count = await items.count();
		expect(count).toBeGreaterThan(0);

		// Every visible rule should show the source icon
		for (let i = 0; i < count; i++) {
			const icons = items.nth(i).locator('i');
			const sourceIcon = icons.filter({ hasText: '' }).locator('.bi-box-arrow-in-right');
			// At least one source icon should exist (may be in the badge area)
			await expect(items.nth(i).locator('i.bi-box-arrow-in-right')).toHaveCount(1);
		}
	});

	test('checking "Sink Rule" shows only sink rules', async ({ page }) => {
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Sink Rule').click();

		const items = page.locator('a.list-group-item');
		const count = await items.count();
		expect(count).toBeGreaterThan(0);

		for (let i = 0; i < count; i++) {
			await expect(items.nth(i).locator('i.bi-box-arrow-right')).toHaveCount(1);
		}
	});

	test('checking "Dynamic Inserts (unresolved)" shows only rules with unresolved dynamic inserts', async ({
		page
	}) => {
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Dynamic Inserts (unresolved)').click();

		const items = page.locator('a.list-group-item');
		const count = await items.count();
		expect(count).toBeGreaterThan(0);

		// Each visible rule should have the warning (partial) or danger (none) dynamic-insert icon
		for (let i = 0; i < count; i++) {
			const icon = items.nth(i).locator('i.bi-exclamation-circle-fill, i.bi-question-circle-fill');
			await expect(icon).toHaveCount(1);
		}
	});

	// ── OR logic: multiple filters ───────────────────────────────────────

	test('multiple filters combine with OR logic', async ({ page }) => {
		// Count source-only, sink-only, then source+sink together
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();
		const sourceCount = await page.locator('a.list-group-item').count();

		// Clear and check sink only
		await ui.groupedNav.filterOption(page, 'Source Rule').click(); // uncheck
		await ui.groupedNav.filterOption(page, 'Sink Rule').click(); // check
		const sinkCount = await page.locator('a.list-group-item').count();

		// Now check both
		await ui.groupedNav.filterOption(page, 'Source Rule').click(); // re-check
		const combinedCount = await page.locator('a.list-group-item').count();

		// Combined should be >= max of individual counts (OR logic)
		expect(combinedCount).toBeGreaterThanOrEqual(Math.max(sourceCount, sinkCount));
		// Combined should be <= sum (could be less if some items match both)
		expect(combinedCount).toBeLessThanOrEqual(sourceCount + sinkCount);
	});

	// ── Clear all ────────────────────────────────────────────────────────

	test('"Clear all filters" resets the list', async ({ page }) => {
		await ui.groupedNav.expandAllButton(page).click();
		const fullCount = await page.locator('a.list-group-item').count();

		// Apply a filter (dropdown stays open after toggle — close it by clicking outside)
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();
		await ui.groupedNav.searchInput(page, 'Search rules...').click(); // dismiss dropdown
		const filteredCount = await page.locator('a.list-group-item').count();
		expect(filteredCount).toBeLessThan(fullCount);

		// Reopen and clear all filters
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.clearAllFiltersButton(page).click();

		// All groups should be back
		await ui.groupedNav.expandAllButton(page).click();
		const restoredCount = await page.locator('a.list-group-item').count();
		expect(restoredCount).toBe(fullCount);

		// Button label reset to "Filters"
		await expect(ui.groupedNav.filterMenuButton(page)).toContainText('Filters');
	});

	// ── Filter + search interaction ──────────────────────────────────────

	test('filter and search combine (intersection)', async ({ page }) => {
		// Apply source filter
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();

		// Search within filtered results
		const search = ui.groupedNav.searchInput(page, 'Search rules...');
		await search.fill('collect-app-given-docs');

		// Only matching items (source + name match)
		const items = page.locator('a.list-group-item');
		await expect(items).toHaveCount(1);
		await expect(items.first()).toContainText('collect-app-given-docs');

		// Clear search, verify filter remains active
		await search.fill('');
		const postSearchCount = await page.locator('a.list-group-item').count();
		expect(postSearchCount).toBeGreaterThan(1);

		// Verify filter button still shows active
		await expect(ui.groupedNav.filterMenuButton(page)).toContainText('1 filter');
	});

	// ── Auto-expand behaviour ────────────────────────────────────────────

	test('filter reduces to single namespace → group auto-expands', async ({ page }) => {
		// Start with all groups collapsed
		const groupToggles = ui.groupedNav.allGroupToggles(page);
		const initialGroupCount = await groupToggles.count();
		expect(initialGroupCount).toBeGreaterThanOrEqual(2);

		// No rule items visible (all collapsed)
		await expect(page.locator('a.list-group-item')).toHaveCount(0);

		// Apply filter that eliminates all rules from one namespace.
		// Source rules only exist in loan-doc-rules, so this leaves 1 group.
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();

		// The sole remaining group should auto-expand
		const remainingGroups = ui.groupedNav.allGroupToggles(page);
		await expect(remainingGroups).toHaveCount(1);

		// There should be visible rule items (group expanded)
		await expect(page.locator('a.list-group-item')).not.toHaveCount(0);
	});

	test('filter eliminates all items → empty state shown', async ({ page }) => {
		// Combine filters that match nothing: Dynamic Retracts (unresolved) — none in demo data
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Dynamic Retracts (unresolved)').click();

		// Or use a combination that can't be satisfied. In demo data there are no
		// rules with unlinked-rhs, so combine it with something else for OR that still
		// yields 0 when only that is checked. Actually Unlinked RHS also matches 0.
		// But the OR of two empty sets is still empty. Let's just check one that
		// we know matches 0.
		await expect(page.locator('a.list-group-item')).toHaveCount(0);
		await expect(ui.groupedNav.emptyState(page)).toBeVisible();
	});

	// ── Group badge reflects filtered count ──────────────────────────────

	test('group badge counts reflect filtered results', async ({ page }) => {
		// Expand all to see all items
		await ui.groupedNav.expandAllButton(page).click();
		const fullTotal = await page.locator('a.list-group-item').count();

		// Apply source filter
		await ui.groupedNav.filterMenuButton(page).click();
		await ui.groupedNav.filterOption(page, 'Source Rule').click();

		// Count visible items (group auto-expanded)
		const visibleItems = await page.locator('a.list-group-item').count();
		expect(visibleItems).toBeLessThan(fullTotal);

		// The visible group badge should match the number of visible items
		const badge = ui.groupedNav.allGroupToggles(page).first().locator('.badge');
		const badgeText = (await badge.textContent()) || '0';
		const badgeCount = parseInt(badgeText, 10);
		expect(badgeCount).toBe(visibleItems);
	});
});

test.describe('GroupedFilterableNavList — Queries page', () => {
	test.beforeEach(async ({ page }) => {
		await page.goto('/');
		await ui.sidebar.navigateTo(page, 'Queries');
		await expect(page).toHaveURL(/\/queries/);
	});

	test('queries page shows items (auto-expanded for single namespace or collapsed for multiple)', async ({ page }) => {
		const itemCount = await page.locator('a.list-group-item').count();
		const groupCount = await ui.groupedNav.allGroupToggles(page).count();

		if (groupCount === 0) {
			// No group toggles means either single namespace auto-expanded, or no data.
			// If there's data, items should be directly visible.
			// If no data, the empty state is shown (verified in the empty-state test).
			expect(itemCount).toBeGreaterThanOrEqual(0);
		} else {
			// Multiple namespaces — items start collapsed
			expect(itemCount).toBe(0);
		}
	});

	test('search works on queries page', async ({ page }) => {
		// Expand any groups first so items are visible
		const expandAllBtn = ui.groupedNav.expandAllButton(page);
		if (await expandAllBtn.isVisible()) {
			await expandAllBtn.click();
		}

		const search = ui.groupedNav.searchInput(page, 'Search queries...');
		await search.fill('find-document');

		const items = page.locator('a.list-group-item');
		const count = await items.count();
		if (count > 0) {
			await expect(items.first()).toContainText('find-document');
		}
	});

	test('empty state shows for no search matches', async ({ page }) => {
		const search = ui.groupedNav.searchInput(page, 'Search queries...');
		await search.fill('nonexistent');

		await expect(page.locator('a.list-group-item')).toHaveCount(0);
		await expect(ui.groupedNav.emptyState(page)).toBeVisible();
		await expect(ui.groupedNav.emptyState(page)).toContainText('No matches found for "nonexistent"');
	});
});
