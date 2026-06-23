<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		variant?:
			| 'primary'
			| 'secondary'
			| 'info'
			| 'success'
			| 'warning'
			| 'danger'
			| 'ghost'
			| 'outline';
		pill?: boolean;
		uppercase?: boolean;
		size?: 'sm' | 'md';
		class?: string;
		children: Snippet;
	}

	let {
		variant = 'primary',
		pill = true,
		uppercase = false,
		size = 'md',
		class: className = '',
		children
	}: Props = $props();

	const variantClass = $derived.by(() => {
		switch (variant) {
			case 'ghost':
				return 'bg-white text-muted border';
			case 'outline':
				return 'bg-transparent text-muted border';
			default:
				return `bg-${variant} bg-opacity-10 text-${variant} border border-${variant} border-opacity-25`;
		}
	});
</script>

<span
	class="custom-badge {variantClass} {pill ? 'rounded-pill' : ''} {uppercase
		? 'text-uppercase'
		: ''} {size} {className}"
>
	{@render children()}
</span>

<style>
	.custom-badge {
		display: inline-flex;
		align-items: center;
		font-weight: 600;
		line-height: 1;
		white-space: nowrap;
		vertical-align: baseline;
		border-radius: 0.375rem;
	}

	.md {
		padding: 0.35em 0.65em;
		font-size: 0.75em;
	}

	.sm {
		padding: 0.25em 0.5em;
		font-size: 0.65em;
	}

	.rounded-pill {
		border-radius: 50rem;
	}

	/* Bootstrap badge compatibility */
	:global(.custom-badge) {
		text-align: center;
	}
</style>
