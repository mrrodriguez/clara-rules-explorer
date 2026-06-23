<script lang="ts">
	import { fetchSessionFactTypes } from '$lib/api';
	import type { SessionFactTypeInfo } from '$lib/types/api';
	import { toUrlId } from '$lib/utils';
	import FilterableNavList from '$lib/components/nav/FilterableNavList.svelte';
	import { page } from '$app/state';

	let factTypes = $state<SessionFactTypeInfo[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);

	$effect(() => {
		async function load() {
			try {
				const response = await fetchSessionFactTypes();
				factTypes = response.types.sort((a, b) => a.name.localeCompare(b.name));
			} catch (e) {
				error = (e as Error).message;
			} finally {
				loading = false;
			}
		}
		load();
	});

	function sessionPath(name: string) {
		return `/session/fact-types/${toUrlId(name)}`;
	}

	function isTypeActive(typeName: string) {
		return page.params.typeName === toUrlId(typeName);
	}
</script>

{#snippet itemRight(type: SessionFactTypeInfo)}
	<span
		class="badge rounded-pill {isTypeActive(type.name)
			? 'bg-white text-primary'
			: 'bg-secondary bg-opacity-10 text-muted'}"
	>
		{type.count}
	</span>
{/snippet}

{#if loading}
	<div
		class="d-flex justify-content-center p-4 border-end"
		style="height: calc(100vh - var(--navbar-height)); width: 100%;"
	>
		<div class="spinner-border spinner-border-sm text-primary" role="status">
			<span class="visually-hidden">Loading...</span>
		</div>
	</div>
{:else if error}
	<div class="p-3 border-end" style="height: calc(100vh - var(--navbar-height));">
		<div class="alert alert-danger small p-2 mb-0">
			{error}
		</div>
	</div>
{:else}
	<FilterableNavList
		items={factTypes}
		hrefPrefix={sessionPath}
		activeColor="#0d6efd"
		searchPlaceholder="Search session facts..."
		{itemRight}
		paramName="typeName"
	/>
{/if}
