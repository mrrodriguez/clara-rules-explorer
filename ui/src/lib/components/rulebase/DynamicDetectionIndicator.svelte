<script lang="ts">
	import type { DynamicDetectionInfo } from '$lib/types/api';

	interface Props {
		detection?: DynamicDetectionInfo;
		label: string;
		variant?: 'badge' | 'icon';
	}

	let { detection, label, variant = 'badge' }: Props = $props();

	let resolution = $derived(detection?.resolution);
	let color = $derived(
		resolution === 'full' ? 'success' : resolution === 'partial' ? 'warning' : 'danger'
	);
	let icon = $derived(
		resolution === 'full'
			? 'bi-check-circle-fill'
			: resolution === 'partial'
				? 'bi-exclamation-circle-fill'
				: 'bi-question-circle-fill'
	);
	let title = $derived(
		resolution === 'full'
			? `Dynamic ${label}: fully resolved`
			: resolution === 'partial'
				? `Dynamic ${label}: partially resolved`
				: `Dynamic ${label}: unresolved`
	);
</script>

{#if detection}
	{#if variant === 'badge'}
		<span class="badge rounded-pill text-bg-{color} ms-2" {title}>
			<i class="bi {icon} me-1"></i> Dynamic {label}
		</span>
	{:else}
		<i class="bi {icon} text-{color}" {title}></i>
	{/if}
{/if}
