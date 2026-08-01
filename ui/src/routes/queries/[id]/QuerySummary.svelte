<script lang="ts">
	import type { QuerySummary, SessionProductionActivityResponse } from '$lib/types/api';
	import RulebaseComponentSummaryHeader from '$lib/components/rulebase/RulebaseComponentSummaryHeader.svelte';
	import RulebaseComponentSummaryDescription from '$lib/components/rulebase/RulebaseComponentSummaryDescription.svelte';
	import DependencyRow from '$lib/components/rulebase/DependencyRow.svelte';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import LhsList from '$lib/components/rulebase/LhsList.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import SessionProductionActivity from '$lib/components/rulebase/SessionProductionActivity.svelte';
	import { queryPath } from '$lib/utils';
	import { appState } from '$lib/state/appState.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		query: QuerySummary;
		activity?: SessionProductionActivityResponse;
		fullView?: boolean;
	}

	let { query, activity, fullView = false }: Props = $props();

	let fullViewHref = $derived(resolve(queryPath(query.name, true) as Pathname));

	let lhsTab = $state<'expression' | 'conditions'>('expression');

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
				<h6 class="text-muted text-uppercase smaller fw-bold border-bottom pb-1 mb-2">LHS</h6>
				<!-- Tab navigation -->
				<ul class="nav nav-underline lhs-tabs mb-2" role="tablist">
					<li class="nav-item" role="presentation">
						<button
							class="nav-link {lhsTab === 'expression' ? 'active' : ''}"
							role="tab"
							aria-selected={lhsTab === 'expression'}
							onclick={() => (lhsTab = 'expression')}
						>
							Expression
						</button>
					</li>
					<li class="nav-item" role="presentation">
						<button
							class="nav-link {lhsTab === 'conditions' ? 'active' : ''}"
							role="tab"
							aria-selected={lhsTab === 'conditions'}
							onclick={() => (lhsTab = 'conditions')}
						>
							Conditions
						</button>
					</li>
				</ul>

				<!-- Tab content -->
				{#if lhsTab === 'expression'}
					<CodeBlock code={query['lhs-form']} language="clojure" expanded={true} />
				{:else}
					<LhsList lhs={query.lhs} />
				{/if}

				<SessionProductionActivity {activity} />
			{/if}

			{#if !fullView}
				<div class="d-flex align-items-center gap-2 mt-3 pt-2 border-top">
					<a href={fullViewHref} class="btn btn-outline-success btn-sm">
						<i class="bi bi-arrows-fullscreen me-1"></i> Full View
					</a>
					<span class="text-muted small"> See detailed LHS. </span>
				</div>
			{/if}
		{/key}
	</div>
</div>

<style>
	.lhs-tabs {
		--bs-nav-underline-link-padding-x: 0.75rem;
		--bs-nav-underline-link-padding-y: 0.375rem;
		font-size: 0.8rem;
	}
	.lhs-tabs .nav-link {
		cursor: pointer;
	}
</style>
