<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { getListFilterState } from '$lib/state/listFilterContext.svelte';

	interface Props {
		/** Fully-qualified name of the item to locate in the list. */
		name: string;
	}

	let { name }: Props = $props();

	const filterState = getListFilterState();
	const disabled = $derived(filterState.activeItemFilteredOut);

	function handleClick() {
		appState.requestLocate(name);
	}
</script>

<button
	class="btn btn-outline-secondary btn-sm"
	title={disabled
		? 'This item is hidden by active filters. Clear filters to enable.'
		: 'Locate this item in the list'}
	{disabled}
	onclick={handleClick}
>
	<i class="bi bi-list-ol me-1"></i>
	Locate in List
</button>
