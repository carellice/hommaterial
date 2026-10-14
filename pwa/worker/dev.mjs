// Runs the worker on this computer, for trying the web app without Cloudflare: node dev.mjs [port]
import { createServer } from 'node:http';
import worker from './worker.js';

const port = Number(process.argv[2] || 8787);
const env = { ALLOWED_ORIGINS: process.env.ALLOWED_ORIGINS || '', PROXY_KEY: process.env.PROXY_KEY || '' };

createServer(async (req, res) => {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const request = new Request(`http://localhost:${port}${req.url}`, {
    method: req.method,
    headers: Object.entries(req.headers).filter(([, v]) => typeof v === 'string'),
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
  });
  const response = await worker.fetch(request, env);
  res.writeHead(response.status, Object.fromEntries(response.headers));
  res.end(Buffer.from(await response.arrayBuffer()));
}).listen(port, () => console.log(`proxy on http://localhost:${port}`));
