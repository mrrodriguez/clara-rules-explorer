<script lang="ts">
	interface Props {
		isSource?: boolean;
		isSink?: boolean;
		variant?: 'badge' | 'icon';
	}

	let { isSource = false, isSink = false, variant = 'badge' }: Props = $props();
</script>

{#if isSource}
	{#if variant === 'badge'}
		<span
			class="badge rounded-pill text-bg-warning ms-2"
			title="Source Rule: No upstream dependencies"
		>
			<i class="bi bi-box-arrow-in-right me-1"></i> Source
		</span>
	{:else}
		<i class="bi bi-box-arrow-in-right text-warning" title="Source Rule"></i>
	{/if}
{/if}

{#if isSink}
	{#if variant === 'badge'}
		<span
			class="badge rounded-pill text-bg-secondary ms-2"
			title="Sink Rule: No downstream consumers"
		>
			<i class="bi bi-box-arrow-right me-1"></i> Sink
		</span>
	{:else}
		<i class="bi bi-box-arrow-right text-secondary" title="Sink Rule"></i>
	{/if}
{/if}
