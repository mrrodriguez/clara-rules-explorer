<script lang="ts">
	import type { Snippet } from 'svelte';
	import NavigationListItem from '$lib/components/rulebase/nav/NavigationListItem.svelte';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import CopyableTitle from '$lib/components/ui/CopyableTitle.svelte';

	interface Props {
		href?: string;
		title?: string;
		fullName: string;
		activeColor?: string;
		badge?: Snippet;
		/** Right-side action buttons (e.g. a popover trigger, a jump link). */
		actions?: Snippet;
		/**
		 * Renders the row as non-linkable muted text (hierarchy ghosts and
		 * other types with no rulebase presence).
		 */
		muted?: boolean;
		active?: boolean;
		/**
		 * When true, the name itself is a click-to-copy control instead of
		 * plain text (used by rows whose dedicated link is an icon button).
		 */
		copyable?: boolean;
	}

	let {
		href,
		title,
		fullName,
		activeColor,
		badge,
		actions,
		muted = false,
		active = false,
		copyable = false
	}: Props = $props();
</script>

<NavigationListItem {href} {title} {activeColor} {active}>
	{#if muted}
		<div class="text-muted fst-italic w-100 min-width-0">{fullName}</div>
	{:else}
		<div class="d-flex justify-content-between align-items-center w-100 min-width-0">
			{#if copyable}
				<CopyableTitle {fullName} size="sm" class="flex-grow-1" />
			{:else}
				<QualifiedName {fullName} size="sm" class="flex-grow-1" />
			{/if}
			{#if badge || actions}
				<div class="d-flex align-items-center gap-1 ms-2 flex-shrink-0">
					{#if badge}
						{@render badge()}
					{/if}
					{#if actions}
						{@render actions()}
					{/if}
				</div>
			{/if}
		</div>
	{/if}
</NavigationListItem>

<style>
	.min-width-0 {
		min-width: 0;
	}
</style>
