import { base } from '$app/paths';
import type {
	RuleSummary,
	QuerySummary,
	RulebaseSummary,
	FactTypeSummary,
	SessionFactTypesResponse,
	SessionFactTypeInstancesResponse,
	SessionFact,
	SessionProductionActivityResponse
} from './types/api';

const DEMO_BASE = `${base}/demo-data`;

/**
 * The merged rulebase bundle written by `bin/scrape-demo-data.js`: summary
 * counts plus full detail for every rule, query, and fact type. Collections
 * are arrays in the analysis's own order (rules/queries in rulebase load
 * order, fact types in first-reference then alphabetical order), and each
 * detail entry is a superset of its list entry.
 */
export interface DemoRulebase {
	summary: RulebaseSummary;
	rules: RuleSummary[];
	queries: QuerySummary[];
	'fact-types': FactTypeSummary[];
}

/**
 * The merged session bundle: the fact-type summary nav feed, per-type
 * instance groupings, per-rule/query activity, and every individual fact
 * detail reachable from those groupings.
 */
export interface DemoSession {
	'fact-types': SessionFactTypesResponse;
	'fact-type-details': Record<string, SessionFactTypeInstancesResponse>;
	rules: Record<string, SessionProductionActivityResponse>;
	queries: Record<string, SessionProductionActivityResponse>;
	facts: Record<string, SessionFact>;
}

export function isDemoMode(): boolean {
	return import.meta.env.VITE_DEMO_MODE === 'true';
}

/**
 * The demo is a fully static SPA, so each bundle is fetched at most once per
 * page session and shared across every route load that needs it.
 */
const demoFileCache = new Map<string, Promise<unknown>>();

function loadDemoFile<T>(file: string, customFetch: typeof fetch): Promise<T> {
	let pending = demoFileCache.get(file) as Promise<T> | undefined;
	if (!pending) {
		const url = `${DEMO_BASE}/${file}`;
		pending = customFetch(url).then((response) => {
			if (!response.ok) {
				throw new Error(`Failed to fetch demo data ${url}: ${response.statusText}`);
			}
			return response.json();
		});
		demoFileCache.set(file, pending);
	}
	return pending;
}

export function loadDemoRulebase(customFetch: typeof fetch): Promise<DemoRulebase> {
	return loadDemoFile<DemoRulebase>('rulebase.json', customFetch);
}

export function loadDemoSession(customFetch: typeof fetch): Promise<DemoSession> {
	return loadDemoFile<DemoSession>('session.json', customFetch);
}
