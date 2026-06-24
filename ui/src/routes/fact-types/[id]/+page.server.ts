import fs from 'fs';
import path from 'path';
import { toUrlId } from '$lib/utils';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/fact-types.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
		const types = data['fact-types'] || [];
		return types.map((type: { name: string }) => ({ id: toUrlId(type.name) }));
	}
	return [];
};
