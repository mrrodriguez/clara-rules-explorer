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

	// When the name itself is copyable the outer list-group-item must not
	// carry a native `title` – the inner QualifiedName/CopyableTitle already
	// handles the tooltip and the outer title's hit-area can otherwise cover
	// sibling action buttons (Playwright hover intercepts on CI).
	const effectiveTitle = $derived(copyable || muted ? undefined : title);
</script>

<NavigationListItem {href} title={effectiveTitle} {fullName} {activeColor} {active}>
	{#if muted}
		<div class="text-muted fst-italic w-100 min-width-0">{fullName}</div>
	{:else}
		<div class="d-flex justify-content-between align-items-center w-100 min-width-0">
			{#if copyable}
				<div class="title-col">
					<CopyableTitle {fullName} size="sm" class="w-100" />
				</div>
			{:else}
				<div class="title-col">
					<QualifiedName {fullName} size="sm" class="w-100" />
				</div>
			{/if}
			{#if badge || actions}
				<div class="d-flex align-items-center gap-1 ms-2 actions-col">
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
	.title-col {
		flex: 1 1 0;
		min-width: 0;
		overflow: hidden;
	}
	.actions-col {
		position: relative;
		z-index: 1;
		flex: 0 0 auto;
	}
</style>
