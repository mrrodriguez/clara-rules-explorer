<script lang="ts">
	interface Props {
		/** Whether the checkbox is checked. */
		checked: boolean;
		/** Label text displayed next to the checkbox. */
		label: string;
		/** Click handler for this item. */
		onclick: () => void;
		/** When `true`, the item appears dimmed and is not interactive. */
		disabled?: boolean;
		/** When `true`, applies the `text-muted` class (visual dimming).
		 *  Separate from `disabled` so dimmed items can still be clickable. */
		muted?: boolean;
		/** Optional count badge shown right-aligned. */
		count?: number;
		/** Optional value for a `data-ns` attribute (used by E2E selectors). */
		dataNs?: string;
	}

	let { checked, label, onclick, disabled = false, muted = false, count, dataNs }: Props = $props();
</script>

<button
	class="dropdown-item small d-flex align-items-center gap-1 mb-0 py-1"
	class:text-muted={muted}
	{disabled}
	{onclick}
	data-ns={dataNs}
>
	<i class="bi bi-{checked ? 'check-square' : 'square'} me-1 opacity-75"></i>
	<span class="text-truncate" title={label}>{label}</span>
	{#if count !== undefined}
		<span class="ms-auto text-muted ps-1 small">{count}</span>
	{/if}
</button>
