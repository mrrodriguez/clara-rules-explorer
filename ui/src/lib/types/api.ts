/**
 * Represents a condition or constraint in the left-hand side (LHS) of a rule or query.
 * The shape of LHS elements can vary (e.g., standard type constraints, accumulators, etc.).
 * For now, we represent it as a flexible record.
 */
export interface LhsElement {
	type?: string;
	constraints?: string;
	accumulator?: string[];
	from?: LhsElement;
	'result-binding'?: string;
	'fact-binding'?: string;
	[key: string]: unknown;
}

/**
 * A reference to another rule or query.
 */
export interface ProductionReference {
	name: string;
	ns: string;
	type: 'rule' | 'query';
}

/**
 * Represents a rule whose RHS (right-hand side) could not be analyzed
 * for downstream effects (no insert/retract types declared).
 */
export interface UnlinkedRuleInfo {
	downstream: 'unknown';
	reason: string;
}

/**
 * Base properties shared by both Rules and Queries.
 */
export interface BaseRuleOrQuery {
	ns: string;
	name: string;
	doc: string | null;
	'lhs-types': string[];
	lhs: LhsElement[];
	notes: string | null;
	'annotation-sources': string[];
	props: Record<string, unknown> | null;
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

/**
 * Represents the detailed summary of a Clara Rule.
 */
export interface RuleSummary extends BaseRuleOrQuery {
	'retract-types': string[];
	'insert-types': string[];
	'rhs-form': string;
	'source-rule'?: boolean;
	'sink-rule'?: boolean;
	'unlinked-rule'?: UnlinkedRuleInfo | null;
	'no-output-types'?: boolean | null;
}

/**
 * Represents the detailed summary of a Clara Query.
 */
export interface QuerySummary extends BaseRuleOrQuery {
	params: string[];
}

/**
 * Represents the usage summary of a Fact Type.
 */
export interface FactTypeSummary {
	name: string;
	'used-by-rules': string[];
	'used-by-queries': string[];
	'inserted-by-rules': string[];
	'retracted-by-rules': string[];
}

/**
 * Represents the dependency graph and analysis from the backend.
 * @deprecated Use streamlined endpoints instead.
 */
export interface DepGraphNode {
	upstream?: string[];
	downstream?: string[];
}

/**
 * @deprecated Use streamlined endpoints instead.
 */
export interface Analysis {
	rules: Record<string, RuleSummary>;
	queries: Record<string, QuerySummary>;
	'fact-types': Record<string, FactTypeSummary>;
	nodes: Record<string, unknown>;
	'dep-graph': Record<string, DepGraphNode>;
	unresolved: unknown[];
}

/**
 * Represents the lightweight summary of the rulebase counts.
 */
export interface RulebaseSummary {
	'rule-count': number;
	'query-count': number;
	'fact-type-count': number;
}

/**
 * A lightweight representation of a Rule or Query for list views.
 */
export interface RuleListItem {
	name: string;
	ns: string;
	doc: string | null;
	'lhs-types': string[];
	'insert-types': string[];
	'retract-types': string[];
	'source-rule'?: boolean;
	'sink-rule'?: boolean;
	'unlinked-rule'?: UnlinkedRuleInfo | null;
	'no-output-types'?: boolean | null;
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

export interface QueryListItem {
	name: string;
	ns: string;
	doc: string | null;
	'lhs-types': string[];
	params: string[];
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

/**
 * --- Phase 2: Session Snapshot Interfaces ---
 */

export interface SessionFactTypeInfo {
	name: string;
	count: number;
}

export interface SessionFactTypesResponse {
	types: SessionFactTypeInfo[];
	'total-count': number;
}

export interface SessionFact {
	id: number;
	type: string;
	data: unknown;
	'is-root'?: boolean;
	'inserted-from'?: ProductionReference[];
	'used-by'?: ProductionReference[];
}

export interface SessionFactGroup {
	name: string;
	type: 'rule' | 'query' | 'root';
	facts: SessionFact[];
	ns?: string;
}

export interface SessionFactTypeDetail {
	name: string;
	count: number;
	'inserted-from': SessionFactGroup[];
	'used-by': SessionFactGroup[];
}

export type SessionFactTypeInstancesResponse = SessionFactTypeDetail;

export interface SessionProductionActivityResponse {
	matches?: SessionFact[] | null;
	'inserted-facts'?: SessionFact[];
}
