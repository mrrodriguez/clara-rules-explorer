<script lang="ts">
	import type { SessionFact, FactMatch } from '$lib/types/api';
	import SessionActivityList from '$lib/components/rulebase/SessionActivityList.svelte';

	export type ActivityCategory =
		| { title: string; type: 'facts'; items: SessionFact[]; emptyText?: string }
		| { title: string; type: 'matches'; items: FactMatch[]; emptyText?: string };

	type TabId = 'facts' | 'matches';

	interface ActivityTab {
		id: TabId;
		label: string;
		count: number;
		category: ActivityCategory;
	}

	interface Props {
		categories: ActivityCategory[];
		emptyText?: string;
	}

	let { categories = [], emptyText = 'No session activity recorded.' }: Props = $props();

	const activeCategories = $derived(categories.filter((c) => c.items.length > 0));
	const totalCount = $derived(activeCategories.reduce((sum, c) => sum + c.items.length, 0));

	const tabs = $derived<ActivityTab[]>(
		activeCategories.map((category) => ({
			id: category.type,
			label: category.title,
			count: category.items.length,
			category
		}))
	);

	let expanded = $state(true);
	let activeTabId = $state<TabId | null>(null);

	const activeTab = $derived(tabs.find((tab) => tab.id === activeTabId) ?? tabs[0] ?? null);

	function toggleExpanded() {
		expanded = !expanded;
	}

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
		activeTabId = tabs[nextIndex].id;
		buttons[nextIndex].focus();
	}
</script>

<div class="mt-4 mb-4 border-top pt-4">
	<button
		type="button"
		class="btn btn-link text-decoration-none text-muted text-uppercase small fw-bold d-flex align-items-center p-0 mb-2 w-100 text-start"
		onclick={toggleExpanded}
		aria-expanded={expanded}
	>
		<i class="bi {expanded ? 'bi-chevron-down' : 'bi-chevron-right'} me-1 opacity-75"></i>
		<i class="bi bi-play-circle-fill me-2 opacity-75"></i>
		<span>Current Session Activity</span>
		{#if totalCount > 0}
			<span class="badge bg-secondary-subtle text-secondary border border-secondary-subtle ms-2"
				>{totalCount}</span
			>
		{/if}
	</button>

	{#if expanded}
		{#if tabs.length === 0}
			<div class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed">
				{emptyText}
			</div>
		{:else}
			<ul
				class="nav nav-underline session-activity-tabs mb-3"
				role="tablist"
				onkeydown={onTablistKeydown}
			>
				{#each tabs as tab (tab.id)}
					<li class="nav-item" role="presentation">
						<button
							type="button"
							id={`session-activity-tab-${tab.id}`}
							class="nav-link {activeTab?.id === tab.id ? 'active' : ''}"
							role="tab"
							aria-selected={activeTab?.id === tab.id}
							aria-controls={`session-activity-panel-${tab.id}`}
							tabindex={activeTab?.id === tab.id ? 0 : -1}
							onclick={() => (activeTabId = tab.id)}
						>
							{tab.label} ({tab.count})
						</button>
					</li>
				{/each}
			</ul>

			{#if activeTab}
				<div
					id={`session-activity-panel-${activeTab.id}`}
					role="tabpanel"
					aria-labelledby={`session-activity-tab-${activeTab.id}`}
					tabindex="0"
				>
					<SessionActivityList items={activeTab.category.items} type={activeTab.category.type} />
				</div>
			{/if}
		{/if}
	{/if}
</div>

<style>
	.session-activity-tabs {
		--bs-nav-underline-link-padding-x: 0.75rem;
		--bs-nav-underline-link-padding-y: 0.375rem;
		font-size: 0.8rem;
	}

	.session-activity-tabs .nav-link {
		cursor: pointer;
	}
</style>
