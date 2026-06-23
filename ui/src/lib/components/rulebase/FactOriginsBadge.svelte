<script lang="ts">
	import type { ProductionReference } from '$lib/types/api';
	import Badge from '$lib/components/ui/Badge.svelte';
	import { splitQualifiedName } from '$lib/utils';

	interface Props {
		origins: ProductionReference[];
	}

	let { origins }: Props = $props();

	const isRoot = $derived(origins.length === 0);

	const tooltip = $derived(
		isRoot
			? ''
			: 'Inserted by: ' +
					origins.map((o) => `${splitQualifiedName(o.name).name} (${o.type})`).join(', ')
	);

	const label = $derived(isRoot ? 'root' : `${origins.length} origins`);
</script>

<span title={tooltip}>
	<Badge variant="ghost" size="sm">
		<i class="bi bi-diagram-2 me-1"></i>
		{label}
	</Badge>
</span>
