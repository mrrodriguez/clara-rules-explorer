<script lang="ts">
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';

	export interface DashboardSummaryCardProps {
		title: string;
		value: string | number;
		description: string;
		icon: string;
		color?: 'primary' | 'success' | 'info' | 'warning' | 'secondary';
		href: string;
		actionLabel: string;
	}

	let {
		title,
		value,
		description,
		icon,
		color = 'primary',
		href,
		actionLabel
	}: DashboardSummaryCardProps = $props();

	const resolvedHref = $derived(resolve(href as Pathname));
</script>

<div class="card h-100 border-{color} shadow-sm">
	<div class="card-body text-center">
		<h5 class="card-title text-{color}">
			<i class="bi {icon} me-2"></i>{title}
		</h5>
		<p class="display-5 my-3">{value}</p>
		<p class="card-text text-muted small">
			{description}
		</p>
	</div>
	<div class="card-footer bg-transparent border-top-0 d-flex justify-content-center pb-3">
		<a href={resolvedHref} class="btn btn-outline-{color} btn-sm action-btn">
			{actionLabel}
		</a>
	</div>
</div>

<style>
	.action-btn {
		min-width: 140px;
	}
</style>
