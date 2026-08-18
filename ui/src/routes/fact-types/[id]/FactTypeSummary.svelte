<script lang="ts">
	import type { FactTypeSummary } from '$lib/types/api';
	import RulebaseComponentSummaryHeader from '$lib/components/rulebase/RulebaseComponentSummaryHeader.svelte';
	import ReferenceCategory from '$lib/components/rulebase/ReferenceCategory.svelte';
	import { factPath } from '$lib/utils';

	interface Props {
		factType: FactTypeSummary;
		fullView?: boolean;
	}

	let { factType, fullView = false }: Props = $props();
</script>

<div class="card shadow-sm">
	<RulebaseComponentSummaryHeader
		type="fact"
		name={factType.name}
		id={factType.id}
		{fullView}
		href={factPath(factType.id)}
	/>

	<div class="card-body p-2 p-md-3">
		<div class="row g-4">
			<div class="col-md-6">
				<ReferenceCategory
					title="Used by Rules"
					icon="bi-list-check"
					items={factType['used-by-rules']}
					{fullView}
				/>

				<ReferenceCategory
					title="Used by Queries"
					icon="bi-search"
					items={factType['used-by-queries']}
					{fullView}
					class="mt-4"
				/>
			</div>

			<div class="col-md-6">
				<ReferenceCategory
					title="Inserted by Rules"
					icon="bi-box-arrow-right"
					items={factType['inserted-by-rules']}
					{fullView}
				/>

				<ReferenceCategory
					title="Retracted by Rules"
					icon="bi-dash-circle"
					items={factType['retracted-by-rules']}
					{fullView}
					class="mt-4"
				/>
			</div>
		</div>

		<div class="mt-4">
			<ReferenceCategory
				title="Hierarchy (Ancestors)"
				icon="bi-diagram-3"
				items={factType.ancestors}
				itemKind="type"
			>
				<div
					class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed"
				>
					No ancestors — this type sits at the root of its hierarchy.
				</div>
			</ReferenceCategory>
			{#if factType.ancestors && factType.ancestors.length > 0}
				<p class="text-muted small ps-2 mt-1 mb-0">
					Ancestors are listed in hierarchy order (descendants before their own ancestors).
					Italicized entries are hierarchy-only types with no rulebase usage — they are not
					linkable.
				</p>
			{/if}
		</div>

		<div class="mt-4">
			<ReferenceCategory
				title="Hierarchy (Descendants)"
				icon="bi-diagram-3-fill"
				items={factType.descendants}
				itemKind="type"
			>
				<div
					class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed"
				>
					No descendants — this type sits at a leaf of its hierarchy.
				</div>
			</ReferenceCategory>
			{#if factType.descendants && factType.descendants.length > 0}
				<p class="text-muted small ps-2 mt-1 mb-0">
					Descendants are listed in hierarchy order (direct descendants first, then their
					descendants). Italicized entries are hierarchy-only types with no rulebase usage — they
					are not linkable.
				</p>
			{/if}
		</div>
	</div>
</div>
