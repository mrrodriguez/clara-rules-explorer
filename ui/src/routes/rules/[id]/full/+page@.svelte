<script lang="ts">
	import RuleSummary from '../RuleSummary.svelte';
	import Breadcrumbs from '$lib/components/ui/Breadcrumbs.svelte';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();
</script>

{#if data.rule}
	<div class="container py-4">
		<Breadcrumbs items={data.breadcrumbs} />

		<RuleSummary rule={data.rule} activity={data.activity} fullView={true} />
	</div>
{/if}
