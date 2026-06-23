<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import SessionActivityList from '$lib/components/rulebase/SessionActivityList.svelte';

	export interface ActivityCategory {
		title: string;
		type: 'facts';
		items: SessionFact[];
		emptyText?: string;
	}

	interface Props {
		categories: ActivityCategory[];
		emptyText?: string;
	}

	let { categories = [], emptyText = 'No session activity recorded.' }: Props = $props();

	const activeCategories = $derived(categories.filter((c) => c.items.length > 0));
</script>

<div class="mt-4 border-top pt-4">
	<ProductionReferenceCategory title="Current Session Activity" icon="bi-play-circle-fill">
		{#if activeCategories.length === 0}
			<div class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed">
				{emptyText}
			</div>
		{:else}
			<div class="d-flex flex-column gap-3">
				{#each activeCategories as category (category.title)}
					<div>
						<div class="mb-2 fs-7 fw-bold text-muted ps-1">
							{category.title} ({category.items.length})
						</div>
						<SessionActivityList items={category.items} type={category.type} />
					</div>
				{/each}
			</div>
		{/if}
	</ProductionReferenceCategory>
</div>
