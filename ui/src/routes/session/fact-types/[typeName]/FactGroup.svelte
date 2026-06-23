<script lang="ts">
	import type { SessionFact } from '$lib/types/api';
	import SessionActivityRow from '$lib/components/rulebase/SessionActivityRow.svelte';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import RulebaseComponentTypeBadge from '$lib/components/rulebase/RulebaseComponentTypeBadge.svelte';
	import Badge from '$lib/components/ui/Badge.svelte';
	import { rulePath, queryPath } from '$lib/utils';

	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	interface Props {
		name: string;
		type: 'rule' | 'query' | 'root';
		instances: SessionFact[];
	}

	let { name, type, instances }: Props = $props();
	let expanded = $state(false);

	const href = $derived(
		type === 'rule' ? rulePath(name) : type === 'query' ? queryPath(name) : null
	);
</script>

<div
	class="fact-group mb-3 border rounded overflow-hidden shadow-sm transition-all {expanded
		? 'ring-primary'
		: ''}"
>
	<div
		class="group-header d-flex align-items-center bg-white p-2 px-3 clickable"
		onclick={() => (expanded = !expanded)}
		role="button"
		tabindex="0"
		onkeydown={(e) => e.key === 'Enter' && (expanded = !expanded)}
	>
		<div
			class="me-3 d-flex align-items-center justify-content-center text-muted"
			style="width: 20px;"
		>
			<i class="bi {expanded ? 'bi-chevron-down' : 'bi-chevron-right'} fs-6"></i>
		</div>

		<div class="flex-grow-1 d-flex align-items-center overflow-hidden">
			{#if type !== 'root'}
				<div class="d-flex align-items-center overflow-hidden">
					<QualifiedName fullName={name} size="sm" class="me-2" />
					<RulebaseComponentTypeBadge {type} />
				</div>
			{:else}
				<span class="fw-bold text-muted small text-uppercase text-tracking-wide">{name}</span>
			{/if}
		</div>

		<div class="ms-3 d-flex align-items-center">
			<span class="fs-7 text-muted text-uppercase fw-bold me-2 d-none d-lg-inline">Facts</span>
			<Badge variant="primary" class="px-3">
				{instances.length}
			</Badge>

			{#if type !== 'root' && href}
				<a
					href={resolve(href as Pathname)}
					class="btn btn-sm btn-outline-secondary border-0 ms-2 p-1 d-flex align-items-center justify-content-center"
					onclick={(e) => e.stopPropagation()}
					title="View {type} summary"
				>
					<i class="bi bi-box-arrow-up-right fs-7"></i>
				</a>
			{/if}
		</div>
	</div>

	{#if expanded}
		<div class="bg-light bg-opacity-10 border-top">
			<div class="list-group list-group-flush">
				{#each instances as instance (instance.id)}
					<SessionActivityRow item={instance} type="facts" showOrigins={false} />
				{/each}
			</div>
		</div>
	{/if}
</div>

<style>
	.clickable {
		cursor: pointer;
		user-select: none;
	}
	.clickable:hover {
		background-color: #f8f9fa !important;
	}
	.ring-primary {
		border-color: rgba(13, 110, 253, 0.5) !important;
		box-shadow: 0 0 0 0.25rem rgba(13, 110, 253, 0.05) !important;
	}
	.group-header {
		transition: background-color 0.15s ease-in-out;
	}
</style>
