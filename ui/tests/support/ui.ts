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
		/** Clicks "Expand all" and verifies it took effect.  On a cold first
		 *  page load the click can be dispatched while the list is still
		 *  re-rendering and get lost — retry until a list item is visible. */
		async expandAll(page: Page) {
			const btn = this.expandAllButton(page);
			const firstItem = page.locator('a.list-group-item').first();
			for (let attempt = 0; attempt < 3; attempt++) {
				await btn.click().catch(() => {});
				try {
					await firstItem.waitFor({ state: 'visible', timeout: 2000 });
					return;
				} catch {
					// Click was lost to a re-render — retry.
				}
			}
			throw new Error('Expand all did not take effect after 3 attempts');
		},
		/** Clicks "Collapse all" and verifies no group remains expanded. */
		async collapseAll(page: Page) {
			const btn = this.collapseAllButton(page);
			for (let attempt = 0; attempt < 3; attempt++) {
				await btn.click().catch(() => {});
				const expandedCount = await page
					.locator('button.list-group-item i.bi-chevron-down')
					.count()
					.catch(() => 0);
				if (expandedCount === 0) return;
				await page.waitForTimeout(300);
			}
			throw new Error('Collapse all did not take effect after 3 attempts');
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
