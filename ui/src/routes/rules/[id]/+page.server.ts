import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/rulebase.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as {
			rules?: { id: string }[];
		};
		return (data.rules ?? []).map((rule) => ({ id: rule.id }));
	}
	return [];
};
