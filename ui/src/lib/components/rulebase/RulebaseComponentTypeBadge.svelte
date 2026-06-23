<script lang="ts">
	import Badge from '$lib/components/ui/Badge.svelte';

	interface Props {
		type: 'rule' | 'query';
	}

	let { type }: Props = $props();

	let variant = $derived(type === 'rule' ? 'primary' : 'success') as 'primary' | 'success';
</script>

<Badge {variant} uppercase={true}>
	{type}
</Badge>
