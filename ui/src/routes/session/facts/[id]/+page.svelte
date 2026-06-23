<script lang="ts">
	import { page } from '$app/state';
	import { fetchSessionFactDetail } from '$lib/api';
	import type { SessionFact } from '$lib/types/api';
	import FactDetail from './FactDetail.svelte';

	let fact = $state<SessionFact | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	$effect(() => {
		async function load() {
			const id = page.params.id;
			if (!id) return;

			loading = true;
			error = null;
			try {
				fact = await fetchSessionFactDetail(id);
			} catch (e) {
				error = (e as Error).message;
			} finally {
				loading = false;
			}
		}
		load();
	});
</script>

<div class="p-4">
	{#if loading}
		<div class="d-flex justify-content-center p-5">
			<div class="spinner-border text-primary" role="status">
				<span class="visually-hidden">Loading...</span>
			</div>
		</div>
	{:else if error}
		<div class="alert alert-danger m-3">
			<i class="bi bi-exclamation-triangle-fill me-2"></i>
			{error}
		</div>
	{:else if fact}
		<FactDetail {fact} />
	{/if}
</div>
