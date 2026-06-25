import fs from 'fs';
import path from 'path';
import type { EntryGenerator } from './$types';

export const entries: EntryGenerator = () => {
	const dirPath = path.resolve('static/demo-data/session/facts');
	if (fs.existsSync(dirPath)) {
		const files = fs.readdirSync(dirPath);
		return files
			.filter((file) => file.endsWith('.json'))
			.map((file) => ({ id: file.replace('.json', '') }));
	}
	return [];
};
