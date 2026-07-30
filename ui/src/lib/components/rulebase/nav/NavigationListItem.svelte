<script lang="ts">
	import type { Snippet } from 'svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		href: string;
		active?: boolean;
		title?: string;
		activeColor?: string;
		children: Snippet;
	}

	let { href, active = false, title, activeColor = '#0d6efd', children }: Props = $props();

	// We cast to Pathname (the union of all valid app pathnames) to satisfy
	// SvelteKit's strict routing types. NavigationListItem receives dynamic
	// strings that correspond to these pathnames at runtime.
	const resolvedHref = $derived(resolve(href as Pathname));
</script>

<a
	href={resolvedHref}
	{title}
	class="list-group-item list-group-item-action d-flex justify-content-between align-items-center py-2 px-3 {active
		? 'active'
		: ''}"
	style:--active-color={activeColor}
>
	{@render children()}
</a>

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
