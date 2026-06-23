<script lang="ts">
	import { factPath } from '$lib/utils';
	import ReferenceListItem from '$lib/components/nav/ReferenceListItem.svelte';

	interface Props {
		type: string;
		active?: boolean;
	}

	let { type, active = false }: Props = $props();
</script>

<ReferenceListItem
	href={factPath(type)}
	title={type}
	fullName={type}
	activeColor="#0dcaf0"
	{active}
/>
