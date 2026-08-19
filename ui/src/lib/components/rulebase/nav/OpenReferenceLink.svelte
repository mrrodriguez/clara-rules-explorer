<script lang="ts">
	import { resolve } from '$app/paths';
	import type { Pathname } from '$app/types';
	import { getShortName } from '$lib/utils';
	import { tooltip } from '$lib/actions/tooltip';

	interface Props {
		/** Internal (unresolved) route path to the referenced entity's summary. */
		path: string;
		/** Fully-qualified name of the referenced entity (tooltip + aria-label). */
		name: string;
	}

	let { path, name }: Props = $props();
</script>

<a
	href={resolve(path as Pathname)}
	class="btn btn-sm btn-outline-secondary border-0 py-0 px-1 d-flex align-items-center"
	use:tooltip={`Open ${getShortName(name)}`}
	aria-label="Open {name}"
>
	<i class="bi bi-box-arrow-up-right"></i>
</a>
