import { expect, test } from '@playwright/test';

test('home page has expected header and navigation buttons', async ({ page }) => {
	await page.goto('/');

	// Check for the main header
	await expect(page.getByRole('heading', { name: 'Rete Network Dashboard' })).toBeVisible();

	// Check for the navigation buttons/links in the summary cards
	const rulesButton = page.getByRole('link', { name: 'View All Rules' });
	await expect(rulesButton).toBeVisible();
	await expect(rulesButton).toHaveAttribute('href', '/rules');

	const queriesButton = page.getByRole('link', { name: 'View All Queries' });
	await expect(queriesButton).toBeVisible();
	await expect(queriesButton).toHaveAttribute('href', '/queries');

	const factTypesButton = page.getByRole('link', { name: 'View All Fact Types' });
	await expect(factTypesButton).toBeVisible();
	await expect(factTypesButton).toHaveAttribute('href', '/fact-types');

	const sessionButton = page.getByRole('link', { name: 'Explore Session' });
	await expect(sessionButton).toBeVisible();
	await expect(sessionButton).toHaveAttribute('href', '/session');
});
