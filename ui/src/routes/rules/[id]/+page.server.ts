import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/rulebase.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as {
			rules?: Record<string, unknown>;
		};
		return Object.keys(data.rules ?? {}).map((id) => ({ id }));
	}
	return [];
};
