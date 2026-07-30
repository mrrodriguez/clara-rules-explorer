<script lang="ts">
	import { tooltip } from '$lib/actions/popover';

	interface Props {
		/** Namespace (group) identifier string. */
		ns: string;
		/** Whether this group is currently expanded. */
		expanded: boolean;
		/** Total number of items in this group. */
		totalCount: number;
		/** Human-readable label for items (e.g. "rules", "queries"). */
		itemLabel: string;
		/** Called when the header is clicked to toggle expand/collapse. */
		ontoggle: () => void;
	}

	let { ns, expanded, totalCount, itemLabel, ontoggle }: Props = $props();
</script>

<button
	class="list-group-item list-group-item-action d-flex flex-column py-2 px-3 border-start-0 border-end-0 bg-light fw-medium small text-start"
	onclick={ontoggle}
>
	<span class="d-flex align-items-center w-100">
		<i class="bi bi-{expanded ? 'chevron-down' : 'chevron-right'} me-1 opacity-50 flex-shrink-0"
		></i>
		<span class="text-truncate" use:tooltip>
			{ns}
		</span>
	</span>
	<span class="badge bg-secondary rounded-pill mt-1">{totalCount} {itemLabel}</span>
</button>
