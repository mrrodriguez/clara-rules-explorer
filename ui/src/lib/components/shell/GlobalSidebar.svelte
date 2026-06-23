<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { CONTEXTUAL_MENU_CONFIG } from '$lib/constants';
	import type { ContextualMenuType } from '$lib/types/ui';
	import SidebarItem from '$lib/components/shell/SidebarItem.svelte';
	import { page } from '$app/state';

	const mainNavConfig = [
		{ href: '/', label: 'Dashboard', icon: 'bi-speedometer2' },
		{ href: '/rules', label: 'Rules', icon: 'bi-list-check' },
		{ href: '/queries', label: 'Queries', icon: 'bi-search' },
		{ href: '/fact-types', label: 'Fact Types', icon: 'bi-database-fill' },
		{ href: '/session', label: 'Session', icon: 'bi-play-circle-fill' }
	];

	function isNavActive(href: string) {
		const path = page.url.pathname;
		if (href === '/') return path === '/';
		return path.startsWith(href);
	}

	let sidebarWidth = $derived(appState.isSidebarMini ? '64px' : '200px');
</script>

{#if appState.isSidebarOpen}
	<aside
		class="sidebar bg-light border-end"
		style="width: {sidebarWidth}; min-height: calc(100vh - var(--navbar-height));"
	>
		<div class="d-flex flex-column h-100">
			<div class="p-2 flex-shrink-0">
				<ul class="nav flex-column">
					{#each mainNavConfig as item (item.href)}
						<SidebarItem
							href={item.href}
							label={item.label}
							icon={item.icon}
							active={isNavActive(item.href)}
						/>
					{/each}
				</ul>
			</div>

			{#if Object.values(CONTEXTUAL_MENU_CONFIG).some((config) => appState.contextualNav[config.navKey].length > 0)}
				<hr class="mx-2 my-1" />
				<div class="p-2 flex-grow-1 overflow-auto">
					<ul class="nav flex-column">
						{#each Object.entries(CONTEXTUAL_MENU_CONFIG) as [key, config] (key)}
							{@const items = appState.contextualNav[config.navKey]}
							{#if items.length > 0}
								<SidebarItem
									label={config.label}
									icon={config.icon}
									badge={items.length}
									active={appState.activeContextualMenu === key}
									onclick={() => appState.toggleContextualMenu(key as ContextualMenuType)}
								/>
							{/if}
						{/each}
					</ul>
				</div>
			{/if}
		</div>
	</aside>
{/if}

<style>
	.sidebar {
		transition: width 0.2s ease-in-out;
		overflow-x: hidden;
		flex-shrink: 0;
		z-index: 1030;
	}
</style>
