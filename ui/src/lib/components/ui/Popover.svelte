<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		open: boolean;
		trigger: Snippet<[() => void]>;
		content: Snippet;
		width?: number;
	}

	let { open = $bindable(), trigger, content, width = 380 }: Props = $props();

	let anchorEl = $state<HTMLDivElement>();
	let panelEl = $state<HTMLDivElement>();

	function portal(node: HTMLElement) {
		document.body.appendChild(node);
		return {
			destroy() {
				node.remove();
			}
		};
	}

	$effect(() => {
		if (!open) return;
		position();
		const reposition = () => position();
		const onKeydown = (e: KeyboardEvent) => {
			if (e.key === 'Escape') open = false;
		};
		const onClickOutside = (e: MouseEvent) => {
			const target = e.target as Node | null;
			if (target && !panelEl?.contains(target) && !anchorEl?.contains(target)) {
				open = false;
			}
		};
		window.addEventListener('scroll', reposition, true);
		window.addEventListener('resize', reposition);
		window.addEventListener('keydown', onKeydown);
		// Use capture so the handler runs before any other click handlers that
		// might stop propagation (e.g. the trigger toggle).
		document.addEventListener('click', onClickOutside, true);
		return () => {
			window.removeEventListener('scroll', reposition, true);
			window.removeEventListener('resize', reposition);
			window.removeEventListener('keydown', onKeydown);
			document.removeEventListener('click', onClickOutside, true);
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

		// Prefer the side with more available space.
		const spaceRight = vw - rect.right - 8;
		const spaceLeft = rect.left - 8;
		let left: number;
		if (spaceRight >= panelW) {
			// Enough space on the right — open to the right.
			left = rect.right + 6;
		} else if (spaceLeft >= panelW) {
			// Not enough space on the right but enough on the left — open to the left.
			left = Math.max(8, rect.left - panelW - 6);
		} else if (spaceLeft >= spaceRight) {
			// Neither side fits completely; prefer the larger side.
			left = Math.max(8, rect.left - panelW - 6);
		} else {
			left = Math.min(rect.right + 6, vw - panelW - 8);
		}

		const panelH = panelEl.offsetHeight;
		if (top + panelH > vh - 8 && rect.top - panelH - 6 >= 8) {
			top = rect.top - panelH - 6;
		}
		panelEl.style.top = `${Math.max(8, top)}px`;
		panelEl.style.left = `${left}px`;
		panelEl.style.visibility = 'visible';
	}
</script>

<div class="d-inline-flex" bind:this={anchorEl}>
	{@render trigger(() => (open = !open))}
</div>

{#if open}
	<div class="popover-panel" bind:this={panelEl} use:portal>
		{@render content()}
	</div>
{/if}

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
