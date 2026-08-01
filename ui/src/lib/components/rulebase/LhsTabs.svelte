<script lang="ts">
	import type { LhsElement } from '$lib/types/api';
	import LhsList from '$lib/components/rulebase/LhsList.svelte';
	import CodeBlock from '$lib/components/ui/CodeBlock.svelte';

	interface Props {
		lhsForm: string;
		lhs: LhsElement[];
	}

	let { lhsForm, lhs }: Props = $props();

	let activeTab = $state<'expression' | 'conditions'>('expression');
</script>

<h6 class="text-muted text-uppercase smaller fw-bold border-bottom pb-1 mb-2">LHS</h6>

<ul class="nav nav-underline lhs-tabs mb-2" role="tablist">
	<li class="nav-item" role="presentation">
		<button
			class="nav-link {activeTab === 'expression' ? 'active' : ''}"
			role="tab"
			aria-selected={activeTab === 'expression'}
			onclick={() => (activeTab = 'expression')}
		>
			Expression
		</button>
	</li>
	<li class="nav-item" role="presentation">
		<button
			class="nav-link {activeTab === 'conditions' ? 'active' : ''}"
			role="tab"
			aria-selected={activeTab === 'conditions'}
			onclick={() => (activeTab = 'conditions')}
		>
			Conditions
		</button>
	</li>
</ul>

{#if activeTab === 'expression'}
	<CodeBlock code={lhsForm} language="clojure" expanded={true} />
{:else}
	<LhsList {lhs} />
{/if}

<style>
	.lhs-tabs {
		--bs-nav-underline-link-padding-x: 0.75rem;
		--bs-nav-underline-link-padding-y: 0.375rem;
		font-size: 0.8rem;
	}
	.lhs-tabs .nav-link {
		cursor: pointer;
	}
</style>
