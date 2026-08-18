<script lang="ts">
	import type { Snippet } from 'svelte';

	type TabId = 'types' | 'dependencies';

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

	let activeTab = $state<TabId>('types');

	const tabs = $derived<{ id: TabId; label: string }[]>([
		{ id: 'types', label: typesLabel },
		{ id: 'dependencies', label: dependenciesLabel }
	]);

	function onTablistKeydown(event: KeyboardEvent) {
		const tablist = event.currentTarget as HTMLUListElement;
		const button = (event.target as HTMLElement).closest<HTMLButtonElement>('[role="tab"]');
		if (!button) return;

		const buttons = Array.from(tablist.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
		const index = buttons.indexOf(button);
		if (index === -1) return;

		const nextIndexByKey: Record<string, number> = {
			ArrowRight: (index + 1) % buttons.length,
			ArrowDown: (index + 1) % buttons.length,
			ArrowLeft: (index - 1 + buttons.length) % buttons.length,
			ArrowUp: (index - 1 + buttons.length) % buttons.length,
			Home: 0,
			End: buttons.length - 1
		};

		const nextIndex = nextIndexByKey[event.key];
		if (nextIndex === undefined) return;

		event.preventDefault();
		activeTab = tabs[nextIndex].id;
		buttons[nextIndex].focus();
	}
</script>

<div class="production-summary-tabs">
	<ul class="nav nav-underline mb-3" role="tablist" onkeydown={onTablistKeydown}>
		{#each tabs as tab (tab.id)}
			<li class="nav-item" role="presentation">
				<button
					type="button"
					id={`production-summary-tab-${tab.id}`}
					class="nav-link {activeTab === tab.id ? 'active' : ''}"
					role="tab"
					aria-selected={activeTab === tab.id}
					aria-controls={`production-summary-panel-${tab.id}`}
					tabindex={activeTab === tab.id ? 0 : -1}
					onclick={() => (activeTab = tab.id)}
				>
					{tab.label}
				</button>
			</li>
		{/each}
	</ul>

	<div
		id="production-summary-panel-types"
		role="tabpanel"
		aria-labelledby="production-summary-tab-types"
		tabindex="0"
		hidden={activeTab !== 'types'}
	>
		{@render types()}
	</div>
	<div
		id="production-summary-panel-dependencies"
		role="tabpanel"
		aria-labelledby="production-summary-tab-dependencies"
		tabindex="0"
		hidden={activeTab !== 'dependencies'}
	>
		{@render dependencies()}
	</div>
</div>

<style>
	.production-summary-tabs {
		font-size: 0.85rem;
	}

	.production-summary-tabs .nav-link {
		cursor: pointer;
	}
</style>
