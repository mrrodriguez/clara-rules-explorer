<script lang="ts">
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	export interface BreadcrumbItem {
		label: string;
		href?: string;
	}

	interface Props {
		items: BreadcrumbItem[];
	}

	let { items }: Props = $props();
</script>

<nav aria-label="breadcrumb" class="mb-4">
	<ol class="breadcrumb mb-0">
		{#each items as item, i (item.label)}
			{#if i === items.length - 1}
				<li class="breadcrumb-item active" aria-current="page">{item.label}</li>
			{:else if item.href}
				<li class="breadcrumb-item">
					<a href={resolve(item.href as Pathname)}>{item.label}</a>
				</li>
			{:else}
				<li class="breadcrumb-item">{item.label}</li>
			{/if}
		{/each}
	</ol>
</nav>

<style>
	.breadcrumb-item + .breadcrumb-item::before {
		content: var(--bs-breadcrumb-divider, '/');
	}
</style>
