<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		types: Snippet;
		dependencies: Snippet;
		typesLabel?: string;
		dependenciesLabel?: string;
	}

	let {
		types,
		dependencies,
		typesLabel = 'Fact Types',
		dependenciesLabel = 'Dependencies'
	}: Props = $props();

	let activeTab = $state<'types' | 'dependencies'>('types');
</script>

<div class="production-summary-tabs">
	<ul class="nav nav-underline mb-3" role="tablist">
		<li class="nav-item" role="presentation">
			<button
				class="nav-link {activeTab === 'types' ? 'active' : ''}"
				role="tab"
				aria-selected={activeTab === 'types'}
				onclick={() => (activeTab = 'types')}
			>
				{typesLabel}
			</button>
		</li>
		<li class="nav-item" role="presentation">
			<button
				class="nav-link {activeTab === 'dependencies' ? 'active' : ''}"
				role="tab"
				aria-selected={activeTab === 'dependencies'}
				onclick={() => (activeTab = 'dependencies')}
			>
				{dependenciesLabel}
			</button>
		</li>
	</ul>

	{#if activeTab === 'types'}
		{@render types()}
	{:else}
		{@render dependencies()}
	{/if}
</div>

<style>
	.production-summary-tabs {
		font-size: 0.85rem;
	}

	.production-summary-tabs .nav-link {
		cursor: pointer;
	}
</style>
