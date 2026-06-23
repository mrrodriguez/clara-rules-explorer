<script lang="ts">
	import 'bootstrap/dist/css/bootstrap.min.css';
	import 'bootstrap-icons/font/bootstrap-icons.css';
	import '../app.css';
	import { onMount } from 'svelte';
	import { appState } from '$lib/state/appState.svelte';
	import GlobalNavbar from '$lib/components/shell/GlobalNavbar.svelte';
	import GlobalSidebar from '$lib/components/shell/GlobalSidebar.svelte';
	import GlobalSidebarFlyout from '$lib/components/shell/GlobalSidebarFlyout.svelte';

	let { children } = $props();

	onMount(async () => {
		// Import Bootstrap JS only on the client
		await import('bootstrap');
	});

	function handleOutsideClick(event: MouseEvent) {
		const flyout = document.querySelector('.contextual-flyout');
		const sidebar = document.querySelector('.sidebar');
		if (
			appState.activeContextualMenu &&
			flyout &&
			!flyout.contains(event.target as Node) &&
			!sidebar?.contains(event.target as Node)
		) {
			appState.activeContextualMenu = null;
		}
	}
</script>

<svelte:window onclick={handleOutsideClick} />

<div class="app-container" data-theme={appState.uiTheme}>
	<GlobalNavbar />

	<div class="d-flex h-100">
		<GlobalSidebar />
		<GlobalSidebarFlyout />

		<main class="flex-grow-1 overflow-auto" style="height: calc(100vh - var(--navbar-height));">
			{@render children()}
		</main>
	</div>
</div>

<style>
	:global(body) {
		margin: 0;
		padding: 0;
		overflow: hidden;
	}

	.app-container {
		height: 100vh;
		display: flex;
		flex-direction: column;
	}
</style>
