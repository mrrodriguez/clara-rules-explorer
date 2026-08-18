<script lang="ts">
	import type { ProductionReference, TypeReference } from '$lib/types/api';
	import { factPath, getShortName } from '$lib/utils';
	import { stableKeys } from '$lib/keys';
	import ReferenceListItem from '$lib/components/rulebase/nav/ReferenceListItem.svelte';
	import ProductionReferenceLink from '$lib/components/rulebase/ProductionReferenceLink.svelte';
	import { tooltip } from '$lib/actions/tooltip';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		type: TypeReference;
		active?: boolean;
		/**
		 * Role this type plays on the production currently in view. When
		 * present the row becomes expandable, revealing the upstream (input)
		 * or downstream (output) productions whose type bridge involves this
		 * type.
		 */
		role?: 'input' | 'output';
		upstream?: ProductionReference[];
		downstream?: ProductionReference[];
		fullView?: boolean;
	}

	let {
		type,
		active = false,
		role = undefined,
		upstream = [],
		downstream = [],
		fullView = false
	}: Props = $props();

	let expanded = $state(false);

	const href = $derived(resolve(factPath(type.id) as Pathname));

	const relatedRefs = $derived.by((): ProductionReference[] => {
		if (role === 'input') {
			return upstream.filter((ref) =>
				(ref.match ?? []).some((m) => m['consumer-type'].id === type.id)
			);
		}

		if (role === 'output') {
			return downstream.filter((ref) =>
				(ref.match ?? []).some((m) => m['producer-type'].id === type.id)
			);
		}

		return [];
	});

	const relatedRefKeys = $derived(stableKeys(relatedRefs, (ref) => ref.id));
	const hasRelated = $derived(relatedRefs.length > 0);
</script>

{#snippet actions()}
	{#if hasRelated}
		<button
			type="button"
			class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
			use:tooltip={expanded ? 'Collapse dependencies' : 'Show upstream/downstream'}
			aria-expanded={expanded}
			aria-label="Toggle upstream/downstream"
			onclick={() => (expanded = !expanded)}
		>
			<i class="bi {expanded ? 'bi-chevron-down' : 'bi-chevron-right'}"></i>
		</button>
	{/if}
	{#if type.known}
		<a
			{href}
			class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
			use:tooltip={`Open ${getShortName(type.name)}`}
			aria-label="Open {type.name}"
		>
			<i class="bi bi-box-arrow-up-right"></i>
		</a>
	{/if}
{/snippet}

{#if type.known}
	<ReferenceListItem
		title={type.name}
		fullName={type.name}
		activeColor="#0dcaf0"
		{active}
		copyable
		{actions}
	/>

	{#if expanded && hasRelated}
		<div class="list-group-item fact-type-details border-0 py-2 ps-3 bg-light-subtle">
			{#each relatedRefs as ref, i (relatedRefKeys[i])}
				<div class="mb-2">
					<ProductionReferenceLink {ref} {fullView} />
				</div>
			{/each}
		</div>
	{/if}
{:else}
	<ReferenceListItem title={type.name} fullName={type.name} muted />
{/if}

<style>
	.fact-type-details {
		font-size: 0.85rem;
	}

	/* Nested production rows sit inside this expanded panel rather than a
	 * list-group wrapper, so ReferenceListItem's transparent active-indicator
	 * left border would otherwise read as a missing border. Restore the
	 * normal list-group left border for those nested rows. */
	.fact-type-details :global(.list-group-item) {
		border-left: var(--bs-list-group-border-width) solid var(--bs-list-group-border-color);
	}
</style>
