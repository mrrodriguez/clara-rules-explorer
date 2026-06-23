<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import CopyButton from '$lib/components/ui/CopyButton.svelte';
	import CollapseToggleButton from '$lib/components/ui/CollapseToggleButton.svelte';
	import ConditionFactType from '$lib/components/rulebase/ConditionFactType.svelte';
	import FactOriginsBadge from '$lib/components/rulebase/FactOriginsBadge.svelte';

	interface Props {
		item: SessionFact;
		type: 'facts' | 'matches';
		showOrigins?: boolean;
	}

	let { item, type, showOrigins = true }: Props = $props();

	let expanded = $state(false);

	const itemDataString = $derived(
		typeof item.data === 'string' ? item.data : JSON.stringify(item.data, null, 2)
	);

	const origins = $derived(item['inserted-from'] ?? []);
	const showOriginsBadge = $derived(type === 'facts' && showOrigins);
</script>

{#snippet factIdLink()}
	<div class="fact-id-cell py-2 px-3 d-flex flex-column border-end">
		<a
			href={resolve(`/session/facts/${item.id}` as Pathname)}
			class="d-flex align-items-center text-decoration-none fw-bold text-primary w-100"
		>
			<span class="me-1">
				Fact ID: {item.id}
			</span>
			{#if showOriginsBadge}
				<FactOriginsBadge {origins} />
			{/if}
			<i class="bi bi-chevron-right ms-auto fs-7 text-muted opacity-50 chevron-icon"></i>
		</a>
		<ConditionFactType type={item.type} class="mt-1" />
	</div>
{/snippet}

{#snippet expressionToggle()}
	<div
		class="expression-toggle flex-shrink-0 d-flex align-items-center gap-2 px-3 bg-light bg-opacity-10"
	>
		<CollapseToggleButton {expanded} onclick={() => (expanded = !expanded)} />
		{#if expanded}
			<CopyButton text={itemDataString} />
		{/if}
	</div>
{/snippet}

<div class="session-activity-row d-flex flex-column border-bottom">
	<div class="d-flex">
		<div class="col-6">
			{@render factIdLink()}
		</div>
		<div class="col-6">
			{@render expressionToggle()}
		</div>
	</div>
	{#if expanded}
		<div class="border-top">
			<CodeBlock code={item.data} language="json" expanded={true} hideHeader={true} />
		</div>
	{/if}
</div>

<style>
	.fact-id-cell {
		border-left: 3px solid transparent;
		cursor: default;
		transition:
			background-color 0.2s,
			border-left-color 0.2s;
	}

	.fact-id-cell:hover {
		background-color: #f8f9fa;
		border-left-color: #0d6efd;
	}

	.fact-id-cell:hover .chevron-icon {
		opacity: 1 !important;
	}

	.expression-toggle {
		min-width: 130px;
	}

	/* Remove CodeBlock's internal border since we provide one */
	:global(.session-activity-row .code-block) {
		border: none !important;
		border-radius: 0 !important;
		margin-bottom: 0 !important;
	}
</style>
