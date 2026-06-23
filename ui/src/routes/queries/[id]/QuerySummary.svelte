<script lang="ts">
	import type { QuerySummary, SessionProductionActivityResponse } from '$lib/types/api';
	import RulebaseComponentSummaryHeader from '$lib/components/rulebase/RulebaseComponentSummaryHeader.svelte';
	import RulebaseComponentSummaryDescription from '$lib/components/rulebase/RulebaseComponentSummaryDescription.svelte';
	import DependencyRow from '$lib/components/rulebase/DependencyRow.svelte';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import ProductionFullViewHint from '$lib/components/rulebase/ProductionFullViewHint.svelte';
	import LhsList from '$lib/components/rulebase/LhsList.svelte';
	import SessionProductionActivity from '$lib/components/rulebase/SessionProductionActivity.svelte';
	import { queryPath } from '$lib/utils';
	import { appState } from '$lib/state/appState.svelte';

	interface Props {
		query: QuerySummary;
		activity?: SessionProductionActivityResponse;
		fullView?: boolean;
	}

	let { query, activity, fullView = false }: Props = $props();

	$effect(() => {
		if (fullView) {
			appState.setContextualNav(query.upstream, query.downstream, 'query', query['lhs-types']);
			return () => {
				appState.clearContextualNav();
			};
		}
	});
</script>

<div class="card shadow-sm">
	<RulebaseComponentSummaryHeader
		type="query"
		name={query.name}
		{fullView}
		href={queryPath(query.name, true)}
	>
		{#if query.params.length > 0}
			<div class="ms-4 d-flex align-items-center gap-1">
				<span class="text-muted small text-uppercase fw-bold me-2">Params:</span>
				{#each query.params as param (param)}
					<span class="badge bg-secondary-subtle text-secondary border border-secondary-subtle"
						>{param}</span
					>
				{/each}
			</div>
		{/if}
	</RulebaseComponentSummaryHeader>

	<div class="card-body p-2 p-md-3">
		{#key query.name}
			<RulebaseComponentSummaryDescription doc={query.doc} />

			{#if !fullView}
				<DependencyRow upstream={query.upstream} downstream={query.downstream} {fullView} />

				<div class="row g-3">
					<div class="col-md-12">
						<ProductionReferenceCategory
							title="Matched Types"
							icon="bi-box-arrow-in-right"
							items={query['lhs-types']}
							{fullView}
						/>
					</div>
				</div>
			{/if}

			{#if fullView}
				<h6 class="text-muted text-uppercase smaller fw-bold border-bottom pb-1 mb-2">
					LHS Conditions
				</h6>
				<LhsList lhs={query.lhs} />

				<SessionProductionActivity {activity} />
			{:else}
				<ProductionFullViewHint type="query" />
			{/if}
		{/key}
	</div>
</div>
