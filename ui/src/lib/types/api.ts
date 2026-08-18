/**
 * A linkable fact-type reference: `name` is the kind-explicit serialized
 * type string (display), `id` the deterministic route id (linkage), and
 * `known` distinguishes types linkable in this rulebase (`true`) from
 * hierarchy ghosts that render as plain text.
 */
export interface TypeReference {
	name: string;
	id: string;
	known: boolean;
}

/**
 * A single type pair linking two productions: `producer-type` is what the
 * producing rule inserts (or retracts), `consumer-type` is what the
 * consuming rule's LHS requires. Identical shape and meaning on upstream
 * and downstream entries — direct matches (same type both ends) are
 * included. `via: 'retract'` marks a pair whose producer-type is a retract
 * type of the producer (retraction coupling, distinct from production).
 */
export interface TypeBridgeMatch {
	'producer-type': TypeReference;
	'consumer-type': TypeReference;
	via?: 'retract';
}

/**
 * A reference to another production (rule or query) in the dependency graph.
 * `id` is the deterministic route id for linkage. `match` (when present)
 * lists the type pairs that link the two productions.
 */
export interface ProductionReference {
	name: string;
	id: string;
	ns: string;
	type: 'rule' | 'query';
	match?: TypeBridgeMatch[];
}

/**
 * Represents a condition or constraint in the left-hand side (LHS) of a rule or query.
 * The shape of LHS elements can vary (e.g., standard type constraints, accumulators, etc.).
 * For now, we represent it as a flexible record.
 */
