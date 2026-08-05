/**
 * Generates the application path for a specific rule's summary or full view.
 *
 * `id` is the server-issued route id — passed through verbatim, never
 * encoded or parsed. Ids contain only `[A-Za-z0-9.-]` and always route as
 * plain single path segments.
 */
export function rulePath(id: string, full = false): `/rules/${string}` {
	return (full ? `/rules/${id}/full` : `/rules/${id}`) as `/rules/${string}`;
}

/**
 * Generates the application path for a specific query's summary or full view.
 *
 * `id` is the server-issued route id — passed through verbatim.
 */
export function queryPath(id: string, full = false): `/queries/${string}` {
	return (full ? `/queries/${id}/full` : `/queries/${id}`) as `/queries/${string}`;
}

/**
 * Generates the application path for a specific fact type's summary.
 *
 * `id` is the server-issued route id — passed through verbatim.
 */
export function factPath(id: string): `/fact-types/${string}` {
	return `/fact-types/${id}` as `/fact-types/${string}`;
}

/**
 * Extracts the short display name from a fully-qualified name.
 * e.g., "clara.server.tools.graph.rules.loan-app-rules/collect-app-given-docs" -> "collect-app-given-docs"
 */
export function getShortName(fqName: string): string {
	return fqName.split('/').pop() || fqName;
}

/**
 * Display-only splitter for kind-explicit serialized type/production names.
 *
 * Never used for URL construction — routes use server-issued ids, so this
 * parses names purely for presentation. String types (`"foo"`), tuples
 * (`[:loan/status "verified"]`), and unresolved symbols (`symbol[my.ns/foo]`)
 * have no namespace to split out; other names split on the last `/`
 * (Clojure) then last `.` (classes/keywords).
 */
export function splitDisplayName(name: string): { name: string; namespace: string } {
	if (name.startsWith('"') || name.startsWith('[') || name.startsWith('symbol[')) {
		return { name, namespace: '' };
	}
	const slashIdx = name.lastIndexOf('/');
	if (slashIdx !== -1) {
		return { name: name.slice(slashIdx + 1), namespace: name.slice(0, slashIdx) };
	}
	const dotIdx = name.lastIndexOf('.');
	if (dotIdx !== -1) {
		return { name: name.slice(dotIdx + 1), namespace: name.slice(0, dotIdx) };
	}
	return { name, namespace: '' };
}
