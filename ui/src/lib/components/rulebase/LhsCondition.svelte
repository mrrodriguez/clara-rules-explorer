<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { LhsElement } from '$lib/types/api';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';
	import LhsCondition from '$lib/components/rulebase/LhsCondition.svelte';
	import ConditionFactType from '$lib/components/rulebase/ConditionFactType.svelte';

	interface Props {
		condition: LhsElement | unknown[];
		depth?: number;
	}

	let { condition, depth = 0 }: Props = $props();

	function isNested(c: LhsElement | unknown[]): c is unknown[] {
		return Array.isArray(c);
	}

	const nested = $derived.by(() => {
		if (!isNested(condition)) return null;
		return {
			type: condition[0] as string,
			conditions: condition.slice(1)
		};
	});

	function formatValue(val: unknown): string {
		if (val === null || val === undefined) return '';
		if (typeof val === 'string') return val;
		return JSON.stringify(val);
	}

	const ignoredKeys = new Set([
		'type',
		'constraints',
		'accumulator',
		'from',
		'result-binding',
		'fact-binding'
	]);

	// Type cast for convenience in template
	const leaf = $derived(condition as LhsElement);
</script>

{#snippet property(label: string, valueClass: string = '', content: Snippet)}
	<div class="row g-0 py-1 align-items-baseline">
		<div
			class="col-auto text-muted fw-bold text-uppercase ps-3"
			style="width: 120px; font-size: 0.7rem;"
		>
			{label}
		</div>
		<div class="col pe-3 {valueClass}">
			{@render content()}
		</div>
	</div>
{/snippet}

{#snippet textProperty(label: string, value: string, valueClass: string = '')}
	<div class="row g-0 py-1 align-items-baseline">
		<div
			class="col-auto text-muted fw-bold text-uppercase ps-3"
			style="width: 120px; font-size: 0.7rem;"
		>
			{label}
		</div>
		<div class="col pe-3 font-monospace {valueClass}">
			{value}
		</div>
	</div>
{/snippet}

{#snippet factType()}
	<ConditionFactType type={leaf.type!} />
{/snippet}

{#snippet fromCondition()}
	<LhsCondition condition={leaf.from!} depth={depth + 1} />
{/snippet}

<div class="lhs-condition {depth > 0 ? 'ms-3 mt-1 border-start ps-2' : ''}">
	{#if nested}
		<div class="mb-1">
			<span class="badge bg-secondary text-uppercase nested-badge">{nested.type}</span>
		</div>
		{#each nested.conditions as subCondition, i (i)}
			<LhsCondition condition={subCondition as LhsElement | unknown[]} depth={depth + 1} />
		{/each}
	{:else}
		<div class="card border-light bg-light-subtle mb-2">
			<div class="card-body p-0 container-fluid property-container">
				{#if leaf.type}
					{@render property('Fact Type', 'text-primary', factType)}
				{/if}

				{#if leaf['fact-binding']}
					{@render textProperty('Binding', leaf['fact-binding'], 'text-success')}
				{/if}

				{#if leaf['result-binding']}
					{@render textProperty('Result', leaf['result-binding'], 'text-success')}
				{/if}

				{#if leaf.accumulator}
					{@render textProperty('Accumulator', leaf.accumulator[0], 'text-info')}
				{/if}

				{#if leaf.from}
					{@render property('From', 'p-0', fromCondition)}
				{/if}

				{#each Object.entries(condition) as [key, value] (key)}
					{#if !ignoredKeys.has(key)}
						{@render textProperty(key, formatValue(value))}
					{/if}
				{/each}

				{#if leaf.constraints}
					<div class="row g-0 py-1">
						<div
							class="col-12 text-muted fw-bold text-uppercase ps-3 pt-2"
							style="font-size: 0.7rem;"
						>
							Constraints
						</div>
						<div class="col-12 p-0">
							<CodeBlock code={leaf.constraints} language="clojure" />
						</div>
					</div>
				{/if}
			</div>
		</div>
	{/if}
</div>

<style>
	.lhs-condition {
		position: relative;
	}

	.nested-badge {
		font-size: 0.65rem;
	}

	.property-container {
		font-size: 0.8rem;
	}
</style>
