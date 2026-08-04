<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { getListFilterState } from '$lib/state/listFilterContext.svelte';

	interface Props {
		/** Route id of the item to locate in the list (server-issued). */
		id: string;
	}

	let { id }: Props = $props();

	const filterState = getListFilterState();
	const disabled = $derived(filterState?.activeItemFilteredOut ?? false);

	function handleClick() {
		appState.requestLocate(id);
	}
</script>

<button
	class="btn btn-outline-secondary btn-sm"
	title={disabled
		? 'This item is hidden by active filters. Clear filters to enable.'
		: 'Reveal this item in the sidebar list'}
	{disabled}
	onclick={handleClick}
>
	<i class="bi bi-list-ol me-1"></i>
	Reveal
</button>
