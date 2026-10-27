// Cat Client — Cloudflare front for a Cat panel on Vercel (panel 6.69.44+).
// Your phone talks to Cloudflare (a workers.dev name or your own Cloudflare domain, any clean
// Cloudflare IP); this Worker passes every request — the panel, the subscription and the VLESS /
// Trojan WebSocket tunnels — on to your Vercel project. Use it when *.vercel.app is filtered on
// your line but Cloudflare is open. Websites still see Vercel's exit IP, not Cloudflare's.
// It only ever talks to the ONE origin below (never an open proxy). No logs, no storage.
// Setup: Cloudflare → Workers & Pages → Create → paste this file → Deploy. Either keep the
// origin written below or add a variable CAT_VERCEL_ORIGIN = your-project.vercel.app.
const DEFAULT_ORIGIN = '__CAT_ORIGIN__';

function originOf(env) {
  const raw = String((env && env.CAT_VERCEL_ORIGIN) || DEFAULT_ORIGIN).trim().toLowerCase()
    .replace(/^https?:\/\//, '').replace(/[/:].*$/, '');
  return /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(raw) ? raw : '';
}

function forwardHeaders(request, front) {
  const h = new Headers(request.headers);
  // Cloudflare-only headers say nothing useful to Vercel and would leak the visitor's IP twice.
  for (const k of ['host', 'cf-connecting-ip', 'cf-ipcountry', 'cf-ray', 'cf-visitor', 'cf-worker', 'x-real-ip', 'x-forwarded-proto']) h.delete(k);
  h.set('x-cat-front', front);
  return h;
}

async function tunnel(request, target, headers) {
  // Outbound WebSocket: Workers return the upstream socket as resp.webSocket on a 101.
  const up = await fetch(target, { headers });
  const ws = up.webSocket;
  if (up.status !== 101 || !ws) return new Response(up.body, { status: up.status === 101 ? 502 : up.status, headers: up.headers });
  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  ws.accept();
  server.accept();
  const closeBoth = (code, reason) => {
    const c = code >= 1000 && code < 5000 && code !== 1005 && code !== 1006 ? code : 1000;
    try { server.close(c, reason || ''); } catch (e) { /* already closed */ }
    try { ws.close(c, reason || ''); } catch (e) { /* already closed */ }
  };
  // Binary frames can arrive as a Blob; forwarding a Blob as-is would send the text «[object Blob]».
  // Each direction keeps one ordered chain, so a slow Blob read never lets a later frame jump ahead.
  const relay = (from, to, side) => {
    let chain = Promise.resolve();
    from.addEventListener('message', (e) => {
      const d = e.data;
      chain = chain.then(async () => to.send(d && typeof d.arrayBuffer === 'function' ? await d.arrayBuffer() : d))
        .catch(() => closeBoth(1011, side));
    });
  };
  relay(server, ws, 'upstream');
  relay(ws, server, 'client');
  server.addEventListener('close', (e) => closeBoth(e.code, e.reason));
  ws.addEventListener('close', (e) => closeBoth(e.code, e.reason));
  server.addEventListener('error', () => closeBoth(1011, 'client error'));
  ws.addEventListener('error', () => closeBoth(1011, 'upstream error'));
  const out = new Headers();
  const proto = up.headers.get('sec-websocket-protocol') || request.headers.get('sec-websocket-protocol');
  if (proto) out.set('sec-websocket-protocol', proto.split(',')[0].trim());
  return new Response(null, { status: 101, webSocket: client, headers: out });
}

export default {
  async fetch(request, env) {
    const origin = originOf(env);
    const url = new URL(request.url);
    const front = url.hostname;
    if (!origin) return new Response('Cat front: set CAT_VERCEL_ORIGIN to your-project.vercel.app', { status: 500 });
    if (origin === front) return new Response('Cat front: the origin cannot be this Worker itself', { status: 508 });
    if (url.pathname === '/__cat_front') {
      return new Response(JSON.stringify({ ok: true, front: 'cat-cf-front', version: 1, origin }), { headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
    }
    const target = 'https://' + origin + url.pathname + url.search;
    const headers = forwardHeaders(request, front);
    try {
      if ((request.headers.get('upgrade') || '').toLowerCase() === 'websocket') return await tunnel(request, target, headers);
      const method = request.method;
      const resp = await fetch(target, { method, headers, body: method === 'GET' || method === 'HEAD' ? undefined : request.body, redirect: 'manual' });
      const out = new Response(resp.body, resp);
      const loc = out.headers.get('location');
      if (loc && loc.indexOf('https://' + origin) === 0) out.headers.set('location', 'https://' + front + loc.slice(8 + origin.length));
      return out;
    } catch (e) {
      return new Response('Cat front: Vercel did not answer (' + String((e && e.message) || e).slice(0, 120) + ')', { status: 502 });
    }
  },
};
