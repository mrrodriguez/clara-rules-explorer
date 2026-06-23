<script lang="ts">
	import { splitQualifiedName } from '$lib/utils';

	interface Props {
		fullName: string;
		size?: 'sm' | 'md' | 'lg';
		class?: string;
	}

	let { fullName, size = 'md', class: className = '' }: Props = $props();
	let { name, namespace } = $derived(splitQualifiedName(fullName));

	const fontSizes = {
		sm: { name: '0.9rem', ns: '0.75rem' },
		md: { name: '1.1rem', ns: '0.85rem' },
		lg: { name: '1.5rem', ns: '1rem' }
	};
</script>

<div class="d-flex flex-column min-width-0 {className}" title={fullName}>
	<span
		class="text-truncate fw-medium"
		style="font-size: {fontSizes[size].name}; line-height: 1.2;"
	>
		{name}
	</span>
	{#if namespace}
		<span
			class="text-truncate text-muted opacity-75"
			style="font-size: {fontSizes[size].ns}; line-height: 1.2;"
		>
			{namespace}
		</span>
	{/if}
</div>

<style>
	.min-width-0 {
		min-width: 0;
	}
</style>
