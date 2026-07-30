<script lang="ts">
	import type { DynamicDetectionInfo, ViaChain } from '$lib/types/api';
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
	let fallbackTypes = $derived(detection?.['fact-instance-derived-types'] ?? []);
	let resolution = $derived(detection?.resolution);
	let startExpanded = $derived(resolution !== 'full');
	let hasCallsites = $derived(callsites.length > 0);
	let hasFallback = $derived(!hasCallsites && fallbackTypes.length > 0);

	function buildViaEntries(via: ViaChain) {
		return [
			{ label: 'boundary', sym: via['boundary-var-name-sym'] },
			...via.callstack.map((e, i) => ({
				label: i === via.callstack.length - 1 ? 'constructor' : 'caller',
				sym: e['var-name-sym']
			}))
		];
	}
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

		{#if hasCallsites}
			{#each callsites as site (site['source-str'] + site.filename)}
				{@const code = site['source-str']}
				{@const types = site['resolved-types']}
				{@const status = site.status}
				{@const constructorSym = site['constructor-sym']}
				{@const via = site.via}
				{@const viaEntries = via ? buildViaEntries(via) : []}
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
										<span
											class="d-inline-flex align-items-center border rounded px-2 py-1 bg-white"
										>
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

							{#if constructorSym}
								<span class="d-inline-flex align-items-center border rounded px-2 py-1 bg-white">
									<i class="bi bi-braces me-2 text-success small"></i>
									<QualifiedName fullName={constructorSym} size="sm" />
								</span>
							{/if}
						</div>

						{#if via}
							<details class="mt-2">
								<summary class="text-muted small" style="cursor: pointer">
									<i class="bi bi-diagram-3 me-1"></i>
									Provenance chain
								</summary>
								<div class="mt-2 d-flex flex-wrap align-items-center gap-1">
									{#each viaEntries as entry, idx (entry.sym + idx)}
										{#if idx > 0}
											<span class="text-muted small">→</span>
										{/if}
										<span
											class="d-inline-flex flex-column align-items-start border rounded px-2 py-1 bg-white"
											title="{entry.label}: {entry.sym}"
										>
											<span class="text-muted" style="font-size: 0.6rem; line-height: 1;">
												{entry.label}
											</span>
											<QualifiedName fullName={entry.sym} size="sm" />
										</span>
									{/each}
								</div>
							</details>
						{/if}
					</div>
				</div>
			{/each}
		{:else if hasFallback}
			<div class="card bg-light border mb-2">
				<div class="card-body p-2">
					<div class="d-flex flex-wrap align-items-start gap-2">
						<span class="text-muted small mt-1"
							>→ Runtime-derived type{fallbackTypes.length !== 1 ? 's' : ''}:</span
						>
						{#each fallbackTypes as type (type)}
							<a href={resolve(factPath(type) as Pathname)} class="text-decoration-none">
								<span class="d-inline-flex align-items-center border rounded px-2 py-1 bg-white">
									<i class="bi bi-box me-2 text-info small"></i>
									<QualifiedName fullName={type} size="sm" />
								</span>
							</a>
						{/each}
					</div>
					<div class="text-muted small mt-1 fst-italic">
						<i class="bi bi-info-circle me-1"></i>
						Fact type{fallbackTypes.length !== 1 ? 's are' : ' is'} detected from runtime session instances.
						No static callsite analysis available.
					</div>
				</div>
			</div>
		{/if}
	</div>
{/if}
