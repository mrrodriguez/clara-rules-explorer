// src/lib/state/appState.svelte.ts

import type { ProductionReference, TypeReference } from '$lib/types/api';
import type { ContextualMenuType } from '$lib/types/ui';

export class AppState {
	// Reactive state using Svelte 5 runes
	uiTheme = $state<'light' | 'dark'>('light');
	isSidebarOpen = $state(true);
	isSidebarMini = $state(false);

	// Contextual navigation for the sidebar
	contextualNav = $state<{
		upstream: ProductionReference[];
		downstream: ProductionReference[];
		inputTypes: TypeReference[];
		insertTypes: TypeReference[];
		retractTypes: TypeReference[];
		type: 'rule' | 'query' | null;
	}>({
		upstream: [],
		downstream: [],
		inputTypes: [],
		insertTypes: [],
		retractTypes: [],
		type: null
	});

	activeContextualMenu = $state<ContextualMenuType | null>(null);

	// Locate-in-list signalling (summary → list communication).
	// This is an ephemeral event, not ongoing derived state — the parent
	// layout handles filtered-out state via callback + context instead.
	locateRequest = $state<{ id: string; timestamp: number } | null>(null);

	// Derived state
	isDark = $derived(this.uiTheme === 'dark');

	// Actions (Mutations)
	toggleTheme() {
		this.uiTheme = this.uiTheme === 'light' ? 'dark' : 'light';
	}

	toggleSidebar() {
		this.isSidebarOpen = !this.isSidebarOpen;
	}

	toggleSidebarMini() {
		this.isSidebarMini = !this.isSidebarMini;
	}

	setContextualNav(
		upstream: ProductionReference[] = [],
		downstream: ProductionReference[] = [],
		type: 'rule' | 'query' | null = null,
		inputTypes: TypeReference[] = [],
		insertTypes: TypeReference[] = [],
		retractTypes: TypeReference[] = []
	) {
		this.contextualNav = { upstream, downstream, type, inputTypes, insertTypes, retractTypes };
		this.activeContextualMenu = null;
	}

	clearContextualNav() {
		this.contextualNav = {
			upstream: [],
			downstream: [],
			type: null,
			inputTypes: [],
			insertTypes: [],
			retractTypes: []
		};
		this.activeContextualMenu = null;
	}

	toggleContextualMenu(menu: ContextualMenuType) {
		this.activeContextualMenu = this.activeContextualMenu === menu ? null : menu;
	}

	requestLocate(id: string) {
		this.locateRequest = { id, timestamp: Date.now() };
	}

	clearLocateRequest() {
		this.locateRequest = null;
	}
}

// Single instance for global state
export const appState = new AppState();
