import { base } from '$app/paths';
import type {
	RuleSummary,
	QuerySummary,
	RuleListItem,
	QueryListItem,
	RulebaseAnalysis,
	FactTypeSummary,
	RulebaseSummary,
	SessionFactTypesResponse,
	SessionFactTypeInstancesResponse,
	SessionFact,
	SessionProductionActivityResponse
} from './types/api';

const API_BASE = '/v1';
const DEMO_BASE = '/demo-data';

function getUrl(urlPath: string): string {
	const isDemo = import.meta.env.VITE_DEMO_MODE === 'true';
	if (isDemo) {
		const relativePath = urlPath.substring(API_BASE.length);
		return `${base}${DEMO_BASE}${relativePath}.json`;
	}
	return urlPath;
}

/**
 * Fetches a summary of the rulebase counts.
 */
export async function fetchRulebaseSummary(
	customFetch: typeof fetch = fetch
): Promise<RulebaseSummary> {
	const response = await customFetch(getUrl(`${API_BASE}/rulebase-summary`));
	if (!response.ok) {
		throw new Error(`Failed to fetch rulebase summary: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the full rulebase analysis external view (dependency graph and
 * summaries).  Not consumed by current pages — they use the streamlined
 * per-resource endpoints instead.
 */
export async function fetchRulebaseAnalysis(
	customFetch: typeof fetch = fetch
): Promise<RulebaseAnalysis> {
	const response = await customFetch(getUrl(`${API_BASE}/rulebase-analysis`));
	if (!response.ok) {
		throw new Error(`Failed to fetch rulebase analysis: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the list of all rules with minimal metadata.
 */
export async function fetchRulesList(customFetch: typeof fetch = fetch): Promise<RuleListItem[]> {
	const response = await customFetch(getUrl(`${API_BASE}/rules`));
	if (!response.ok) {
		throw new Error(`Failed to fetch rules list: ${response.statusText}`);
	}
	const data = await response.json();
	return data.rules;
}

/**
 * Fetches the list of all queries with minimal metadata.
 */
export async function fetchQueriesList(
	customFetch: typeof fetch = fetch
): Promise<QueryListItem[]> {
	const response = await customFetch(getUrl(`${API_BASE}/queries`));
	if (!response.ok) {
		throw new Error(`Failed to fetch queries list: ${response.statusText}`);
	}
	const data = await response.json();
	return data.queries;
}

/**
 * Fetches the list of all fact types with minimal metadata.
 */
export async function fetchFactTypesList(
	customFetch: typeof fetch = fetch
): Promise<FactTypeSummary[]> {
	const response = await customFetch(getUrl(`${API_BASE}/fact-types`));
	if (!response.ok) {
		throw new Error(`Failed to fetch fact types list: ${response.statusText}`);
	}
	const data = await response.json();
	return data['fact-types'];
}

/**
 * Fetches the summary for a specific rule by id.
 */
export async function fetchRule(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<RuleSummary> {
	const response = await customFetch(getUrl(`${API_BASE}/rules/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch rule ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the summary for a specific query by id.
 */
export async function fetchQuery(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<QuerySummary> {
	const response = await customFetch(getUrl(`${API_BASE}/queries/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch query ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the summary for a specific fact type by id.
 */
export async function fetchFactType(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<FactTypeSummary> {
	const response = await customFetch(getUrl(`${API_BASE}/fact-types/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch fact type ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * --- Phase 2: Memory Analysis Endpoints ---
 */

/**
 * Fetches the summary of all fact types currently in the session.
 */
export async function fetchSessionFactTypes(
	customFetch: typeof fetch = fetch
): Promise<SessionFactTypesResponse> {
	const response = await customFetch(getUrl(`${API_BASE}/session/fact-types`));
	if (!response.ok) {
		throw new Error(`Failed to fetch session fact types: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches all instances of a specific fact type in the session.
 */
export async function fetchSessionFactTypeInstances(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<SessionFactTypeInstancesResponse> {
	const response = await customFetch(getUrl(`${API_BASE}/session/fact-types/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch instances for type ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches details for a single fact instance by ID.
 */
export async function fetchSessionFactDetail(
	id: number | string,
	customFetch: typeof fetch = fetch
): Promise<SessionFact> {
	const response = await customFetch(getUrl(`${API_BASE}/session/facts/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch session fact ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches facts and matches for a specific rule.
 */
export async function fetchSessionRuleActivity(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<SessionProductionActivityResponse> {
	const response = await customFetch(getUrl(`${API_BASE}/session/rules/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch session activity for rule ${id}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches current result sets and fact IDs for a specific query.
 */
export async function fetchSessionQueryActivity(
	id: string,
	customFetch: typeof fetch = fetch
): Promise<SessionProductionActivityResponse> {
	const response = await customFetch(getUrl(`${API_BASE}/session/queries/${id}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch session activity for query ${id}: ${response.statusText}`);
	}
	return response.json();
}
