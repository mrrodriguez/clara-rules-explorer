<script lang="ts">
	import QuerySummary from '../QuerySummary.svelte';
	import Breadcrumbs from '$lib/components/ui/Breadcrumbs.svelte';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();
</script>

{#if data.query}
	<div class="container py-4">
		<Breadcrumbs items={data.breadcrumbs} />

		<QuerySummary query={data.query} activity={data.activity} fullView={true} />
	</div>
{/if}
