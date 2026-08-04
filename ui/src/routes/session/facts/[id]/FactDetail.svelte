<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import CopyableTitle from '$lib/components/ui/CopyableTitle.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import ProductionReferenceCategory from '$lib/components/rulebase/ProductionReferenceCategory.svelte';
	import { factPath } from '$lib/utils';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		fact: SessionFact;
	}

	let { fact }: Props = $props();

	const typeHref = $derived(resolve(factPath(fact.type.id) as Pathname));
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
				<a
					href={typeHref}
					class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
					title="View fact type: {fact.type.name}"
					aria-label="View fact type"
				>
					<i class="bi bi-box-arrow-up-right"></i>
				</a>
			</div>
		</div>
		<div class="card-body p-0">
			<CodeBlock code={JSON.stringify(fact.data, null, 2)} language="json" expanded={true} />
		</div>
	</div>

	<div class="row g-4">
		<!-- Lineage (Origins) -->
		<div class="col-md-6">
			<ProductionReferenceCategory
				title="Inserted From (Lineage)"
				icon="bi-diagram-2"
				items={fact['inserted-from']}
			>
				<div
					class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed"
				>
					Inserted as a root fact (no rule origin)
				</div>
			</ProductionReferenceCategory>
		</div>

		<!-- Impact (Usage) -->
		<div class="col-md-6">
			<ProductionReferenceCategory
				title="Used By (Impact)"
				icon="bi-lightning"
				items={fact['used-by']}
			>
				<div
					class="p-3 text-muted text-center fs-7 bg-light rounded fst-italic border border-dashed"
				>
					This fact is not currently being used by any rules or queries.
				</div>
			</ProductionReferenceCategory>
		</div>
	</div>
</div>
