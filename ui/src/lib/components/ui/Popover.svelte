<script lang="ts">
	import type { Snippet } from 'svelte';
	import { clickOutside } from '$lib/actions/clickOutside';

	interface Props {
		open: boolean;
		trigger: Snippet<[() => void]>;
		content: Snippet;
		width?: number;
	}

	let { open = $bindable(), trigger, content, width = 380 }: Props = $props();

	let anchorEl = $state<HTMLDivElement>();
	let panelEl = $state<HTMLDivElement>();

	$effect(() => {
		if (!open) return;
		position();
		const reposition = () => position();
		const onKeydown = (e: KeyboardEvent) => {
			if (e.key === 'Escape') open = false;
		};
		window.addEventListener('scroll', reposition, true);
		window.addEventListener('resize', reposition);
		window.addEventListener('keydown', onKeydown);
		return () => {
			window.removeEventListener('scroll', reposition, true);
			window.removeEventListener('resize', reposition);
			window.removeEventListener('keydown', onKeydown);
		};
	});

	function position() {
		if (!anchorEl || !panelEl) return;
		const rect = anchorEl.getBoundingClientRect();
		const vw = window.innerWidth;
		const vh = window.innerHeight;
		const panelW = Math.min(width, vw - 16);
		panelEl.style.width = `${panelW}px`;
		let top = rect.bottom + 6;
		let left = Math.min(Math.max(8, rect.right - panelW), vw - panelW - 8);
		if (left < 8) left = 8;
		const panelH = panelEl.offsetHeight;
		if (top + panelH > vh - 8 && rect.top - panelH - 6 >= 8) {
			top = rect.top - panelH - 6;
		}
		panelEl.style.top = `${Math.max(8, top)}px`;
		panelEl.style.left = `${left}px`;
		panelEl.style.visibility = 'visible';
	}
</script>

<div class="d-inline-flex" bind:this={anchorEl} use:clickOutside={() => (open = false)}>
	{@render trigger(() => (open = !open))}
	{#if open}
		<div class="popover-panel" bind:this={panelEl}>
			{@render content()}
		</div>
	{/if}
</div>

<style>
	.popover-panel {
		position: fixed;
		z-index: 1080;
		visibility: hidden;
		background-color: var(--bs-body-bg);
		border: 1px solid var(--bs-border-color);
		border-radius: 0.5rem;
		box-shadow: 0 0.5rem 1rem rgba(0, 0, 0, 0.15);
		overflow: hidden;
	}
</style>
