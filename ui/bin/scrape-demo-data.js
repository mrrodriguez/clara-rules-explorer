import fs from 'fs';
import path from 'path';

const API_HOST = 'http://localhost:9001';
const API_BASE = `${API_HOST}/v1`;
const OUTPUT_DIR = path.resolve('static/demo-data');

function toUrlId(fqName) {
	return fqName.replace('/', '.');
}

async function fetchJson(url) {
	const res = await fetch(url);
	if (!res.ok) {
		throw new Error(`Failed to fetch ${url}: ${res.statusText}`);
	}
	return res.json();
}

function writeJson(relativePath, data) {
	const fullPath = path.join(OUTPUT_DIR, relativePath);
	const dir = path.dirname(fullPath);
	fs.mkdirSync(dir, { recursive: true });
	fs.writeFileSync(fullPath, JSON.stringify(data, null, 2), 'utf-8');
	console.log(`Wrote: ${relativePath}`);
}

async function scrape() {
	try {
		console.log(`Starting scrape from backend at ${API_HOST}...`);

		// 1. Save rulebase summary
		const rulebaseSummary = await fetchJson(`${API_BASE}/rulebase-summary`);
		writeJson('rulebase-summary.json', rulebaseSummary);

		// 2. Save rule list and dynamic details/activity
		const rulesData = await fetchJson(`${API_BASE}/rules`);
		writeJson('rules.json', rulesData);
		const rules = rulesData.rules || [];
		for (const rule of rules) {
			const ruleId = encodeURIComponent(toUrlId(rule.name));
			// Static detail
			const ruleDetail = await fetchJson(`${API_BASE}/rules/${ruleId}`);
			writeJson(`rules/${ruleId}.json`, ruleDetail);
			// Session activity
			const ruleActivity = await fetchJson(`${API_BASE}/session/rules/${ruleId}`);
			writeJson(`session/rules/${ruleId}.json`, ruleActivity);
		}

		// 3. Save query list and dynamic details/activity
		const queriesData = await fetchJson(`${API_BASE}/queries`);
		writeJson('queries.json', queriesData);
		const queries = queriesData.queries || [];
		for (const query of queries) {
			const queryId = encodeURIComponent(toUrlId(query.name));
			// Static detail
			const queryDetail = await fetchJson(`${API_BASE}/queries/${queryId}`);
			writeJson(`queries/${queryId}.json`, queryDetail);
			// Session activity
			const queryActivity = await fetchJson(`${API_BASE}/session/queries/${queryId}`);
			writeJson(`session/queries/${queryId}.json`, queryActivity);
		}

		// 4. Save static fact types
		const factTypesData = await fetchJson(`${API_BASE}/fact-types`);
		writeJson('fact-types.json', factTypesData);
		const factTypes = factTypesData['fact-types'] || [];
		for (const factType of factTypes) {
			const factTypeId = encodeURIComponent(toUrlId(factType.name));
			// Static detail
			const factTypeDetail = await fetchJson(`${API_BASE}/fact-types/${factTypeId}`);
			writeJson(`fact-types/${factTypeId}.json`, factTypeDetail);
		}

		// 5. Save session fact types list
		const sessionFactTypes = await fetchJson(`${API_BASE}/session/fact-types`);
		writeJson('session/fact-types.json', sessionFactTypes);

		// 6. Save session fact type instances and collect individual fact IDs
		const factIds = new Set();
		const sessionTypes = sessionFactTypes.types || [];
		for (const typeInfo of sessionTypes) {
			const typeId = encodeURIComponent(toUrlId(typeInfo.name));
			const instances = await fetchJson(`${API_BASE}/session/fact-types/${typeId}`);
			writeJson(`session/fact-types/${typeId}.json`, instances);

			// Extract individual fact IDs to scrape detail views
			const extractFacts = (groups) => {
				for (const group of (groups || [])) {
					for (const factObj of (group.facts || [])) {
						if (factObj.id !== undefined && factObj.id !== null) {
							factIds.add(factObj.id);
						}
					}
				}
			};
			extractFacts(instances['inserted-from']);
			extractFacts(instances['used-by']);
		}

		// 7. Save detail views for all collected facts
		console.log(`Discovered ${factIds.size} unique facts in session. Scraping detail views...`);
		for (const id of factIds) {
			const factDetail = await fetchJson(`${API_BASE}/session/facts/${id}`);
			writeJson(`session/facts/${id}.json`, factDetail);
		}

		console.log('Scrape completed successfully!');
	} catch (e) {
		console.error('Error during scrape:', e);
		process.exit(1);
	}
}

scrape();
