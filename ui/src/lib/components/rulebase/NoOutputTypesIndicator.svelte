<script lang="ts">
	interface Props {
		noOutputTypes?: boolean | null;
		variant?: 'badge' | 'icon';
	}

	let { noOutputTypes, variant = 'badge' }: Props = $props();
</script>

{#if noOutputTypes}
	{#if variant === 'badge'}
		<span
			class="badge rounded-pill text-bg-secondary ms-2"
			title="This rule has been reviewed and has no downstream inserts or retracts."
		>
			<i class="bi bi-sign-stop me-1"></i> No Output
		</span>
	{:else}
		<i
			class="bi bi-sign-stop text-secondary"
			title="No Output — Reviewed: no downstream inserts or retracts"
		></i>
	{/if}
{/if}
