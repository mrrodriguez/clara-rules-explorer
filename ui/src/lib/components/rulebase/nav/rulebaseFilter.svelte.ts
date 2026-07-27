/**
 * Reactive state machine for the rulebase attribute filter used in
 * {@link GroupedFilterableNavList}.
 *
 * Manages a set of independently toggleable checkboxes — unlike
 * {@link NamespaceFilter}, there is no exclusive-mode or guard logic:
 * each checkbox simply toggles on/off with OR semantics (an item
 * matches if it matches *any* checked filter).
 */
export class RulebaseFilter {
	/** Record of currently active filter IDs. Empty record = no filter. */
	activeFilters: Record<string, boolean> = $state({});

	/** Whether the filter dropdown is currently visible. */
	filterDropdownOpen: boolean = $state(false);

	/** `true` when at least one filter checkbox is checked. */
	get active(): boolean {
		return Object.keys(this.activeFilters).length > 0;
	}

	/** Toggle a single filter on/off. */
	toggle(id: string): void {
		if (this.activeFilters[id]) {
			delete this.activeFilters[id];
		} else {
			this.activeFilters[id] = true;
		}
	}

	/** Deactivate all filters and close the dropdown. */
	clearAll(): void {
		this.activeFilters = {};
		this.filterDropdownOpen = false;
	}
}
