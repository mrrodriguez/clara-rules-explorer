import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const filePath = path.resolve('static/demo-data/session.json');
	if (fs.existsSync(filePath)) {
		const data = JSON.parse(fs.readFileSync(filePath, 'utf-8')) as {
			'fact-types'?: { types?: { id: string }[] };
		};
		return (data['fact-types']?.types ?? []).map((type) => ({ id: type.id }));
	}
	return [];
};
