/**
 * Reactive state machine for the namespace multi-select filter used in
 * {@link GroupedFilterableNavList}.
 *
 * The toggle behaviour follows a three-state UX pattern:
 *  1. **Off** (no active filter) → first click selects ONLY the chosen
 *     namespace (exclusive mode), hiding all others.
 *  2. **Exclusive / multi-select** → subsequent clicks toggle individual
 *     namespaces on/off.
 *  3. **Guard** — if the user would hide the last visible namespace the
 *     filter resets to "show all".
 */
export class NamespaceFilter {
	/** Record of namespaces hidden from view. Empty record = show all. */
	hiddenNamespaces: Record<string, boolean> = $state({});

	/** Current text in the dropdown's namespace search input. */
	nsFilterText: string = $state('');

	/** Whether the filter dropdown is currently visible. */
	filterDropdownOpen: boolean = $state(false);

	/** `true` when at least one namespace is hidden (i.e. a filter is active). */
	get active(): boolean {
		return Object.keys(this.hiddenNamespaces).length > 0;
	}

	/**
	 * Toggle visibility of a single namespace.
	 *
	 * On the **first** interaction (no active filter) this enters exclusive
	 * mode — the chosen namespace is kept visible and all others are hidden.
	 * Subsequent clicks toggle individual namespaces.  If the last visible
	 * namespace would be hidden the filter resets to "show all".
	 *
	 * @param ns            The namespace to toggle.
	 * @param allNamespaces Ordered list of all known namespaces.
	 */
	toggle(ns: string, allNamespaces: string[]): void {
		if (!this.active) {
			// First interaction: exclusive mode — select ONLY this namespace.
			for (const other of allNamespaces) {
				this.hiddenNamespaces[other] = true;
			}
			delete this.hiddenNamespaces[ns];
		} else if (this.hiddenNamespaces[ns]) {
			delete this.hiddenNamespaces[ns];
		} else {
			this.hiddenNamespaces[ns] = true;
			// If every namespace would be hidden, reset to show all.
			if (Object.keys(this.hiddenNamespaces).length >= allNamespaces.length) {
				this.hiddenNamespaces = {};
			}
		}
	}

	/** Reset the filter to show all namespaces and close the dropdown. */
	showAll(): void {
		this.hiddenNamespaces = {};
		this.filterDropdownOpen = false;
		this.nsFilterText = '';
	}

	/**
	 * Return the subset of `allNamespaces` that match the dropdown search
	 * text.  When the search text is empty the original array is returned
	 * unchanged (reference-stable for `$derived` efficiency).
	 */
	getFiltered(allNamespaces: string[]): string[] {
		if (this.nsFilterText.length === 0) return allNamespaces;
		const needle = this.nsFilterText.toLowerCase();
		return allNamespaces.filter((ns) => ns.toLowerCase().includes(needle));
	}
}
