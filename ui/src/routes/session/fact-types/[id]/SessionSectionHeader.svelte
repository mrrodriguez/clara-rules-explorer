<script lang="ts">
	interface Props {
		icon: string;
		label: string;
	}

	let { icon, label }: Props = $props();
</script>

<div class="d-flex align-items-center mb-4">
	<div
		class="bg-light rounded-circle p-2 me-2 d-flex align-items-center justify-content-center"
		style="width: 32px; height: 32px;"
	>
		<i class="bi {icon} text-muted"></i>
	</div>
	<h5 class="text-muted text-uppercase fs-7 fw-bold mb-0 text-tracking-wide">
		{label}
	</h5>
</div>
