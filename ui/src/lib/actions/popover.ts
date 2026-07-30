/**
 * Svelte `use:` action that shows a CSS tooltip when hovered, but only
 * when the element's text content is truncated (scrollWidth > clientWidth).
 *
 * The tooltip is rendered with `position: fixed` on `<body>` so it is never
 * clipped by ancestor `overflow: hidden` (common with `.text-truncate`).
 *
 * @example
 * ```svelte
 * <span class="text-truncate" use:tooltip>Long text that may be cut off…</span>
 * ```
 */
export function tooltip(node: HTMLElement) {
	let wrapper: HTMLSpanElement | null = null;

	function getOrCreateWrapper(): HTMLSpanElement {
		if (!wrapper) {
			wrapper = document.createElement('span');
			wrapper.className = 'truncation-tooltip';
			wrapper.setAttribute('aria-hidden', 'true');
		}
		return wrapper;
	}

	function show() {
		// Only show if the text is actually truncated
		if (node.scrollWidth <= node.clientWidth) return;

		const w = getOrCreateWrapper();
		w.textContent = node.textContent ?? '';

		const rect = node.getBoundingClientRect();
		w.style.top = `${rect.bottom + 3}px`;
		w.style.left = `${rect.left + rect.width / 2}px`;

		document.body.appendChild(w);
	}

	function hide() {
		if (wrapper) wrapper.remove();
	}

	node.addEventListener('mouseenter', show);
	node.addEventListener('mouseleave', hide);
	node.addEventListener('focus', show);
	node.addEventListener('blur', hide);

	return {
		destroy() {
			node.removeEventListener('mouseenter', show);
			node.removeEventListener('mouseleave', hide);
			node.removeEventListener('focus', show);
			node.removeEventListener('blur', hide);
			hide();
		}
	};
}
