<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import ReferenceListItem from '$lib/components/rulebase/nav/ReferenceListItem.svelte';
	import FactTypeInlineLink from '$lib/components/rulebase/FactTypeInlineLink.svelte';
	import Badge from '$lib/components/ui/Badge.svelte';
	import Popover from '$lib/components/ui/Popover.svelte';
	import { tooltip } from '$lib/actions/tooltip';
	import { getShortName, rulePath, queryPath } from '$lib/utils';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		ref: ProductionReference;
		fullView?: boolean;
		active?: boolean;
	}

	let { ref, fullView = false, active = false }: Props = $props();

	const path = $derived(
		resolve(
			(ref.type === 'rule' ? rulePath(ref.id, fullView) : queryPath(ref.id, fullView)) as Pathname
		)
	);

	const activeColor = $derived(ref.type === 'rule' ? '#0d6efd' : '#198754');

	const matches = $derived(ref.match ?? []);

	let popoverOpen = $state(false);
</script>

{#snippet badge()}
	<RulebaseComponentTypeBadge type={ref.type} />
{/snippet}

{#snippet actions()}
	{#if matches.length > 0}
		<Popover bind:open={popoverOpen} width={400}>
			{#snippet trigger(toggle)}
				<button
					type="button"
					class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
					use:tooltip={`Show type matches (${matches.length})`}
					aria-label="Show type matches"
					aria-expanded={popoverOpen}
					onclick={toggle}
				>
					<i class="bi bi-link-45deg"></i>
				</button>
			{/snippet}
			{#snippet content()}
				<div class="p-2 d-flex flex-column gap-2" style="max-height: 340px; overflow-y: auto;">
					<div class="d-flex align-items-center justify-content-between px-1">
						<h6 class="text-muted text-uppercase small fw-bold mb-0">Type matches</h6>
						<span class="badge bg-secondary-subtle text-secondary border border-secondary-subtle"
							>{matches.length}</span
						>
					</div>
					{#each matches as m (m['producer-type'].name + '>' + m['consumer-type'].name)}
						{#if m['producer-type'].id === m['consumer-type'].id}
							<div class="d-flex flex-column gap-1 border-start ps-2 pb-1">
								<FactTypeInlineLink type={m['producer-type']} />
							</div>
						{:else}
							<div class="d-flex flex-column gap-1 border-start ps-2 pb-1">
								<FactTypeInlineLink type={m['producer-type']} />
								<div class="d-flex align-items-center gap-1 text-muted small">
									<i class="bi bi-arrow-down"></i>
									<span class="text-uppercase fw-bold satisfies-label">satisfies</span>
									{#if m.via === 'retract'}
										<Badge variant="secondary" size="sm">retract</Badge>
									{/if}
								</div>
								<FactTypeInlineLink type={m['consumer-type']} />
							</div>
						{/if}
					{/each}
				</div>
			{/snippet}
		</Popover>
	{/if}

	<a
		href={path}
		class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
		use:tooltip={`Open ${getShortName(ref.name)}`}
		aria-label="Open {ref.name}"
	>
		<i class="bi bi-box-arrow-up-right"></i>
	</a>
{/snippet}

<ReferenceListItem title={ref.name} fullName={ref.name} {activeColor} {badge} {actions} {active} />

<style>
	.satisfies-label {
		font-size: 0.6rem;
		letter-spacing: 0.06em;
	}
</style>
