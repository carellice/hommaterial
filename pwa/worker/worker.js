/**
 * The proxy of the Hommaterial web app. A page on GitHub Pages cannot talk to Alexa or to
 * SwitchBot by itself: their servers do not answer other sites, and a browser does not let a page
 * send the cookies of Amazon. This worker does it in its place, request by request, and keeps
 * nothing: the keys travel in each request, from the browser that holds them.
 *
 * The page posts {url, method, headers, body, key} as text, which spares the browser a preflight;
 * the answer is {status, headers, cookies, body}, with the Set-Cookie lines a page cannot read.
 *
 * Settings, all optional, as variables of the worker:
 *   ALLOWED_ORIGINS  the sites allowed to use it, comma separated, such as https://you.github.io
 *   PROXY_KEY        a word the page must send along, for a worker nobody else can use
 */

// Only Amazon and SwitchBot: the worker must not become a proxy to anywhere.
const ALLOWED_HOSTS = [
  /^(www|api|alexa)\.amazon\.(com|it|de|fr|es|co\.uk)$/,
  /^api\.switch-bot\.com$/,
];

const MAX_BODY = 256 * 1024;

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin') || '';
    const allowed = (env.ALLOWED_ORIGINS || '').split(',').map((it) => it.trim()).filter(Boolean);
    const permitted = allowed.length === 0 || allowed.includes(origin);
    const cors = {
      'Access-Control-Allow-Origin': allowed.length === 0 ? '*' : permitted ? origin : allowed[0],
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400',
      Vary: 'Origin',
    };
    const reply = (status, data) =>
      new Response(JSON.stringify(data), { status, headers: { ...cors, 'Content-Type': 'application/json' } });

    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (request.method === 'GET') return reply(200, { hommaterial: true });
    if (request.method !== 'POST') return reply(405, { error: 'method' });
    if (!permitted) return reply(403, { error: 'origin' });

    const text = await request.text();
    if (text.length > MAX_BODY) return reply(413, { error: 'size' });
    let job;
    try {
      job = JSON.parse(text);
    } catch {
      return reply(400, { error: 'json' });
    }
    if (env.PROXY_KEY && job.key !== env.PROXY_KEY) return reply(401, { error: 'key' });

    let target;
    try {
      target = new URL(job.url);
    } catch {
      return reply(400, { error: 'url' });
    }
    if (target.protocol !== 'https:' || !ALLOWED_HOSTS.some((host) => host.test(target.hostname))) {
      return reply(403, { error: 'host' });
    }

    const method = (job.method || 'GET').toUpperCase();
    const headers = new Headers();
    for (const [name, value] of Object.entries(job.headers || {})) headers.set(name, String(value));
    let response;
    try {
      response = await fetch(target.toString(), {
        method,
        headers,
        body: method === 'GET' || method === 'HEAD' ? undefined : job.body ?? undefined,
        // Amazon answers some calls with a redirect whose cookies the page needs to see.
        redirect: 'manual',
      });
    } catch (e) {
      return reply(502, { error: 'unreachable', message: String(e) });
    }

    const out = {};
    for (const [name, value] of response.headers) if (name.toLowerCase() !== 'set-cookie') out[name] = value;
    const cookies = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : response.headers.getAll?.('Set-Cookie') || [];
    return reply(200, { status: response.status, headers: out, cookies, body: await response.text() });
  },
};
