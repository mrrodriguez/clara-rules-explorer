/**
 * Svelte `use:` action that calls a callback when a click occurs outside
 * the bound element. Useful for closing dropdowns, popovers, and modals.
 *
 * @example
 * ```svelte
 * <div use:clickOutside={() => (open = false)}>
 *   ...
 * </div>
 * ```
 */
export function clickOutside(node: HTMLElement, onOutsideClick: () => void) {
	function handleClick(e: MouseEvent) {
		if (!node.contains(e.target as Node)) {
			onOutsideClick();
		}
	}

	document.addEventListener('click', handleClick, true);

	return {
		destroy() {
			document.removeEventListener('click', handleClick, true);
		}
	};
}
