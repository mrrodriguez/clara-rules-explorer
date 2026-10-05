<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import CopyableTitle from '$lib/components/ui/CopyableTitle.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import ReferenceCategory from '$lib/components/rulebase/ReferenceCategory.svelte';
	import { factPath } from '$lib/utils';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		fact: SessionFact;
	}

	let { fact }: Props = $props();

	const typeHref = $derived(resolve(factPath(fact.type.id) as Pathname));

	type RelationKey =
		| 'inserted-from'
		| 'supports-insertions-of'
		| 'supports-results-of'
		| 'matches-condition-of'
		| 'blocks-condition-of'
		| 'blocking-candidate-of';

	interface RelationCategory {
		title: string;
		icon: string;
		key: RelationKey;
		emptyMessage: string;
	}

	const relationCategories = $derived<RelationCategory[]>([
		{
			title: 'Inserted From (Lineage)',
			icon: 'bi-diagram-2',
			key: 'inserted-from',
			emptyMessage: 'Inserted as a root fact (no rule origin)'
		},
		{
			title: 'Supports Insertions Of',
			icon: 'bi-box-arrow-in-right',
			key: 'supports-insertions-of',
			emptyMessage: 'This fact supports no rule insertions.'
		},
		{
			title: 'Supports Results Of',
			icon: 'bi-list-check',
			key: 'supports-results-of',
			emptyMessage: 'This fact supports no query results.'
		},
		{
			title: 'Matches Condition Of',
			icon: 'bi-filter-circle',
			key: 'matches-condition-of',
			emptyMessage: 'This fact passes no positive condition.'
		},
		{
			title: 'Blocks Condition Of',
			icon: 'bi-x-octagon',
			key: 'blocks-condition-of',
			emptyMessage: 'This fact blocks the condition of nothing.'
		},
		{
			title: 'Blocking Candidate Of',
			icon: 'bi-dash-circle',
			key: 'blocking-candidate-of',
			emptyMessage: 'This fact is a blocking candidate of nothing.'
		}
	]);
</script>

<div class="fact-detail">
	<div class="card shadow-sm mb-4">
		<div class="card-header bg-white d-flex justify-content-between align-items-center py-3">
			<div class="d-flex align-items-center">
				<div
					class="bg-primary text-white rounded p-2 me-3 d-flex align-items-center justify-content-center"
					style="width: 40px; height: 40px;"
				>
					<i class="bi bi-hash fs-4"></i>
				</div>
				<div>
					<h5 class="mb-0 fw-bold">Fact {fact.id}</h5>
					<div class="fs-7 text-muted">Reference ID</div>
				</div>
			</div>
			<div class="text-end d-flex align-items-start gap-2">
				<CopyableTitle fullName={fact.type.name} size="md" />
				{#if fact.type.known}
					<a
						href={typeHref}
						class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
						title="View fact type: {fact.type.name}"
						aria-label="View fact type"
					>
						<i class="bi bi-box-arrow-up-right"></i>
					</a>
				{/if}
			</div>
		</div>
		<div class="card-body p-0">
			<CodeBlock code={JSON.stringify(fact.data, null, 2)} language="json" expanded={true} />
		</div>
	</div>

	<div class="row g-4">
		{#each relationCategories as category (category.key)}
			<div class="col-md-6">
				<ReferenceCategory title={category.title} icon={category.icon} items={fact[category.key]}>
					<div
						class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed"
					>
						{category.emptyMessage}
					</div>
				</ReferenceCategory>
			</div>
		{/each}
	</div>
</div>
