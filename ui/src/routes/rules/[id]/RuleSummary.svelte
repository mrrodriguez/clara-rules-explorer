<script lang="ts">
	import type { RuleSummary, SessionProductionActivityResponse } from '$lib/types/api';
	import RulebaseComponentSummaryHeader from '$lib/components/rulebase/RulebaseComponentSummaryHeader.svelte';
	import RulebaseComponentSummaryDescription from '$lib/components/rulebase/RulebaseComponentSummaryDescription.svelte';
	import DependencyRow from '$lib/components/rulebase/DependencyRow.svelte';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import LhsTabs from '$lib/components/rulebase/LhsTabs.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import SessionProductionActivity from '$lib/components/rulebase/SessionProductionActivity.svelte';
	import { rulePath } from '$lib/utils';
	import { appState } from '$lib/state/appState.svelte';
	import SourceSinkIndicators from '$lib/components/rulebase/SourceSinkIndicators.svelte';
	import UnlinkedRuleIndicator from '$lib/components/rulebase/UnlinkedRuleIndicator.svelte';
	import NoOutputTypesIndicator from '$lib/components/rulebase/NoOutputTypesIndicator.svelte';
	import DynamicDetectionIndicator from '$lib/components/rulebase/DynamicDetectionIndicator.svelte';
	import DynamicCallsiteList from '$lib/components/rulebase/DynamicCallsiteList.svelte';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		rule: RuleSummary;
		activity?: SessionProductionActivityResponse;
		fullView?: boolean;
	}

	let { rule, activity, fullView = false }: Props = $props();

	let fullViewHref = $derived(resolve(rulePath(rule.name, true) as Pathname));

	$effect(() => {
		if (fullView) {
			appState.setContextualNav(
				rule.upstream,
				rule.downstream,
				'rule',
				rule['lhs-types'],
				rule['insert-types'],
				rule['retract-types']
			);
			return () => {
				appState.clearContextualNav();
			};
		}
	});
</script>

<div class="card shadow-sm">
	<RulebaseComponentSummaryHeader
		type="rule"
		name={rule.name}
		{fullView}
		href={rulePath(rule.name, true)}
	>
		<SourceSinkIndicators
			isSource={rule['source-rule']}
			isSink={rule['sink-rule']}
			variant="badge"
		/>
		<UnlinkedRuleIndicator unlinkedRule={rule['unlinked-rule']} variant="badge" />
		<NoOutputTypesIndicator noOutputTypes={rule['no-output-types']} variant="badge" />
		<DynamicDetectionIndicator detection={rule['dynamic-insert-types-detected']} label="Inserts" />
		<DynamicDetectionIndicator
			detection={rule['dynamic-retract-types-detected']}
			label="Retracts"
		/>
	</RulebaseComponentSummaryHeader>

	<div class="card-body p-2 p-md-3">
		{#key rule.name}
			<RulebaseComponentSummaryDescription doc={rule.doc} />

			{#if !fullView}
				<DependencyRow upstream={rule.upstream} downstream={rule.downstream} {fullView} />

				<div class="row g-3">
					<div class="col-md-4">
						<ProductionReferenceCategory
							title="LHS Types (Input)"
							icon="bi-box-arrow-in-right"
							items={rule['lhs-types']}
							{fullView}
						/>
					</div>
					<div class="col-md-4">
						<ProductionReferenceCategory
							title="Insert Types (Output)"
							icon="bi-box-arrow-right"
							items={rule['insert-types']}
							{fullView}
						/>
					</div>
					{#if rule['retract-types'].length > 0}
						<div class="col-md-4">
							<ProductionReferenceCategory
								title="Retract Types"
								icon="bi-dash-circle"
								items={rule['retract-types']}
								{fullView}
							/>
						</div>
					{/if}
				</div>
			{/if}

			{#if fullView}
				<div class="row g-3">
					<!-- LHS Column -->
					<div class="col-lg-6 border-end pe-lg-3">
						<LhsTabs lhsForm={rule['lhs-form']} lhs={rule.lhs} />
					</div>

					<!-- RHS Column -->
					<div class="col-lg-6 ps-lg-3" style="overflow-x: auto;">
						<div class="sticky-top" style="top: 0.5rem; z-index: 10;">
							<h6 class="text-muted text-uppercase smaller fw-bold border-bottom pb-1 mb-2">
								RHS Action
							</h6>
							<CodeBlock code={rule['rhs-form']} language="clojure" expanded={true} />
						</div>
					</div>
				</div>

				<SessionProductionActivity {activity} />
			{/if}

			<DynamicCallsiteList detection={rule['dynamic-insert-types-detected']} label="Insert" />
			<DynamicCallsiteList detection={rule['dynamic-retract-types-detected']} label="Retract" />

			{#if !fullView}
				<div class="d-flex align-items-center gap-2 mt-3 pt-2 border-top">
					<a href={fullViewHref} class="btn btn-outline-primary btn-sm">
						<i class="bi bi-arrows-fullscreen me-1"></i> Full View
					</a>
					<span class="text-muted small"> See detailed LHS and RHS. </span>
				</div>
			{/if}
		{/key}
	</div>
</div>
