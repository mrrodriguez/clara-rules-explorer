<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import DependencyList from '$lib/components/rulebase/DependencyList.svelte';

	interface Props {
		upstream?: ProductionReference[];
		downstream?: ProductionReference[];
		fullView?: boolean;
	}

	let { upstream = [], downstream = [], fullView = false }: Props = $props();
</script>

<div class="row g-3 mb-3">
	<div class="col-md-6">
		<DependencyList items={upstream} direction="upstream" {fullView} />
	</div>
	<div class="col-md-6">
		<DependencyList items={downstream} direction="downstream" {fullView} />
	</div>
</div>
