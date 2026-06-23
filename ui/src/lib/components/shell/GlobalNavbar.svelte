<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { resolve } from '$app/paths';
</script>

<nav class="navbar navbar-expand-lg navbar-dark bg-dark border-bottom sticky-top">
	<div class="container-fluid">
		<a
			class="navbar-brand d-flex align-items-center"
			href={resolve('/')}
			aria-label="Clara Rules Explorer Dashboard"
		>
			<i class="bi bi-diagram-3-fill me-2 text-primary"></i>
			<span class={appState.isSidebarMini ? 'd-none' : ''}>Clara Rules Explorer</span>
		</a>

		<div class="ms-auto d-flex align-items-center">
			<button
				class="btn btn-dark btn-sm me-2"
				onclick={() => appState.toggleSidebarMini()}
				title={appState.isSidebarMini ? 'Expand Sidebar' : 'Collapse Sidebar'}
			>
				<i class="bi bi-layout-sidebar"></i>
			</button>
			<button
				class="btn btn-outline-light btn-sm"
				onclick={() => appState.toggleTheme()}
				aria-label="Toggle light/dark theme"
			>
				<i class="bi bi-{appState.isDark ? 'sun-fill' : 'moon-fill'}"></i>
			</button>
		</div>
	</div>
</nav>
