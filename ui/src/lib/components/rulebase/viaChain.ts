import type { ViaChain } from '$lib/types/api';

/**
 * Display label for one provenance-chain hop in the callsite "Provenance
 * chain" view.  `caller` is any non-terminal hop on either path; `rule` heads
 * the rule-side path; `boundary` is the boundary fn (`insert!`/`retract!`);
 * `constructor` terminates the constructor-side path.
 */
export type ViaEntryLabel = 'rule' | 'caller' | 'boundary' | 'constructor';

export interface ViaChainEntry {
	label: ViaEntryLabel;
	sym: string;
}

/**
 * Composes the ordered provenance chain for a callsite's `:via` map:
 *
 * 1. `rule-to-boundary-path` — the rule → boundary-holding var path (first
 *    entry `rule`, the rest `caller`).
 * 2. the boundary fn (`boundary-var-name-sym`) — labelled `boundary`.
 * 3. `boundary-to-constructor-path` — the boundary-holding var → constructor
 *    path, *skipping its first entry when `rule-to-boundary-path` is present*
 *    (that entry is `boundary-in-var`, already shown as the rule-side tail);
 *    otherwise every entry is included.  The last entry is `constructor`, the
 *    rest `caller`.
 */
export function buildViaEntries(via: ViaChain): ViaChainEntry[] {
	const rulePath = via['rule-to-boundary-path'] ?? [];
	const ctorPath = via['boundary-to-constructor-path'] ?? [];
	const boundarySym = via['boundary-var-name-sym'] ?? '';

	const entries: ViaChainEntry[] = [];

	rulePath.forEach((entry, index) => {
		entries.push({
			label: index === 0 ? 'rule' : 'caller',
			sym: entry['var-name-sym']
		});
	});

	if (boundarySym) {
		entries.push({ label: 'boundary', sym: boundarySym });
	}

	// The shared `boundary-in-var` opens the constructor path; when the
	// rule-side path is present it already ended there, so drop the repeat.
	const ctorStart = rulePath.length > 0 ? 1 : 0;
	ctorPath.slice(ctorStart).forEach((entry, index, rest) => {
		entries.push({
			label: index === rest.length - 1 ? 'constructor' : 'caller',
			sym: entry['var-name-sym']
		});
	});

	return entries;
}
