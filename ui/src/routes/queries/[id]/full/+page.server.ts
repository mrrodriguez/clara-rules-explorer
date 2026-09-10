import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/rulebase.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as {
			queries?: { id: string }[];
		};
		return (data.queries ?? []).map((query) => ({ id: query.id }));
	}
	return [];
};
