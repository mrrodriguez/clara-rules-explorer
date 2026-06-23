<script lang="ts">
	interface Props {
		doc: string | null | undefined;
	}
	let { doc }: Props = $props();
</script>

{#if doc}
	<div class="mb-3 px-1">
		<h6 class="text-muted text-uppercase smaller fw-bold mb-1">Description</h6>
		<p class="lead mb-0 small">{doc}</p>
	</div>
{/if}
