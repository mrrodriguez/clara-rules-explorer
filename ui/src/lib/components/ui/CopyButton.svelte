<script lang="ts">
	interface Props {
		text: string;
		title?: string;
		size?: 'sm' | 'md';
		class?: string;
	}

	let { text, title = 'Copy to clipboard', size = 'sm', class: className = '' }: Props = $props();

	function copy() {
		navigator.clipboard.writeText(text);
	}
</script>

<button
	class="btn {size === 'sm' ? 'btn-sm' : ''} btn-outline-secondary border-0 py-0 {className}"
	onclick={copy}
	{title}
	type="button"
>
	<i class="bi bi-clipboard {size === 'sm' ? 'fs-7' : ''}"></i>
</button>
