/**
 * Converts a Clara fully-qualified name into the dot-separated route
 * identifier used in SvelteKit route params and API path segments.
 *
 * This replaces the Clojure namespace separator ("/") with a dot so the
 * identifier is safe to use as a single path segment (after percent-encoding
 * by callers). The result is NOT percent-encoded — callers are responsible
 * for wrapping with {@link encodeURIComponent} when generating actual URLs.
 *
 * @param fqName The fully-qualified name (e.g., "my.ns/my-rule")
 * @returns A dot-separated route identifier (e.g., "my.ns.my-rule")
 */
export function toRouteId(fqName: string): string {
	return fqName.replace('/', '.');
}

/**
 * Reverses {@link toRouteId} — converts a dot-separated route identifier
 * back to the canonical fully-qualified name.
 *
 * e.g., "my.ns.my-rule" -> "my.ns/my-rule"
 *
 * This receives SvelteKit-decoded route params, so percent-encoded
 * characters (like %3F for "?") have already been decoded by the router.
 *
 * Note: For Java class names (which already use dots), this is a heuristic
 * based on the last dot.
 */
export function fromRouteId(id: string): string {
	// If it was a Clojure rule/query, it had a slash that we converted to a dot.
	// However, Java classes use dots. The convention in the backend is that
	// the LAST dot is the separator for rules/queries.
	const lastDotIndex = id.lastIndexOf('.');
	if (lastDotIndex === -1) return id;

	// Check if it's likely a rule (contains namespace)
	const ns = id.substring(0, lastDotIndex);
	const name = id.substring(lastDotIndex + 1);

	// This is a heuristic. In a real app, we might check if the ID
	// corresponds to a known rule/query vs a fact type.
	// For now, let's keep it simple as the backend handle-get-rule does similar.
	return `${ns}/${name}`;
}

/**
 * Generates the application path for a specific rule's summary or full view.
 */
export function rulePath(fqName: string, full = false): `/rules/${string}` {
	const id = encodeURIComponent(toRouteId(fqName));
	return (full ? `/rules/${id}/full` : `/rules/${id}`) as `/rules/${string}`;
}

/**
 * Generates the application path for a specific query's summary or full view.
 */
export function queryPath(fqName: string, full = false): `/queries/${string}` {
	const id = encodeURIComponent(toRouteId(fqName));
	return (full ? `/queries/${id}/full` : `/queries/${id}`) as `/queries/${string}`;
}

/**
 * Generates the application path for a specific fact type's summary.
 */
export function factPath(name: string): `/fact-types/${string}` {
	const id = encodeURIComponent(toRouteId(name));
	return `/fact-types/${id}` as `/fact-types/${string}`;
}

/**
 * Extracts the short name from a fully-qualified name.
 * e.g., "clara.server.tools.graph.rules.loan-app-rules/collect-app-given-docs" -> "collect-app-given-docs"
 */
export function getShortName(fqName: string): string {
	return fqName.split('/').pop() || fqName;
}

/**
 * Splits a qualified name into its name and namespace/package parts.
 * Handles both Clojure-style (/) and Java-style (.) separators.
 */
export function splitQualifiedName(fqName: string): { name: string; namespace: string } {
	if (fqName.includes('/')) {
		const parts = fqName.split('/');
		const name = parts.pop() || '';
		return { name, namespace: parts.join('/') };
	}

	if (fqName.includes('.')) {
		const parts = fqName.split('.');
		const name = parts.pop() || '';
		return { name, namespace: parts.join('.') };
	}

	return { name: fqName, namespace: '' };
}
