<script lang="ts">
	interface Props {
		expanded: boolean;
		label?: string;
		class?: string;
		onclick: () => void;
	}

	let { expanded, label = 'expression', class: className = '', onclick }: Props = $props();
</script>

<button
	class="btn btn-sm btn-link text-decoration-none p-0 d-flex align-items-center text-secondary {className}"
	{onclick}
	aria-expanded={expanded}
>
	<i class="bi {expanded ? 'bi-chevron-down' : 'bi-chevron-right'} me-2"></i>
	<span class="fs-7 font-monospace text-uppercase fw-bold text-muted text-tracking-wide">
		{expanded ? 'Hide' : 'Show'}
		{label}
	</span>
</button>
