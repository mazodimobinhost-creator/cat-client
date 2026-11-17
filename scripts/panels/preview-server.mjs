/**
 * Local preview of the Cat Panel worker (no Cloudflare needed).
 * Usage: node scripts/panels/preview-server.mjs [port]
 *
 * Serves the exact worker code that ships in the app. WebSocket data-plane
 * paths (/ws, /trojan) are not handled here (Cloudflare-only API); every
 * HTTP endpoint (/ panel, /sub, /sub64, /clash, /health) works.
 */
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;
const redesignPath = path.join(here, '../../docs/ui-redesign/preview.html');

const PORT = Number(process.argv[2] || 8787);
const env = {
  UUID: process.env.PREVIEW_UUID || 'ca7c11e1-7a9e-4a0b-9e3f-0d2b1c3d4e5f',
  CF_IPS: '104.16.6.62, 172.67.181.32, 188.114.96.1',
  SNI_LIST: 'cdn.example.ir',
  DNS_UPSTREAM: 'https://178.22.122.100/dns-query',
  OPEN_PANEL: process.env.PREVIEW_LOCKED ? '' : 'true',
  CAT_KV: (() => { const m = new Map(); return { get: async (k) => m.get(k) ?? null, put: async (k, v) => { m.set(k, v); }, delete: async (k) => { m.delete(k); }, list: async () => ({ keys: [...m.keys()].map((name) => ({ name })) }) }; })(),
};

const server = http.createServer(async (req, res) => {
  try {
    if (req.url && req.url.startsWith('/redesign')) {
      const html = readFileSync(redesignPath, 'utf8');
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      res.end(html);
      return;
    }
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) if (v !== undefined) headers.set(k, Array.isArray(v) ? v.join(', ') : String(v));
    headers.set('Host', 'catpanel-preview.workers.dev');
    if (req.method === 'POST' || req.method === 'PUT' || req.method === 'DELETE') {
      headers.set('Origin', 'http://catpanel-preview.workers.dev');
    }
    const reqPath = (req.url === '/' || (req.url && req.url.startsWith('/?'))) ? '/panel' + (req.url.slice(1)) : (req.url || '/panel');
    const url = 'http://catpanel-preview.workers.dev' + reqPath;
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = chunks.length && req.method !== 'GET' && req.method !== 'HEAD' ? Buffer.concat(chunks) : undefined;
    const response = await worker.fetch(new Request(url, { method: req.method, headers, body }), env, { waitUntil() {} });
    const flat = {};
    for (const [k, v] of response.headers) flat[k] = v;
    delete flat['x-frame-options'];
    delete flat['content-security-policy'];
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

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Cat Panel preview on http://0.0.0.0:${PORT} (panel=/ redesign=/redesign sub=/sub/${env.UUID} clash=/sub/${env.UUID}/clash health=/health)`);
});
