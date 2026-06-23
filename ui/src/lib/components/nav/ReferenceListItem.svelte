<script lang="ts">
	import type { Snippet } from 'svelte';
	import NavigationListItem from '$lib/components/nav/NavigationListItem.svelte';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';

	interface Props {
		href: string;
		title: string;
		fullName: string;
		activeColor?: string;
		badge?: Snippet;
		active?: boolean;
	}

	let { href, title, fullName, activeColor, badge, active = false }: Props = $props();
</script>

<NavigationListItem {href} {title} {activeColor} {active}>
	<div class="d-flex justify-content-between align-items-center w-100 min-width-0">
		<QualifiedName {fullName} size="sm" class="flex-grow-1" />
		{#if badge}
			<div class="ms-2 flex-shrink-0">
				{@render badge()}
			</div>
		{/if}
	</div>
</NavigationListItem>

<style>
	.min-width-0 {
		min-width: 0;
	}
</style>
