<script lang="ts">
	import { appState } from '$lib/state/appState.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		href?: string;
		onclick?: () => void;
		active?: boolean;
		icon: string;
		label: string;
		badge?: number | string;
	}

	let { href, onclick, active = false, icon, label, badge }: Props = $props();

	let isMini = $derived(appState.isSidebarMini);
</script>

<li class="nav-item mb-1">
	{#if href}
		<a
			href={resolve(href as Pathname)}
			class="nav-link d-flex align-items-center {active ? 'active' : ''} {isMini
				? 'justify-content-center'
				: 'justify-content-start'}"
			title={label}
		>
			<i class="bi {icon} fs-5"></i>
			{#if !isMini}
				<span class="ms-2">{label}</span>
			{/if}
		</a>
	{:else}
		<button
			type="button"
			{onclick}
			class="nav-link w-100 border-0 d-flex align-items-center {active ? 'active' : ''} {isMini
				? 'justify-content-center'
				: 'justify-content-between'}"
			title={label}
		>
			<div class="d-flex align-items-center">
				<i class="bi {icon} fs-5"></i>
				{#if !isMini}
					<span class="ms-2">{label}</span>
				{/if}
			</div>
			{#if !isMini && badge !== undefined}
				<span
					class="badge rounded-pill {active
						? 'bg-white text-primary'
						: 'bg-secondary bg-opacity-25 text-dark'} small"
				>
					{badge}
				</span>
			{/if}
		</button>
	{/if}
</li>

<style>
	.nav-link {
		color: #555;
		padding: 0.5rem;
		border-radius: 6px;
		transition: all 0.2s;
		text-decoration: none;
	}

	.nav-link:hover {
		background-color: #e9ecef;
		color: #000;
	}

	.nav-link.active {
		background-color: #0d6efd;
		color: white !important;
	}

	button.nav-link:focus {
		box-shadow: none;
	}
</style>
