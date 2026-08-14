<script lang="ts">
	import type { SessionFact, FactMatch } from '$lib/types/api';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import CopyButton from '$lib/components/ui/CopyButton.svelte';
	import CollapseToggleButton from '$lib/components/ui/CollapseToggleButton.svelte';
	import ConditionFactType from '$lib/components/rulebase/ConditionFactType.svelte';
	import FactOriginsBadge from '$lib/components/rulebase/FactOriginsBadge.svelte';

	interface Props {
		item: SessionFact | FactMatch;
		type: 'facts' | 'matches';
		showOrigins?: boolean;
	}

	let { item, type, showOrigins = true }: Props = $props();

	let expanded = $state(false);
	let expandedBindings = $state<Record<number, boolean>>({});

	// A match row renders the wrapped fact (identity + origins) plus one
	// expandable block per binding set; a fact row renders the fact itself.
	const fact = $derived<SessionFact>(
		type === 'matches' ? (item as FactMatch).fact : (item as SessionFact)
	);
	const bindings = $derived<Record<string, unknown>[]>(
		type === 'matches' ? (item as FactMatch).bindings : []
	);

	function dataString(data: unknown): string {
		return typeof data === 'string' ? data : JSON.stringify(data, null, 2);
	}

	const factDataString = $derived(dataString(fact.data));
	const origins = $derived(fact['inserted-from'] ?? []);
	const showOriginsBadge = $derived(showOrigins);

	function isBindingExpanded(i: number): boolean {
		return expandedBindings[i] ?? false;
	}

	function toggleBinding(i: number) {
		expandedBindings[i] = !(expandedBindings[i] ?? false);
	}
</script>

{#snippet factIdLink()}
	<div class="fact-id-cell py-2 px-3 d-flex flex-column border-end">
		<a
			href={resolve(`/session/facts/${fact.id}` as Pathname)}
			class="d-flex align-items-center text-decoration-none fw-bold text-primary w-100"
		>
			<span class="me-1">
				Fact ID: {fact.id}
			</span>
			{#if showOriginsBadge}
				<FactOriginsBadge {origins} />
			{/if}
			<i class="bi bi-chevron-right ms-auto fs-7 text-muted opacity-50 chevron-icon"></i>
		</a>
		<ConditionFactType type={fact.type} class="mt-1" />
	</div>
{/snippet}

{#snippet factToggle()}
	<div
		class="expression-toggle flex-shrink-0 d-flex align-items-center gap-2 px-3 bg-light bg-opacity-10"
	>
		<CollapseToggleButton {expanded} onclick={() => (expanded = !expanded)} />
		{#if expanded}
			<CopyButton text={factDataString} />
		{/if}
	</div>
{/snippet}

{#snippet bindingToggle(binding: Record<string, unknown>, i: number)}
	<div class="d-flex align-items-center gap-2">
		<CollapseToggleButton
			expanded={isBindingExpanded(i)}
			label={bindings.length > 1 ? `binding ${i + 1}` : 'expression'}
			onclick={() => toggleBinding(i)}
		/>
		{#if isBindingExpanded(i)}
			<CopyButton text={dataString(binding)} />
		{/if}
	</div>
{/snippet}

{#if type === 'facts'}
	<div class="session-activity-row d-flex flex-column border-bottom">
		<div class="d-flex">
			<div class="col-6">
				{@render factIdLink()}
			</div>
			<div class="col-6">
				{@render factToggle()}
			</div>
		</div>
		{#if expanded}
			<div class="border-top">
				<CodeBlock code={fact.data} language="json" expanded={true} hideHeader={true} />
			</div>
		{/if}
	</div>
{:else}
	<div class="session-activity-row d-flex flex-column border-bottom">
		<div class="d-flex">
			<div class="col-6">
				{@render factIdLink()}
			</div>
			<div class="col-6">
				<div
					class="expression-toggle-list flex-shrink-0 d-flex flex-column align-items-end gap-1 py-2 px-3 bg-light bg-opacity-10"
				>
					{#each bindings as binding, i (i)}
						{@render bindingToggle(binding, i)}
					{/each}
				</div>
			</div>
		</div>
		{#each bindings as binding, i (i)}
			{#if isBindingExpanded(i)}
				<div class="border-top">
					<CodeBlock code={binding} language="json" expanded={true} hideHeader={true} />
				</div>
			{/if}
		{/each}
	</div>
{/if}

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
