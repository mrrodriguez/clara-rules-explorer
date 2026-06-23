<script lang="ts">
	import type { FactTypeSummary } from '$lib/types/api';
	import RulebaseComponentSummaryHeader from '$lib/components/rulebase/RulebaseComponentSummaryHeader.svelte';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import { factPath, splitQualifiedName } from '$lib/utils';

	interface Props {
		factType: FactTypeSummary;
		fullView?: boolean;
	}

	let { factType, fullView = false }: Props = $props();

	function toRef(fqName: string, type: 'rule' | 'query') {
		const { namespace } = splitQualifiedName(fqName);
		return {
			name: fqName,
			ns: namespace,
			type
		};
	}
</script>

<div class="card shadow-sm">
	<RulebaseComponentSummaryHeader
		type="fact"
		name={factType.name}
		{fullView}
		href={factPath(factType.name)}
	/>

	<div class="card-body p-2 p-md-3">
		<div class="row g-4">
			<div class="col-md-6">
				<ProductionReferenceCategory
					title="Used by Rules"
					icon="bi-list-check"
					items={factType['used-by-rules'].map((n) => toRef(n, 'rule'))}
					{fullView}
				/>

				<ProductionReferenceCategory
					title="Used by Queries"
					icon="bi-search"
					items={factType['used-by-queries'].map((n) => toRef(n, 'query'))}
					{fullView}
					class="mt-4"
				/>
			</div>

			<div class="col-md-6">
				<ProductionReferenceCategory
					title="Inserted by Rules"
					icon="bi-box-arrow-right"
					items={factType['inserted-by-rules'].map((n) => toRef(n, 'rule'))}
					{fullView}
				/>

				<ProductionReferenceCategory
					title="Retracted by Rules"
					icon="bi-dash-circle"
					items={factType['retracted-by-rules'].map((n) => toRef(n, 'rule'))}
					{fullView}
					class="mt-4"
				/>
			</div>
		</div>
	</div>
</div>
