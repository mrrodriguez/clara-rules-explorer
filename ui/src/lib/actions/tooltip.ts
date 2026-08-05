/**
 * Svelte `use:` action that shows a styled tooltip with the given text on
 * hover/focus. The tooltip is rendered with `position: fixed` on `<body>`
 * so it is never clipped by ancestor `overflow` (common with scrollable
 * list containers).
 *
 * The tooltip text can be updated reactively via the action's `update`.
 *
 * @example
 * ```svelte
 * <button use:tooltip="Open the summary">↗</button>
 * ```
 */
export function tooltip(node: HTMLElement, text: string) {
	let wrapper: HTMLSpanElement | null = null;
	let currentText = text;

	function getOrCreateWrapper(): HTMLSpanElement {
		if (!wrapper) {
			wrapper = document.createElement('span');
			wrapper.className = 'truncation-tooltip';
			wrapper.setAttribute('aria-hidden', 'true');
		}
		return wrapper;
	}

	function show() {
		if (!currentText) return;
		const w = getOrCreateWrapper();
		w.textContent = currentText;

		// Measure while hidden, then position centered above the node and
		// clamp horizontally so the tooltip stays within the viewport.
		w.style.visibility = 'hidden';
		document.body.appendChild(w);

		const nodeRect = node.getBoundingClientRect();
		const wRect = w.getBoundingClientRect();
		const halfW = wRect.width / 2;
		const minCenter = halfW + 4;
		const maxCenter = window.innerWidth - halfW - 4;
		const centerX = Math.min(Math.max(nodeRect.left + nodeRect.width / 2, minCenter), maxCenter);

		w.style.top = `${nodeRect.top - wRect.height - 6}px`;
		w.style.left = `${centerX}px`;
		w.style.visibility = 'visible';
	}

	function hide() {
		if (wrapper) wrapper.remove();
	}

	node.addEventListener('mouseenter', show);
	node.addEventListener('mouseleave', hide);
	node.addEventListener('focus', show);
	node.addEventListener('blur', hide);

	return {
		update(next: string) {
			currentText = next;
		},
		destroy() {
			node.removeEventListener('mouseenter', show);
			node.removeEventListener('mouseleave', hide);
			node.removeEventListener('focus', show);
			node.removeEventListener('blur', hide);
			hide();
		}
	};
}

/**
 * Svelte `use:` action that shows a CSS tooltip when hovered, but only
 * when the element's text content is truncated (scrollWidth > clientWidth).
 *
 * The tooltip is rendered with `position: fixed` on `<body>` so it is never
 * clipped by ancestor `overflow: hidden` (common with `.text-truncate`).
 *
 * @example
 * ```svelte
 * <span class="text-truncate" use:truncationTooltip>Long text that may be cut off…</span>
 * ```
 */
export function truncationTooltip(node: HTMLElement) {
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
