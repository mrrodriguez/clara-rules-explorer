<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { CONTEXTUAL_MENU_CONFIG } from '$lib/constants';
	import ProductionReferenceLink from '$lib/components/rulebase/ProductionReferenceLink.svelte';
	import FactTypeReferenceLink from '$lib/components/rulebase/FactTypeReferenceLink.svelte';
	import type { ProductionReference, TypeReference } from '$lib/types/api';

	type ContextualItem = ProductionReference | TypeReference;

	const activeMenu = $derived.by(() => {
		const menuId = appState.activeContextualMenu;
		const config = menuId ? CONTEXTUAL_MENU_CONFIG[menuId] : null;
		if (!config) return { items: [], label: '', icon: '', contentType: null };

		return {
			items: appState.contextualNav[config.navKey] as ContextualItem[],
			label: config.label,
			icon: config.icon,
			contentType: config.contentType
		};
	});

	const activeTypeRole = $derived.by(() => {
		const menuId = appState.activeContextualMenu;
		if (menuId === 'input') return 'input' as const;
		if (menuId === 'insert' || menuId === 'retract') return 'output' as const;
		return undefined;
	});

	let sidebarWidth = $derived(appState.isSidebarMini ? '64px' : '200px');

	// The flyout sizes to its content (fit-content) so long qualified names are
	// never cropped, but it must never run past the right edge of the viewport.
	let flyoutMaxWidth = $derived(`calc(100vw - ${sidebarWidth} - 2rem)`);
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
	<aside
		class="contextual-flyout bg-white border-end shadow-lg"
		style="left: {sidebarWidth}; max-width: {flyoutMaxWidth};"
	>
		<div class="d-flex flex-column h-100">
			{@render header(activeMenu.label, activeMenu.icon)}

			<div class="flex-grow-1 overflow-auto">
				<div class="list-group list-group-flush">
					{#if activeMenu.contentType === 'fact'}
						{#each activeMenu.items as item (item.id)}
							<FactTypeReferenceLink
								type={item as TypeReference}
								fullView={true}
								role={activeTypeRole}
								upstream={appState.contextualNav.upstream}
								downstream={appState.contextualNav.downstream}
							/>
						{/each}
					{:else if activeMenu.contentType === 'production'}
						{#each activeMenu.items as item (item.id)}
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
		/* Grow to the widest row so fully-qualified names render without
		 * ellipsis; the inline max-width keeps it inside the viewport. */
		width: fit-content;
		min-width: 300px;
		z-index: 1020;
		transition:
			left 0.2s ease-in-out,
			width 0.2s ease-in-out;
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
