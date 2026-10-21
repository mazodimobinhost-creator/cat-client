import { readFileSync } from 'node:fs';

const base = process.env.LIVE_PANEL_URL || 'https://edge-aybca.catclient-ldqlurrj.workers.dev';
const out = { base };

function summarizeHtml(html) {
  const title = (html.match(/<title[^>]*>([^<]+)<\/title>/i) || [])[1] || '';
  const code = (html.match(/cf-error-code[^>]*>([^<]+)</i) || [])[1] || '';
  const desc = (html.match(/<h2[^>]*>([^<]+)<\/h2>/i) || [])[1] || '';
  const what = (html.match(/What happened\?<\/h2>\s*<p[^>]*>([^<]+)</i) || [])[1] || '';
  return { title: title.trim(), code: code.trim(), desc: desc.trim(), what: what.trim(), len: html.length };
}

for (const p of ['/health', '/api/version', '/?av=1.10.0-beta132&pv=6.69.17']) {
  try {
    const r = await fetch(base + p, { redirect: 'manual' });
    const txt = await r.text();
    out[p] = {
      status: r.status,
      location: r.headers.get('location'),
      cfRay: r.headers.get('cf-ray'),
      summary: txt.trimStart().startsWith('<') ? summarizeHtml(txt) : txt.slice(0, 240),
    };
  } catch (e) {
    out[p] = { error: String((e && e.message) || e) };
  }
}

const token = (process.env.CF_TOKEN || '').trim();
const accountId = (process.env.CF_ACCOUNT || '').trim();
if (token && accountId) {
  try {
    const cfHdr = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
    const subRes = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/subdomain`, { headers: cfHdr });
    const subJson = await subRes.json();
    const subdomain = subJson?.result?.subdomain || '';
    const workerName = 'catpanel-test';
    const script = readFileSync('dist-panel/catpanel.min.js', 'utf8');
    const uploadUrl = `https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/scripts/${workerName}`;
    const bindings = [
      { type: 'plain_text', name: 'UUID', text: '11111111-2222-4333-8444-555555555555' },
      { type: 'plain_text', name: 'OPEN_SUB', text: 'false' },
      { type: 'plain_text', name: 'PANEL_USER', text: 'admin' },
      { type: 'secret_text', name: 'PANEL_PASSWORD', text: 'admin' },
      { type: 'secret_text', name: 'CF_API_TOKEN', text: token },
      { type: 'plain_text', name: 'CF_ACCOUNT_ID', text: accountId },
      { type: 'plain_text', name: 'CF_WORKER_NAME', text: workerName },
    ];
    const metadata = {
      main_module: 'worker.js',
      bindings,
      compatibility_date: '2026-09-01',
      compatibility_flags: [],
    };
    const boundary = '----catclient' + Date.now();
    const body =
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="metadata"\r\n` +
      `Content-Type: application/json\r\n\r\n` +
      `${JSON.stringify(metadata)}\r\n` +
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="worker.js"; filename="worker.js"\r\n` +
      `Content-Type: application/javascript+module\r\n\r\n` +
      `${script}\r\n--${boundary}--\r\n`;
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      body,
    });
    const putJson = await putRes.json();
    await fetch(`${uploadUrl}/subdomain`, {
      method: 'POST',
      headers: cfHdr,
      body: JSON.stringify({ enabled: true, previews_enabled: false }),
    });
    const testUrl = `https://${workerName}.${subdomain}.workers.dev`;
    const probes = [];
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => setTimeout(r, 3000));
      const pr = await fetch(`${testUrl}/api/version?t=${Date.now()}`, { redirect: 'manual' });
      const ptxt = await pr.text();
      probes.push({
        status: pr.status,
        body: ptxt.trimStart().startsWith('<') ? summarizeHtml(ptxt) : ptxt.slice(0, 200),
      });
      if (pr.status === 200 && ptxt.startsWith('{')) break;
    }
    out.cfTest = {
      uploadOk: Boolean(putJson?.success),
      uploadErrors: putJson?.errors || [],
      testUrl,
      probes,
    };
  } catch (e) {
    out.cfTestError = String((e && e.message) || e);
  }
} else {
  out.cfTest = 'no-secrets-in-env';
}

const msg = JSON.stringify(out)
  .replace(/%/g, '%25')
  .replace(/\r/g, '%0D')
  .replace(/\n/g, '%0A');
console.log('::notice title=live-edge-probe::' + msg);
