/**
 * Local preview of the Cat Wizard page (UI only — the real install talks to
 * api.cloudflare.com from the Worker, which needs to run on Cloudflare).
 * Usage: node scripts/panels/wizard-preview.mjs [port]
 * Set WIZARD_FAKE_CF=1 to fake the Cloudflare API so the whole flow can be clicked through.
 */
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { readFileSync } from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.wizard.js'));
const wizard = mod.default;
const T = mod._testing;
const PORT = Number(process.argv[2] || 8788);
const env = { WIZARD_PASSWORD: process.env.WIZARD_PASSWORD || '' };

if (process.env.WIZARD_FAKE_CF) {
  const panelSrc = readFileSync(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'), 'utf8');
  const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json' } });
  const state = { subdomain: 'demo-account', scripts: {} };
  T.__setFetch(async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url;
    const method = (init.method || 'GET').toUpperCase();
    await new Promise((r) => setTimeout(r, 400));
    if (url.includes('githubusercontent') || url.includes('github.com')) return new Response(url.endsWith('wizard.js') ? readFileSync(path.join(here, '../../app/src/main/assets/panels/catclient.wizard.js'), 'utf8') : panelSrc);
    if (url.includes('api.cloudflare.com')) {
      const auth = (init.headers && init.headers.authorization) || '';
      if (!auth.endsWith('demo')) return json({ success: false, errors: [{ message: 'Invalid API Token (use any token ending in "demo" for the fake)' }] }, 401);
      if (url.endsWith('/user/tokens/verify')) return json({ success: true, result: { status: 'active' } });
      if (url.includes('/accounts?')) return json({ success: true, result: [{ id: 'acc', name: 'Demo account' }] });
      if (url.endsWith('/workers/subdomain') && method === 'GET') return json({ success: true, result: { subdomain: state.subdomain } });
      if (url.includes('/storage/kv/namespaces')) return method === 'GET' ? json({ success: true, result: [] }) : json({ success: true, result: { id: 'kv-demo', title: 'x' } });
      if (url.endsWith('/settings')) return json({ success: false, errors: [] }, 404);
      if (url.endsWith('/subdomain') && method === 'POST') return json({ success: true, result: {} });
      if (method === 'PUT') { state.scripts[url.split('/').pop()] = true; return json({ success: true, result: {} }); }
    }
    if (url.endsWith('/health')) return json({ ok: true, version: '5.4.0' });
    return new Response('nf', { status: 404 });
  });
  console.log('fake Cloudflare API enabled — paste any token ending in "demo"');
}

const server = http.createServer(async (req, res) => {
  try {
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) headers.set(k, v);
    headers.set('Host', 'cat-wizard-preview.workers.dev');
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = chunks.length && req.method !== 'GET' && req.method !== 'HEAD' ? Buffer.concat(chunks) : undefined;
    const response = await wizard.fetch(new Request('http://cat-wizard-preview.workers.dev' + req.url, { method: req.method, headers, body }), env, { waitUntil() {} });
    const flat = {};
    for (const [k, v] of response.headers) flat[k] = v;
    res.writeHead(response.status, flat);
    if (response.body) {
      const reader = response.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        res.write(Buffer.from(value));
      }
    }
    res.end();
  } catch (e) {
    res.writeHead(500, { 'content-type': 'text/plain' });
    res.end('preview error: ' + (e && e.message ? e.message : e));
  }
});
server.listen(PORT, '0.0.0.0', () => console.log('Cat Wizard preview on http://0.0.0.0:' + PORT));
