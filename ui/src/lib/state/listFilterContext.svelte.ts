import { getContext, setContext } from 'svelte';

const KEY = Symbol('listFilterState');

/**
 * Reactive state shared between a list component and its sibling summary
 * views within a domain layout. The layout creates the state, passes a
 * callback to the list to keep it updated, and sets it in context for
 * summary components to read.
 */
export interface ListFilterState {
	activeItemFilteredOut: boolean;
}

/** Set the reactive filter state in context (called by the domain layout). */
export function setListFilterState(state: ListFilterState): void {
	setContext<ListFilterState>(KEY, state);
}

/** Read the reactive filter state from context (called by summary views). */
export function getListFilterState(): ListFilterState {
	return getContext<ListFilterState>(KEY);
}
