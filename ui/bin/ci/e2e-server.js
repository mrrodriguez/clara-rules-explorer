import { createServer } from 'node:http';
import { handler } from '../../build/handler.js';

const PORT = +(process.env.PORT || '4173');
const TARGET = process.env.API_PROXY_TARGET || 'http://localhost:9001';

/**
 * Read the request body into a string (or null for bodiless methods).
 * Node 24+ fetch accepts a string body directly.
 */
function readBody(req) {
	if (req.method === 'GET' || req.method === 'HEAD') return null;
	return new Promise((resolve, reject) => {
		const chunks = [];
		req.on('data', (c) => chunks.push(c));
		req.on('end', () => resolve(Buffer.concat(chunks).toString()));
		req.on('error', reject);
	});
}

const server = createServer(async (req, res) => {
	if (req.url?.startsWith('/v1/')) {
		try {
			const url = TARGET + req.url;
			const headers = { ...req.headers };
			delete headers.host;
			delete headers.connection;

			const body = await readBody(req);
			const upstream = await fetch(url, { method: req.method, headers, body });

			res.writeHead(upstream.status, Object.fromEntries(upstream.headers));
			if (upstream.body) {
				for await (const chunk of upstream.body) res.write(chunk);
			}
			res.end();
		} catch {
			res.writeHead(502);
			res.end('Bad Gateway');
		}
		return;
	}
	handler(req, res);
});

server.listen(PORT, () => {
	console.log(`e2e server listening on port ${PORT} (proxy /v1 → ${TARGET})`);
});
