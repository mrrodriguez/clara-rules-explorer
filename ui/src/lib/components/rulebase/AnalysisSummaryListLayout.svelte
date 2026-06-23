<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		sidebar: Snippet;
		children: Snippet;
	}

	let { sidebar, children }: Props = $props();
</script>

<div class="container-fluid p-0 m-0">
	<div class="row g-0">
		<div class="col-md-4 col-lg-3 border-end">
			{@render sidebar()}
		</div>
		<div
			class="col-md-8 col-lg-9 p-4"
			style="height: calc(100vh - var(--navbar-height)); overflow-y: auto;"
		>
			{@render children()}
		</div>
	</div>
</div>
