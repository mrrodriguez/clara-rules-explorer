<script lang="ts">
	import type { UnlinkedRuleInfo } from '$lib/types/api';

	interface Props {
		unlinkedRule?: UnlinkedRuleInfo | null;
		variant?: 'badge' | 'icon';
	}

	let { unlinkedRule, variant = 'badge' }: Props = $props();
</script>

{#if unlinkedRule}
	{#if variant === 'badge'}
		<span
			class="badge rounded-pill text-bg-warning ms-2"
			title="Unlinked RHS: {unlinkedRule.reason}"
		>
			<i class="bi bi-exclamation-triangle-fill me-1"></i> Unlinked RHS
		</span>
	{:else}
		<i
			class="bi bi-exclamation-triangle-fill text-warning"
			title="Unlinked RHS: {unlinkedRule.reason}"
		></i>
	{/if}
{/if}
