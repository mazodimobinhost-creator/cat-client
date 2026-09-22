/**
 * Local preview of the Cat Panel worker (no Cloudflare needed).
 * Usage: node scripts/panels/preview-server.mjs [port]
 *
 * Serves the exact worker code that ships in the app. WebSocket data-plane
 * paths (/ws, /trojan) are not handled here (Cloudflare-only API); every
 * HTTP endpoint (/ panel, /sub, /sub64, /clash, /health) works.
 */
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;

const PORT = Number(process.argv[2] || 8787);
const env = {
  CF_IPS: '104.16.6.62, 172.67.181.32, 188.114.96.1',
  SNI_LIST: 'cdn.example.ir',
  DNS_UPSTREAM: 'https://cloudflare-dns.com/dns-query',
  // REMOTE: '',        // full-TCP relay URL (wss://...)
  // PANEL_PASSWORD: '',  // set to see the login screen
};

const server = http.createServer(async (req, res) => {
  try {
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) headers.set(k, v);
    headers.set('Host', 'catpanel-preview.workers.dev');
    const url = 'http://catpanel-preview.workers.dev' + req.url;
    const response = await worker.fetch(new Request(url, { method: req.method, headers }), env);
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

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Cat Panel preview on http://0.0.0.0:${PORT} (panel=/ sub=/sub clash=/clash health=/health)`);
});
