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
			return page.locator('.list-group-item').filter({ hasText: name });
		},
		/** Returns the first item in an EntityList */
		firstItem(page: Page) {
			return page.locator('.list-group-item').first();
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
	}
};
