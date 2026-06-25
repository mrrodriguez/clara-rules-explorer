import fs from 'fs';
import path from 'path';
import { toUrlId } from '$lib/utils';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/session/fact-types.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
		return (data.types || []).map((type: { name: string }) => ({
			typeName: toUrlId(type.name)
		}));
	}
	return [];
};
