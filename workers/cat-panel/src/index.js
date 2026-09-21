const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Allow-Headers": "content-type,x-admin-token" };
const json = (data, status = 200) => new Response(JSON.stringify(data, null, 2), { status, headers: { ...cors, "content-type": "application/json; charset=utf-8" } });
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function authorized(request, env) { return env.ADMIN_TOKEN && request.headers.get("x-admin-token") === env.ADMIN_TOKEN; }
function settings(env) {
  const ips = (env.PROXY_IPS || "").split(",").map(x => x.trim()).filter(Boolean);
  return { name: "Cat Panel", sni: env.SNI || "", proxyIPs: ips, uuid: env.UUID || "", path: env.WS_PATH || "/ws" };
}
function links(env, host) {
  const s = settings(env); const address = s.proxyIPs[0] || host;
  if (!uuid.test(s.uuid)) return [];
  return s.proxyIPs.concat([address]).filter((v, i, a) => a.indexOf(v) === i).map(ip =>
    `vless://${s.uuid}@${ip}:443?encryption=none&security=tls&sni=${encodeURIComponent(s.sni || host)}&type=ws&host=${encodeURIComponent(host)}&path=${encodeURIComponent(s.path)}#Cat-Panel-${ip}`);
}
const page = `<!doctype html><meta charset=utf-8><title>Cat Panel</title><style>body{font:16px system-ui;max-width:760px;margin:40px auto;padding:0 18px;background:#111;color:#eee}input,button{padding:10px;margin:5px 0;width:100%;box-sizing:border-box}button{background:#8b5cf6;color:white;border:0;border-radius:6px}pre{white-space:pre-wrap;background:#222;padding:12px;border-radius:6px}</style><h1>🐈 Cat Panel</h1><p>Worker configuration and subscription generator</p><input id=t placeholder="Admin token" type=password><button onclick=load()>Load config</button><pre id=o>Not loaded</pre><script>async function load(){let r=await fetch('/api/config',{headers:{'x-admin-token':t.value}});o.textContent=await r.text()}</script>`;
export default { async fetch(request, env) { if (request.method === "OPTIONS") return new Response(null, { headers: cors }); const u = new URL(request.url);
  if (u.pathname === "/") return new Response(page, { headers: { "content-type": "text/html;charset=utf-8" } });
  if (u.pathname === "/health") return json({ ok: true, service: "cat-panel" });
  if (u.pathname === "/api/config") { if (!authorized(request, env)) return json({ error: "unauthorized" }, 401); return json({ ...settings(env), subscription: `${u.origin}/sub` }); }
  if (u.pathname === "/sub") { const out = links(env, u.hostname); if (!out.length) return new Response("UUID is not configured", { status: 503 }); return new Response(btoa(out.join("\n")), { headers: { ...cors, "content-type": "text/plain;charset=utf-8", "cache-control": "no-store" } }); }
  return new Response("Not found", { status: 404, headers: cors });
} }; 
