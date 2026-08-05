<script lang="ts">
	import type { Snippet } from 'svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		/**
		 * When given, the row renders as a link to this path; when omitted it
		 * renders as a plain row (used by rows that carry interactive
		 * actions, which must not be nested inside an anchor).
		 */
		href?: string;
		active?: boolean;
		title?: string;
		activeColor?: string;
		children: Snippet;
	}

	let { href, active = false, title, activeColor = '#0d6efd', children }: Props = $props();
</script>

{#if href}
	<a
		href={resolve(href as Pathname)}
		{title}
		class="list-group-item list-group-item-action d-flex justify-content-between align-items-center py-2 px-3 {active
			? 'active'
			: ''}"
		style:--active-color={activeColor}
	>
		{@render children()}
	</a>
{:else}
	<div
		{title}
		class="list-group-item d-flex justify-content-between align-items-center py-2 px-3 {active
			? 'active'
			: ''}"
		style:--active-color={activeColor}
	>
		{@render children()}
	</div>
{/if}

<style>
	.list-group-item {
		border-left: 3px solid transparent;
		/* Ensure no text underlining on hover for these items */
		text-decoration: none !important;
	}

	/* Standardized hover behavior for all list items */
	.list-group-item:hover {
		background-color: #f8f9fa;
		color: inherit;
	}

	.list-group-item.active {
		border-left-color: var(--active-color);
		background-color: #f8f9fa;
		color: var(--active-color);
	}

	.list-group-item.active:hover {
		color: var(--active-color);
	}
</style>
