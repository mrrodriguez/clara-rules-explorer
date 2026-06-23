<script lang="ts">
	import { highlight } from '$lib/highlighter';
	import CopyButton from '$lib/components/ui/CopyButton.svelte';
	import CollapseToggleButton from '$lib/components/ui/CollapseToggleButton.svelte';

	interface Props {
		code: unknown;
		language?: string;
		expanded?: boolean;
		hideHeader?: boolean;
	}

	let {
		code,
		language = 'json',
		expanded = $bindable(false),
		hideHeader = false
	}: Props = $props();

	let formattedCode = $derived(typeof code === 'string' ? code : JSON.stringify(code, null, 2));

	// We only trigger highlighting when expanded
	let highlightedHtml = $derived.by(() => {
		if (!expanded) return null;
		return highlight(formattedCode, language, 'github-dark');
	});

	function toggle() {
		expanded = !expanded;
	}
</script>

{#snippet rawPre(text: string)}
	<pre class="m-0 p-3 bg-transparent" style="font-size: 0.875rem; color: #c9d1d9;"><code
			>{text}</code
		></pre>
{/snippet}

<div class="code-block position-relative border rounded overflow-hidden {hideHeader ? '' : 'mb-3'}">
	{#if !hideHeader}
		<div
			class="d-flex justify-content-between align-items-center p-2 border-bottom bg-light-subtle"
		>
			<CollapseToggleButton {expanded} onclick={toggle} />
			<CopyButton text={formattedCode} />
		</div>
	{/if}

	{#if expanded}
		<div class="code-content">
			{#await highlightedHtml}
				{@render rawPre(formattedCode)}
			{:then html}
				{#if html}
					<div class="shiki-wrapper">
						<!-- eslint-disable-next-line svelte/no-at-html-tags -->
						{@html html}
					</div>
				{:else}
					{@render rawPre(formattedCode)}
				{/if}
			{/await}
		</div>
	{/if}
</div>

<style>
	.code-block {
		background-color: var(--bs-body-bg);
	}

	.code-content {
		max-height: 500px;
		overflow-y: auto;
		background-color: #0d1117; /* GitHub Dark background */
	}

	/* Shiki styles override */
	:global(.shiki-wrapper pre) {
		margin: 0 !important;
		padding: 1rem !important;
		font-size: 0.875rem !important;
		background-color: transparent !important; /* Let .code-content provide the bg */
	}

	:global(.shiki-wrapper code) {
		font-family: var(--bs-font-monospace) !important;
	}
</style>
