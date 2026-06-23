<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { CONTEXTUAL_MENU_CONFIG } from '$lib/constants';
	import { toUrlId } from '$lib/utils';
	import ProductionReferenceLink from '$lib/components/rulebase/ProductionReferenceLink.svelte';
	import FactTypeReferenceLink from '$lib/components/rulebase/FactTypeReferenceLink.svelte';
	import type { ProductionReference } from '$lib/types/api';

	const activeMenu = $derived.by(() => {
		const menuId = appState.activeContextualMenu;
		const config = menuId ? CONTEXTUAL_MENU_CONFIG[menuId] : null;
		if (!config) return { items: [], label: '', icon: '', contentType: null };

		return {
			items: appState.contextualNav[config.navKey],
			label: config.label,
			icon: config.icon,
			contentType: config.contentType
		};
	});

	let sidebarWidth = $derived(appState.isSidebarMini ? '64px' : '200px');
</script>

{#snippet header(label: string, icon: string)}
	<div class="p-3 border-bottom d-flex justify-content-between align-items-center bg-light">
		<h6 class="text-muted text-uppercase fs-7 fw-bold mb-0">
			<i class="bi {icon} me-2"></i>
			{label}
		</h6>
		<button
			class="btn-close small"
			onclick={() => (appState.activeContextualMenu = null)}
			aria-label="Close menu"
		></button>
	</div>
{/snippet}

{#if appState.activeContextualMenu}
	<aside class="contextual-flyout bg-white border-end shadow-lg" style="left: {sidebarWidth};">
		<div class="d-flex flex-column h-100">
			{@render header(activeMenu.label, activeMenu.icon)}

			<div class="flex-grow-1 overflow-auto">
				<div class="list-group list-group-flush">
					{#if activeMenu.contentType === 'fact'}
						{#each activeMenu.items as item (item as string)}
							<FactTypeReferenceLink type={item as string} />
						{/each}
					{:else if activeMenu.contentType === 'production'}
						{#each activeMenu.items as item (toUrlId((item as ProductionReference).name))}
							<ProductionReferenceLink ref={item as ProductionReference} fullView={true} />
						{/each}
					{/if}
				</div>
			</div>
		</div>
	</aside>
{/if}

<style>
	.contextual-flyout {
		position: fixed;
		top: var(--navbar-height);
		bottom: 0;
		width: 300px;
		z-index: 1020;
		transition: left 0.2s ease-in-out;
		animation: slideIn 0.2s ease-out;
	}

	@keyframes slideIn {
		from {
			transform: translateX(-20px);
			opacity: 0;
		}
		to {
			transform: translateX(0);
			opacity: 1;
		}
	}
</style>
