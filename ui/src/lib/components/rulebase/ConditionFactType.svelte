<script lang="ts">
	import type { TypeReference } from '$lib/types/api';
	import { factPath, splitDisplayName } from '$lib/utils';
	import { resolve } from '$app/paths';

	interface Props {
		type: TypeReference;
		class?: string;
		component?: 'a' | 'span';
	}

	let { type, class: className = '', component = 'a' }: Props = $props();

	const info = $derived.by(() => {
		const { name, namespace } = splitDisplayName(type.name);
		const linkable = type.known && component === 'a';
		return {
			name,
			namespace,
			href: resolve(factPath(type.id)),
			linkable
		};
	});
</script>

{#if info.linkable}
	<a
		href={info.href}
		class="fact-type-simple d-flex flex-column text-decoration-none {className}"
		title={type.name}
	>
		<span class="type-name fw-medium text-primary">{info.name}</span>
		{#if info.namespace}
			<span class="type-ns text-muted small opacity-75">{info.namespace}</span>
		{/if}
	</a>
{:else}
	<span
		class="fact-type-simple d-flex flex-column text-decoration-none {className}"
		title={type.name}
	>
		<span class="type-name fw-medium">{info.name}</span>
		{#if info.namespace}
			<span class="type-ns text-muted small opacity-75">{info.namespace}</span>
		{/if}
	</span>
{/if}

<style>
	.fact-type-simple {
		line-height: 1.2;
		padding: 0.125rem 0.25rem;
		margin: -0.125rem -0.25rem;
		border-radius: 0.375rem;
		transition: all 0.15s ease-in-out;
		color: inherit;
		width: fit-content;
	}

	a.fact-type-simple:hover {
		background-color: #f8f9fa;
		border-color: #dee2e6;
		text-decoration: none !important;
	}

	.type-name {
		font-size: 0.875rem;
	}
	.type-ns {
		font-size: 0.75rem;
		word-break: break-all;
	}
</style>
