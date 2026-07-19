<script lang="ts">
	import type { DynamicDetectionInfo } from '$lib/types/api';
	import { factPath } from '$lib/utils';
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';
	import QualifiedName from '$lib/components/ui/QualifiedName.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';

	interface Props {
		detection?: DynamicDetectionInfo;
		label: string;
	}

	let { detection, label }: Props = $props();

	let callsites = $derived(detection?.callsites ?? []);
	let resolution = $derived(detection?.resolution);
	let startExpanded = $derived(resolution !== 'full');
</script>

{#if detection}
	<div class="mb-3">
		<h6 class="text-muted text-uppercase small fw-bold d-flex align-items-center mb-2">
			<i class="bi bi-lightning-charge me-2 opacity-75"></i>
			Dynamic {label} Callsites
			{#if resolution}
				<span
					class="badge rounded-pill text-bg-{resolution === 'full'
						? 'success'
						: resolution === 'partial'
							? 'warning'
							: 'danger'} ms-2"
				>
					{resolution}
				</span>
			{/if}
		</h6>

		{#each callsites as site (site['source-str'] + site.filename)}
			{@const code = site['source-str']}
			{@const types = site['resolved-types']}
			{@const status = site.status}
			<div class="card bg-light border mb-2">
				<div class="card-body p-2">
					{#if startExpanded}
						<CodeBlock {code} language="clojure" expanded={true} />
					{:else}
						<CodeBlock {code} language="clojure" />
					{/if}

					<div class="d-flex flex-wrap align-items-start gap-2">
						{#if types && types.length > 0}
							<span class="text-muted small mt-1">→</span>
							{#each types as type (type)}
								<a href={resolve(factPath(type) as Pathname)} class="text-decoration-none">
									<span class="d-inline-flex align-items-center border rounded px-2 py-1 bg-white">
										<i class="bi bi-box me-2 text-info small"></i>
										<QualifiedName fullName={type} size="sm" />
									</span>
								</a>
							{/each}
						{:else if status === 'unresolved'}
							<span class="badge text-bg-secondary">
								<i class="bi bi-question-circle me-1"></i>
								unresolved
							</span>
						{/if}
					</div>
				</div>
			</div>
		{/each}
	</div>
{/if}
