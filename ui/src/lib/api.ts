import { toUrlId } from '$lib/utils';
import type {
	RuleSummary,
	QuerySummary,
	RuleListItem,
	QueryListItem,
	Analysis,
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
		return `${DEMO_BASE}${relativePath}.json`;
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
 * Fetches the full analysis and dependency graph.
 * @deprecated Use specific endpoints instead.
 */
export async function fetchAnalysis(customFetch: typeof fetch = fetch): Promise<Analysis> {
	const response = await customFetch(getUrl(`${API_BASE}/analysis`));
	if (!response.ok) {
		throw new Error(`Failed to fetch analysis: ${response.statusText}`);
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
 * Fetches the summary for a specific rule by name.
 */
export async function fetchRule(
	name: string,
	customFetch: typeof fetch = fetch
): Promise<RuleSummary> {
	const response = await customFetch(getUrl(`${API_BASE}/rules/${encodeURIComponent(toUrlId(name))}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch rule ${name}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the summary for a specific query by name.
 */
export async function fetchQuery(
	name: string,
	customFetch: typeof fetch = fetch
): Promise<QuerySummary> {
	const response = await customFetch(getUrl(`${API_BASE}/queries/${encodeURIComponent(toUrlId(name))}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch query ${name}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * Fetches the summary for a specific fact type by name.
 */
export async function fetchFactType(
	name: string,
	customFetch: typeof fetch = fetch
): Promise<FactTypeSummary> {
	const response = await customFetch(getUrl(`${API_BASE}/fact-types/${encodeURIComponent(toUrlId(name))}`));
	if (!response.ok) {
		throw new Error(`Failed to fetch fact type ${name}: ${response.statusText}`);
	}
	return response.json();
}

/**
 * --- Phase 2: Session Snapshot Endpoints ---
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
	typeName: string,
	customFetch: typeof fetch = fetch
): Promise<SessionFactTypeInstancesResponse> {
	const response = await customFetch(
		getUrl(`${API_BASE}/session/fact-types/${encodeURIComponent(toUrlId(typeName))}`)
	);
	if (!response.ok) {
		throw new Error(`Failed to fetch instances for type ${typeName}: ${response.statusText}`);
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
	ruleName: string,
	customFetch: typeof fetch = fetch
): Promise<SessionProductionActivityResponse> {
	const response = await customFetch(
		getUrl(`${API_BASE}/session/rules/${encodeURIComponent(toUrlId(ruleName))}`)
	);
	if (!response.ok) {
		throw new Error(
			`Failed to fetch session activity for rule ${ruleName}: ${response.statusText}`
		);
	}
	return response.json();
}

/**
 * --- Phase 2: Session Snapshot Endpoints ---
 */

/**
 * Fetches current result sets and fact IDs for a specific query.
 */
export async function fetchSessionQueryActivity(
	queryName: string,
	customFetch: typeof fetch = fetch
): Promise<SessionProductionActivityResponse> {
	const response = await customFetch(
		getUrl(`${API_BASE}/session/queries/${encodeURIComponent(toUrlId(queryName))}`)
	);
	if (!response.ok) {
		throw new Error(
			`Failed to fetch session activity for query ${queryName}: ${response.statusText}`
		);
	}
	return response.json();
}
