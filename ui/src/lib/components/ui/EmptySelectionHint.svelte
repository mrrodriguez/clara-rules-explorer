<script lang="ts">
	interface Props {
		title: string;
		description: string;
		icon?: string;
	}

	let { title, description, icon = 'bi-arrow-left-circle' }: Props = $props();
</script>

<div
	class="h-100 d-flex flex-column align-items-center justify-content-center text-muted text-center p-4"
>
	<i class="bi {icon} display-1 mb-3"></i>
	<h3>{title}</h3>
	<p class="lead">{description}</p>
</div>
