<script lang="ts">
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';

	interface Props {
		fullName: string;
		size?: 'sm' | 'md' | 'lg';
		class?: string;
	}

	let { fullName, size = 'md', class: className = '' }: Props = $props();

	let copied = $state(false);
	let timer: ReturnType<typeof setTimeout> | undefined;

	async function copyName() {
		try {
			await navigator.clipboard.writeText(fullName);
		} catch {
			// Clipboard API unavailable (non-secure context) — no feedback.
			return;
		}
		copied = true;
		clearTimeout(timer);
		timer = setTimeout(() => (copied = false), 1500);
	}
</script>

<button
	type="button"
	class="copyable-title d-flex align-items-center gap-1 border-0 bg-transparent p-0 text-start min-width-0 {className}"
	title={copied ? 'Copied to clipboard' : 'Click to copy fully qualified name'}
	aria-label={`Copy ${fullName}`}
	onclick={copyName}
>
	<QualifiedName {fullName} {size} class="flex-grow-1" />
	{#if copied}
		<i class="bi bi-check2 text-success"></i>
	{/if}
</button>

<style>
	.copyable-title {
		cursor: pointer;
		width: fit-content;
		max-width: 100%;
	}
	.copyable-title:hover {
		opacity: 0.85;
	}
</style>