export interface LhsElement {
	type?: TypeReference;
	constraints?: string;
	accumulator?: string[];
	from?: LhsElement;
	'result-binding'?: string;
	'fact-binding'?: string;
	[key: string]: unknown;
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
 * A single entry in a provenance path (`rule-to-boundary-path` /
 * `boundary-to-constructor-path`) — a shortest path through a var-level call
 * graph, not an observed runtime call path.
 */
export interface ViaEntry {
	'var-name-sym': string;
}

/**
 * Provenance chain from a boundary fn to a constructor callsite.
 *
 * `rule-to-boundary-path` is the rule → boundary-holding var path (omitted
 * when the boundary call is in the rule's own RHS);
 * `boundary-to-constructor-path` is the boundary-holding var → constructor
 * path.  Both share `boundary-in-var` as a join point.  `source` marks
 * heuristic provenance (`record-ctor-scan`) entries that carry no path.
 */
export interface ViaChain {
	'boundary-var-name-sym'?: string;
	'boundary-in-var'?: string;
	'rule-to-boundary-path'?: ViaEntry[];
	'boundary-to-constructor-path'?: ViaEntry[];
	source?: string;
}

/**
 * A single dynamic insert!/retract! callsite detected in rule source.
 */
export interface DynamicCallsiteEntry {
	'source-str': string;
	ns: string;
	filename: string;
	status?: string;
	'resolved-types'?: TypeReference[];
	'fact-type'?: TypeReference;
	'constructor-sym'?: string;
	via?: ViaChain;
}

/**
 * Detection info for dynamic insert!/retract! callsites in a rule.
 *
 * `callsites` are statically-resolved call sites with full provenance.
 * `fact-instance-derived-types` are runtime-derived type names (plain
 * strings) when static analysis cannot fully resolve the constructor.
 */
export interface DynamicDetectionInfo {
	resolution: 'full' | 'partial' | 'none';
	callsites?: DynamicCallsiteEntry[];
	'fact-instance-derived-types'?: string[];
}

/**
 * Base properties shared by both Rules and Queries.
 */
export interface BaseRuleOrQuery {
	ns: string;
	name: string;
	id: string;
	doc: string | null;
	'lhs-types': TypeReference[];
	lhs: LhsElement[];
	'lhs-form': string;
	notes: string | null;
	props: Record<string, unknown> | null;
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

/**
 * Represents the detailed summary of a Clara Rule.
 */
export interface RuleSummary extends BaseRuleOrQuery {
	'retract-types': TypeReference[];
	'insert-types': TypeReference[];
	'rhs-form': string;
	'source-rule'?: boolean;
	'sink-rule'?: boolean;
	'unlinked-rule'?: UnlinkedRuleInfo | null;
	'no-output-types'?: boolean | null;
	'dynamic-insert-types-detected'?: DynamicDetectionInfo;
	'dynamic-retract-types-detected'?: DynamicDetectionInfo;
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
	id: string;
	ns: string | null;
	'used-by-rules': ProductionReference[];
	'used-by-queries': ProductionReference[];
	'inserted-by-rules': ProductionReference[];
	'retracted-by-rules': ProductionReference[];
	/**
	 * Hierarchy-ordered ancestor types (descendants before their own
	 * ancestors, ties broken lexicographically). Detail-only — the list
	 * endpoint omits it. `known: true` entries link via their id; ghosts
	 * render as plain text.
	 */
	ancestors?: TypeReference[];
	/**
	 * Hierarchy-ordered descendant types (direct descendants first, then their
	 * descendants, ties broken lexicographically). Detail-only — the list
	 * endpoint omits it. `known: true` entries link via their id; ghosts
	 * render as plain text.
	 */
	descendants?: TypeReference[];
}

/**
 * A node in the dependency graph: upstream and downstream production names.
 */
export interface DepGraphNode {
	upstream?: string[];
	downstream?: string[];
}

/**
 * The full rulebase analysis external view (`GET /v1/rulebase-analysis`).
 * Not consumed by current pages — they use the streamlined per-resource
 * endpoints instead.
 */
export interface RulebaseAnalysis {
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
	'working-memory-available': boolean;
}

/**
 * A lightweight representation of a Rule or Query for list views.
 */
export interface RuleListItem {
	name: string;
	id: string;
	ns: string;
	doc: string | null;
	'lhs-types': TypeReference[];
	'insert-types': TypeReference[];
	'retract-types': TypeReference[];
	'source-rule'?: boolean;
	'sink-rule'?: boolean;
	'unlinked-rule'?: UnlinkedRuleInfo | null;
	'no-output-types'?: boolean | null;
	'dynamic-insert-types-detected'?: DynamicDetectionInfo;
	'dynamic-retract-types-detected'?: DynamicDetectionInfo;
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

export interface QueryListItem {
	name: string;
	id: string;
	ns: string;
	doc: string | null;
	'lhs-types': TypeReference[];
	params: string[];
	upstream?: ProductionReference[];
	downstream?: ProductionReference[];
}

/**
 * --- Phase 2: Memory Analysis Interfaces ---
 */

export interface SessionFactTypeInfo {
	name: string;
	id: string;
	ns: string | null;
	count: number;
}

export interface SessionFactTypesResponse {
	types: SessionFactTypeInfo[];
	'total-count': number;
}

export interface SessionFact {
	id: number;
	type: TypeReference;
	ns?: string | null;
	data: unknown;
	'is-root'?: boolean;
	'inserted-from'?: ProductionReference[];
	'used-by'?: ProductionReference[];
}

export interface SessionFactGroup {
	name: string;
	id: string;
	type: 'rule' | 'query' | 'root';
	facts: SessionFact[];
	ns?: string;
}

export interface SessionFactTypeDetail {
	name: string;
	id: string;
	ns: string | null;
	count: number;
	ids: number[];
	'inserted-from': SessionFactGroup[];
	'used-by': SessionFactGroup[];
}

export type SessionFactTypeInstancesResponse = SessionFactTypeDetail;

export interface FactMatch {
	fact: SessionFact;
	bindings: Record<string, unknown>[];
}

export interface SessionProductionActivityResponse {
	matches?: FactMatch[] | null;
	'inserted-facts'?: SessionFact[];
}
