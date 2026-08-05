import type { Page } from '@playwright/test';

/**
 * Reusable UI helpers for Playwright tests.
 * Follows semantic practices by preferring roles and ARIA labels.
 */
export const ui = {
	sidebar: {
		/** Navigates to a top-level page using the sidebar link */
		async navigateTo(page: Page, label: 'Dashboard' | 'Rules' | 'Queries' | 'Fact Types' | 'Session') {
			// Use filter with hasText to robustly match the label regardless of icons/whitespace
			await page.locator('aside.sidebar').getByRole('link').filter({ hasText: label }).click();
		}
	},
	list: {
		/** Returns a locator for an item in an EntityList by its name (title attribute) */
		item(page: Page, name: string) {
			return page.locator('a.list-group-item').filter({ hasText: name });
		},
		/** Returns the first item in an EntityList */
		firstItem(page: Page) {
			return page.locator('a.list-group-item').first();
		}
	},
	summary: {
		/** Returns the title element in the summary card (which is a div, not a heading) */
		title(page: Page, name: string) {
			return page.locator('.card-header').filter({ hasText: name });
		}
	},
	indicators: {
		/** Returns the unlinked RHS warning badge in the rule summary header */
		unlinkedBadge(page: Page) {
			return page.locator('.card-header .badge.text-bg-warning').filter({ hasText: 'Unlinked RHS' });
		},
		/** Returns the unlinked RHS warning icon in the rule list */
		unlinkedIcon(page: Page) {
			return page.locator('.list-group-item i.bi-exclamation-triangle-fill.text-warning');
		},
		/** Returns the No Output muted badge in the rule summary header */
		noOutputBadge(page: Page) {
			return page.locator('.card-header .badge.text-bg-secondary').filter({ hasText: 'No Output' });
		},
		/** Returns the No Output muted icon in the rule list */
		noOutputIcon(page: Page) {
			return page.locator('.list-group-item i.bi-sign-stop.text-secondary');
		}
	},
	groupedNav: {
		/** The search/filter input */
		searchInput(page: Page, placeholder: string) {
			return page.locator(`input[placeholder="${placeholder}"]`);
		},
		/** The namespace multi-select filter dropdown toggle button */
		namespaceFilterButton(page: Page) {
			return page.locator('button.btn-outline-secondary').filter({ has: page.locator('i.bi-funnel') });
		},
		/** The namespace filter dropdown menu (when open) */
		namespaceFilterDropdown(page: Page) {
			return page.locator('.dropdown-menu.show');
		},
		/** A namespace toggle button inside the filter dropdown */
		namespaceToggle(page: Page, nsLabel: string) {
			return page.locator('.dropdown-menu.show button[data-ns]').filter({ hasText: nsLabel });
		},
		/** All namespace toggle buttons inside the filter dropdown */
		allNamespaceToggles(page: Page) {
			return page.locator('.dropdown-menu.show button[data-ns]');
		},
		/** Returns true if the namespace toggle for the given ns is checked (check-square icon) */
		isNamespaceChecked(page: Page, nsLabel: string) {
			return page
				.locator('.dropdown-menu.show button[data-ns]')
				.filter({ hasText: nsLabel })
				.locator('i.bi-check-square');
		},
		/** The "Show all namespaces" button in the dropdown */
		showAllNamespacesButton(page: Page) {
			return page.locator('.dropdown-menu.show button.dropdown-item').filter({ hasText: 'Show all namespaces' });
		},
		/** A namespace group toggle button (button, not anchor) */
		groupToggle(page: Page, ns: string) {
			return page.locator('button.list-group-item').filter({ hasText: ns });
		},
		/** All namespace group toggle buttons */
		allGroupToggles(page: Page) {
			return page.locator('button.list-group-item');
		},
		/** The "Expand all" button */
		expandAllButton(page: Page) {
			return page.locator('button.btn-link').filter({ hasText: 'Expand all' });
		},
		/** The "Collapse all" button */
		collapseAllButton(page: Page) {
			return page.locator('button.btn-link').filter({ hasText: 'Collapse all' });
		},
		/** Clicks "Expand all" if the button is present and items are not
		 *  already visible (e.g. single-namespace pages auto-expand). */
		async expandAll(page: Page) {
			// Fast-path: if items are already visible, nothing to do.
			if (await page.locator('a.list-group-item').first().isVisible().catch(() => false)) {
				return;
			}
			const btn = this.expandAllButton(page);
			if (await btn.isVisible().catch(() => false)) {
				await btn.click();
				await page.locator('a.list-group-item').first().waitFor({ state: 'visible', timeout: 5_000 });
			}
		},
		/** Clicks "Collapse all" if the button is present. */
		async collapseAll(page: Page) {
			const btn = this.collapseAllButton(page);
			if (await btn.isVisible().catch(() => false)) {
				await btn.click();
				await page
					.locator('button.list-group-item i.bi-chevron-down')
					.first()
					.waitFor({ state: 'hidden', timeout: 5_000 })
					.catch(() => {});
			}
		},
		/** The empty state message (when no items match) */
		emptyState(page: Page) {
			return page.locator('.text-muted.fst-italic');
		},

		// ── Rulebase filter menu (the "Filters" button with checkboxes) ──

		/** The rulebase filter menu toggle button */
		filterMenuButton(page: Page) {
			return page.locator('button.btn-outline-secondary').filter({ has: page.locator('i.bi-funnel-fill') });
		},
		/** The rulebase filter dropdown menu (when open) — scoped by its unique content */
		filterMenuDropdown(page: Page) {
			return page.locator('.dropdown-menu.show').filter({ hasText: 'Clear all filters' });
		},
		/** A filter checkbox inside the filter menu dropdown */
		filterOption(page: Page, label: string) {
			return page.locator('.dropdown-menu.show button.dropdown-item').filter({ hasText: label });
		},
		/** The "Clear all filters" button inside the filter menu dropdown */
		clearAllFiltersButton(page: Page) {
			return page.locator('.dropdown-menu.show button.dropdown-item').filter({ hasText: 'Clear all filters' });
		}
	}
};
