import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/queries.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
		return (data.queries || []).map((query: { name: string }) => ({
			id: query.name.replace('/', '.')
		}));
	}
	return [];
};
