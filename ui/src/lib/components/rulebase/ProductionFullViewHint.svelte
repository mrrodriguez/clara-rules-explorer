<script lang="ts">
	interface Props {
		type?: 'rule' | 'query';
	}
	let { type = 'rule' }: Props = $props();
</script>

<div class="alert alert-light border small py-2 mb-0">
	<i class="bi bi-info-circle me-2"></i>
	{#if type === 'rule'}
		Click <strong>Full View</strong> to see the detailed LHS conditions and RHS code.
	{:else}
		Click <strong>Full View</strong> to see the detailed LHS conditions.
	{/if}
</div>
