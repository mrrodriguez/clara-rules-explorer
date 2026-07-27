<script lang="ts">
	import type { Snippet } from 'svelte';
	import { clickOutside } from '$lib/actions/clickOutside';

	interface Props {
		/** Whether the dropdown menu is visible (two-way bindable). */
		open: boolean;
		/** Content rendered inside the toggle button. */
		buttonContent: Snippet;
		/** Content rendered inside the dropdown menu (when open). */
		children: Snippet;
		/** Called when the user clicks outside the dropdown. When omitted,
		 *  `open` is set to `false` automatically via `$bindable`. */
		onclose?: () => void;
	}

	let { open = $bindable(), buttonContent, children, onclose }: Props = $props();

	function handleClickOutside() {
		if (onclose) {
			onclose();
		} else {
			open = false;
		}
	}
</script>

<div class="p-2 border-bottom bg-light">
	<div class="position-relative" use:clickOutside={handleClickOutside}>
		<button
			class="btn btn-sm btn-outline-secondary w-100 text-truncate text-start"
			onclick={() => (open = !open)}
		>
			{@render buttonContent()}
		</button>

		{#if open}
			<div class="dropdown-menu show w-100 p-1" style="max-height: 320px; overflow-y: auto;">
				{@render children()}
			</div>
		{/if}
	</div>
</div>
