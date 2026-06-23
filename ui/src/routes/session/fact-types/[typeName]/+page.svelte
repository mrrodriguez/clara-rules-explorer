<script lang="ts">
	import { page } from '$app/state';
	import { fetchSessionFactTypeInstances } from '$lib/api';
	import type { SessionFactTypeDetail, SessionFactGroup } from '$lib/types/api';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import FactGroup from './FactGroup.svelte';
	import SessionSectionHeader from './SessionSectionHeader.svelte';

	let detail = $state<SessionFactTypeDetail | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	$effect(() => {
		const typeName = page.params.typeName;
		let active = true;

		if (!typeName) {
			loading = false;
			return;
		}

		async function load(name: string) {
			loading = true;
			error = null;
			detail = null;
			try {
				const result = await fetchSessionFactTypeInstances(name);
				if (active) {
					detail = result;
				}
			} catch (e) {
				if (active) {
					error = (e as Error).message;
				}
			} finally {
				if (active) {
					loading = false;
				}
			}
		}

		load(typeName);

		return () => {
			active = false;
		};
	});

	interface ColumnDef {
		icon: string;
		label: string;
		groups: SessionFactGroup[];
		emptyMessage?: string;
	}

	const columns = $derived<ColumnDef[]>(
		detail
			? [
					{
						icon: 'bi-box-arrow-in-right',
						label: 'Origins (Inserted From)',
						groups: detail['inserted-from'] || [],
						emptyMessage: undefined
					},
					{
						icon: 'bi-lightning',
						label: 'Impact (Used By)',
						groups: detail['used-by'] || [],
						emptyMessage: 'No active usage detected for these instances.'
					}
				]
			: []
	);
</script>

<div class="p-4">
	<!-- Header Card -->
	<div class="d-flex align-items-center mb-5 p-4 bg-white rounded shadow-sm border">
		<div
			class="bg-primary text-white rounded p-3 me-4 d-flex align-items-center justify-content-center"
			style="width: 60px; height: 60px;"
		>
			<i class="bi bi-layers fs-2"></i>
		</div>
		<div class="flex-grow-1">
			<div class="text-uppercase fs-7 fw-bold text-muted mb-1 text-tracking-wide">
				Fact Type Explorer
			</div>
			<h3 class="mb-0 fw-bold">
				{#if page.params.typeName}
					<QualifiedName fullName={page.params.typeName} size="lg" />
				{/if}
			</h3>
		</div>
		{#if !loading && !error && detail}
			<div class="text-end border-start ps-4 ms-4">
				<div class="display-6 fw-bold text-primary mb-0">{detail.count}</div>
				<div class="fs-7 text-muted text-uppercase fw-bold">Active Instances</div>
			</div>
		{/if}
	</div>

	{#if loading}
		<div class="d-flex flex-column align-items-center justify-content-center p-5 text-muted">
			<div class="spinner-border text-primary mb-3" role="status">
				<span class="visually-hidden">Loading...</span>
			</div>
			<span>Retrieving working memory snapshots...</span>
		</div>
	{:else if error}
		<div class="alert alert-danger shadow-sm border-0 d-flex align-items-center p-4">
			<i class="bi bi-exclamation-triangle-fill fs-3 me-3"></i>
			<div>
				<h5 class="alert-heading fw-bold mb-1">Failed to load instances</h5>
				<p class="mb-0 small">{error}</p>
			</div>
		</div>
	{:else if !detail || detail.count === 0}
		<div class="text-center p-5 text-muted border border-dashed rounded bg-light">
			<i class="bi bi-inbox display-4 d-block mb-3 text-muted opacity-50"></i>
			<h5 class="fw-bold">No instances found</h5>
			<p class="mb-0">Working memory does not currently contain any facts of this type.</p>
		</div>
	{:else}
		<div class="row g-4">
			{#each columns as column (column.label)}
				<div class="col-xl-6">
					<SessionSectionHeader icon={column.icon} label={column.label} />

					{#each column.groups as group (group.name)}
						<FactGroup name={group.name} type={group.type} instances={group.facts} />
					{/each}

					{#if column.groups.length === 0 && column.emptyMessage}
						<div class="text-center p-4 text-muted border border-dashed rounded bg-light">
							<p class="mb-0 fs-7 fst-italic">{column.emptyMessage}</p>
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>
