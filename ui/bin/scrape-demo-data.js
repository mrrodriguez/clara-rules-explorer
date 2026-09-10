import fs from 'fs';
import path from 'path';

const API_HOST = 'http://localhost:9001';
const API_BASE = `${API_HOST}/v1`;
const OUTPUT_DIR = path.resolve('static/demo-data');

async function fetchJson(url) {
	const res = await fetch(url);
	if (!res.ok) {
		throw new Error(`Failed to fetch ${url}: ${res.statusText}`);
	}
	return res.json();
}

/**
 * Recursively sort object keys so the serialized JSON is byte-stable across
 * runs. Array order is preserved — the analysis endpoints return collections
 * in a meaningful order (rules/queries in rulebase load order, fact types in
 * first-reference then alphabetical order), and re-sorting or re-keying them
 * would lose that.
 */
function canonicalize(value) {
	if (Array.isArray(value)) {
		return value.map(canonicalize);
	}
	if (value && typeof value === 'object') {
		return Object.fromEntries(
			Object.keys(value)
				.sort()
				.map((key) => [key, canonicalize(value[key])])
		);
	}
	return value;
}

function writeJson(relativePath, data) {
	const fullPath = path.join(OUTPUT_DIR, relativePath);
	fs.mkdirSync(path.dirname(fullPath), { recursive: true });
	fs.writeFileSync(fullPath, `${JSON.stringify(canonicalize(data), null, 2)}\n`, 'utf-8');
	console.log(`Wrote: ${relativePath}`);
}

/** Fetch each `<collection>/<id>` detail endpoint, preserving the given id order. */
async function fetchDetailList(collection, ids) {
	return Promise.all(ids.map((id) => fetchJson(`${API_BASE}/${collection}/${id}`)));
}

/** Fetch each `<collection>/<id>` detail endpoint into an id-keyed map. */
async function fetchDetailMap(collection, ids) {
	const entries = await Promise.all(
		ids.map(async (id) => [id, await fetchJson(`${API_BASE}/${collection}/${id}`)])
	);
	return Object.fromEntries(entries);
}

/**
 * Rulebase side of the demo: summary counts plus full detail for every rule,
 * query, and fact type, kept as arrays in the analysis's own order. The list
 * endpoints are only used to enumerate ids — each detail response is a
 * superset of its list entry, so the merged file is lossless.
 */
async function scrapeRulebase() {
	const summary = await fetchJson(`${API_BASE}/rulebase-summary`);
	const [rulesData, queriesData, factTypesData] = await Promise.all([
		fetchJson(`${API_BASE}/rules`),
		fetchJson(`${API_BASE}/queries`),
		fetchJson(`${API_BASE}/fact-types`)
	]);
	const rulesList = rulesData.rules ?? [];
	const queriesList = queriesData.queries ?? [];
	const factTypesList = factTypesData['fact-types'] ?? [];

	const [rules, queries, factTypes] = await Promise.all([
		fetchDetailList(
			'rules',
			rulesList.map((rule) => rule.id)
		),
		fetchDetailList(
			'queries',
			queriesList.map((query) => query.id)
		),
		fetchDetailList(
			'fact-types',
			factTypesList.map((factType) => factType.id)
		)
	]);

	return { summary, rules, queries, 'fact-types': factTypes };
}

/**
 * Session side of the demo: the fact-type summary nav feed (kept in its
 * endpoint order), per-fact-type instance groupings, per-rule/query activity,
 * and every individual fact detail reachable from those groupings.
 */
async function scrapeSession(ruleIds, queryIds) {
	const factTypeSummary = await fetchJson(`${API_BASE}/session/fact-types`);
	const typeIds = (factTypeSummary.types ?? []).map((type) => type.id);

	const factTypeDetails = await fetchDetailMap('session/fact-types', typeIds);

	const factIds = new Set();
	const collectFactIds = (groups) => {
		for (const group of groups ?? []) {
			for (const fact of group.facts ?? []) {
				if (fact.id !== undefined && fact.id !== null) {
					factIds.add(fact.id);
				}
			}
		}
	};
	for (const detail of Object.values(factTypeDetails)) {
		collectFactIds(detail['inserted-from']);
		collectFactIds(detail['used-by']);
	}

	const [rules, queries, facts] = await Promise.all([
		fetchDetailMap('session/rules', ruleIds),
		fetchDetailMap('session/queries', queryIds),
		fetchDetailMap('session/facts', [...factIds])
	]);

	return {
		'fact-types': factTypeSummary,
		'fact-type-details': factTypeDetails,
		rules,
		queries,
		facts
	};
}

async function scrape() {
	try {
		console.log(`Starting scrape from backend at ${API_HOST}...`);

		// Clean out stale files (and any prior split-file layout) from scrapes.
		if (fs.existsSync(OUTPUT_DIR)) {
			fs.rmSync(OUTPUT_DIR, { recursive: true });
		}
		fs.mkdirSync(OUTPUT_DIR, { recursive: true });

		const rulebase = await scrapeRulebase();
		writeJson('rulebase.json', rulebase);

		const session = await scrapeSession(
			rulebase.rules.map((rule) => rule.id),
			rulebase.queries.map((query) => query.id)
		);
		writeJson('session.json', session);

		console.log('Scrape completed successfully!');
	} catch (e) {
		console.error('Error during scrape:', e);
		process.exit(1);
	}
}

scrape();
