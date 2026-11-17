// Cat GP — Cloudflare Worker for Google Apps Script only (not the Cat panel).
// dash.cloudflare.com → Workers & Pages → Create → paste this whole file → Deploy.
// Copy https://<name>.workers.dev into Cat Client. Same CAT_KEY as Code.gs.
// GET in a browser shows that Google Script and Cloudflare are connected.
var CAT_KEY = __CAT_KEY__;

function json(o, status) {
  return new Response(JSON.stringify(o), {
    status: status || 200,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-cat-gp-cf': '1' },
  });
}

export default {
  async fetch(request) {
    var url = new URL(request.url);
    if (url.pathname !== '/') return new Response('not found', { status: 404, headers: { 'cache-control': 'no-store' } });
    var method = request.method.toUpperCase();
    if (method === 'GET' || method === 'HEAD') {
      var wantHtml = (request.headers.get('accept') || '').toLowerCase().indexOf('text/html') >= 0;
      if (wantHtml) {
        var html = '<!doctype html><meta charset=utf-8><title>Cat GP</title><body style="font:16px sans-serif;padding:24px"><h3>🌐 Cat GP</h3><p>گوگل اسکریپت و کلادفلر به هم متصل شدند.</p><p>Google Script and Cloudflare are connected.</p><p>This Worker is only for your Google Apps Script. It is not the Cat panel.</p>';
        return new Response(method === 'HEAD' ? null : html, {
          status: 200,
          headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-cat-gp-cf': '1' },
        });
      }
      return json({ ok: true, relay: 'cat-gp-cf', linked: true });
    }
    if (method !== 'POST') return new Response('method not allowed', { status: 405 });
    if (request.headers.get('x-cat-gp-hop')) return json({ e: 'relay loop' });
    var q;
    try { q = await request.json(); } catch (err) { return json({ e: 'bad request' }); }
    if (!q || q.k !== CAT_KEY) return json({ e: 'unauthorized' }, 403);
    if (typeof q.u !== 'string' || !/^https?:\/\//i.test(q.u) || q.u.length > 4096) return json({ e: 'bad url' });
    var target;
    try { target = new URL(q.u); } catch (err) { return json({ e: 'bad url' }); }
    var host = target.hostname.toLowerCase();
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.indexOf(':') >= 0 || host.charAt(0) === '[') return json({ e: 'bare IP' });
    if (host === url.hostname.toLowerCase()) return json({ e: 'refusing to fetch this worker itself' });
    var m = String(q.m || 'GET').toUpperCase();
    if (['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD'].indexOf(m) < 0) return json({ e: 'method' });
    var h = new Headers();
    var skip = /^(host|connection|content-length|transfer-encoding|keep-alive|te|trailer|upgrade|accept-encoding|proxy-.*|cf-.*|x-forwarded-.*|x-real-ip|forwarded|via)$/i;
    var headersIn = q.h || {};
    Object.keys(headersIn).forEach(function (k) {
      if (!skip.test(k)) { try { h.set(k, String(headersIn[k])); } catch (err) { } }
    });
    h.set('x-cat-gp-hop', '1');
    var body;
    if (q.b && m !== 'GET' && m !== 'HEAD') {
      try { body = Uint8Array.from(atob(String(q.b)), function (c) { return c.charCodeAt(0); }); } catch (err) { return json({ e: 'bad body' }); }
      if (body.length > 8 * 1024 * 1024) return json({ e: 'too large' }, 413);
      if (q.ct) h.set('content-type', String(q.ct));
    }
    var r;
    try {
      r = await fetch(target.toString(), { method: m, headers: h, body: body, redirect: q.r === true ? 'follow' : 'manual' });
    } catch (err) {
      return json({ e: 'fetch: ' + String((err && err.message) || err).slice(0, 160) });
    }
    var bytes = new Uint8Array(await r.arrayBuffer());
    if (bytes.length > 25 * 1024 * 1024) return json({ e: 'too large' });
    var bin = '';
    var i;
    for (i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    var outH = {};
    r.headers.forEach(function (v, k) { if (k !== 'set-cookie') outH[k] = v; });
    var cookies = typeof r.headers.getSetCookie === 'function' ? r.headers.getSetCookie() : [];
    if (cookies.length) outH['set-cookie'] = cookies;
    return json({ s: r.status, h: outH, b: btoa(bin), linked: true });
  },
};
