<script lang="ts">
	import DashboardSummaryCard, {
		type DashboardSummaryCardProps
	} from './DashboardSummaryCard.svelte';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();

	const dashboardCards = $derived<DashboardSummaryCardProps[]>([
		{
			title: 'Rules',
			value: data.summary['rule-count'],
			description: 'Total production nodes identified in the rulebase.',
			icon: 'bi-gear-fill',
			color: 'primary',
			href: '/rules',
			actionLabel: 'View All Rules'
		},
		{
			title: 'Queries',
			value: data.summary['query-count'],
			description: 'Available queries for fact inspection.',
			icon: 'bi-search',
			color: 'success',
			href: '/queries',
			actionLabel: 'View All Queries'
		},
		{
			title: 'Fact Types',
			value: data.summary['fact-type-count'],
			description: 'Total unique fact types used across rules and queries.',
			icon: 'bi-database-fill',
			color: 'info',
			href: '/fact-types',
			actionLabel: 'View All Fact Types'
		},
		{
			title: 'Working Memory',
			value: data.sessionFactTypes['total-count'],
			description: 'Total active facts currently in the session.',
			icon: 'bi-cpu-fill',
			color: 'warning',
			href: '/session',
			actionLabel: 'Explore Session'
		}
	]);
</script>

<svelte:head>
	<title>Clara Rules Explorer</title>
</svelte:head>

<div class="container-fluid">
	<div class="row mb-4">
		<div class="col">
			<h1 class="display-6 fw-bold">Rete Network Dashboard</h1>
			<p class="text-muted">Explore and analyze your Clara Rules session.</p>
		</div>
	</div>

	{#each dashboardCards as card (card.title)}
		<div class="row mb-4 justify-content-center">
			<div class="col-md-4">
				<DashboardSummaryCard {...card} />
			</div>
		</div>
	{/each}
</div>
