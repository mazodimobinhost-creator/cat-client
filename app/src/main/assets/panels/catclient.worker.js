export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = request.headers.get('Host') || '';
    const uuid = env.UUID || crypto.randomUUID();
    if (url.pathname === '/sub') {
      const vless = `vless://${uuid}@${host}:443?encryption=none&security=tls&sni=${host}&fp=randomized&type=ws&path=%2F%3Fed%3D2048&host=${host}#Cat-Client-${host}`;
      const trojan = `trojan://${uuid}@${host}:443?security=tls&sni=${host}&type=ws&path=%2Ftrojan%3Fed%3D2048&host=${host}#Cat-Client-Trojan-${host}`;
      return new Response([vless, trojan].join('\n'), {
        headers: { 'content-type': 'text/plain; charset=utf-8', 'access-control-allow-origin': '*', 'subscription-userinfo': 'upload=0; download=0; total=1099511627776' }
      });
    }
    if (url.pathname === '/') return panelHtml(host, uuid);
    if (url.pathname.startsWith('/') && url.pathname.length > 1) {
      const upgrade = request.headers.get('Upgrade');
      if (upgrade === 'websocket') return fetch('https://www.cloudflare.com/cdn-cgi/trace', { headers: { Host: host } });
      return env.REMOTE ? fetch(request) : fetch('https://' + host + url.pathname, request);
    }
    return new Response('Cat Client Panel', { status: 200 });
  }
};
function panelHtml(host, uuid) {
  const sub = `https://${host}/sub`;
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cat Client Panel</title>
<style>
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#000;color:#f5f3ff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{max-width:560px;width:100%;background:#0a0a0a;border:1px solid #27272a;border-radius:20px;padding:32px}
h1{color:#a855f7;margin-top:0;font-size:28px}
code{background:#18181b;padding:12px 14px;border-radius:10px;display:block;word-break:break-all;margin:10px 0;border:1px solid #27272a;font-family:ui-monospace,monospace;font-size:13px}
button{background:#7c3aed;border:0;color:#fff;padding:12px 22px;border-radius:10px;cursor:pointer;font-size:15px;font-weight:600;margin:4px}
button:hover{background:#8b5cf6}
.muted{color:#a1a1aa;margin:8px 0 0}
h3{color:#c4b5fd;margin-top:24px;margin-bottom:8px;font-size:15px}
</style></head><body>
<div class="card">
<h1>🐱 Cat Client Panel</h1>
<p style="color:#d4d4d8">Your private Cloudflare Worker proxy is ready.</p>
<h3>Subscription link</h3>
<code id="sub">${sub}</code>
<button onclick="navigator.clipboard.writeText('${sub}');this.textContent='Copied!'">Copy sub</button>
<button onclick="window.open('catclient://add-sub?url='+encodeURIComponent('${sub}'))">Open in Cat Client</button>
<h3>VLESS config</h3>
<code>vless://${uuid}@${host}:443?security=tls&sni=${host}&type=ws&path=%2F%3Fed%3D2048#Cat-Client</code>
<h3>Trojan config</h3>
<code>trojan://${uuid}@${host}:443?security=tls&sni=${host}&type=ws&path=%2Ftrojan#Cat-Client-Trojan</code>
<p class="muted">UUID: <code style="display:inline;padding:2px 6px">${uuid}</code></p>
<p class="muted">Add this sub inside Cat Client → Subscriptions → + Add.</p>
</div></body></html>`, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}
