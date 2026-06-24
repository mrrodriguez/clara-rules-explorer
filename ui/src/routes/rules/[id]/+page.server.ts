import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/rules.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
		return (data.rules || []).map((rule: { name: string }) => ({
			id: rule.name.replace('/', '.')
		}));
	}
	return [];
};
