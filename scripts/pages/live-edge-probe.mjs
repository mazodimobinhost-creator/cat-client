import { readFileSync } from 'node:fs';

const base = process.env.LIVE_PANEL_URL || 'https://edge-n7x8p.catclient-j0vjpz8u.workers.dev';
const out = { base, event: process.env.GITHUB_EVENT_NAME || '?' };

function summarizeHtml(html) {
  const title = (html.match(/<title[^>]*>([^<]+)<\/title>/i) || [])[1] || '';
  const code = (html.match(/cf-error-code[^>]*>([^<]+)</i) || [])[1] || '';
  const desc = (html.match(/<h2[^>]*>([^<]+)<\/h2>/i) || [])[1] || '';
  const what = (html.match(/What happened\?<\/h2>\s*<p[^>]*>([\s\S]{1,220}?)</i) || [])[1] || '';
  return {
    title: title.trim(),
    code: code.trim(),
    desc: desc.trim(),
    what: what.replace(/\s+/g, ' ').trim(),
    len: html.length,
  };
}

for (const p of ['/health', '/api/version', '/?av=1.10.0-beta133&pv=6.69.18', '/panel/?av=1.10.0-beta133&pv=6.69.18', '/api/selfcheck']) {
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
let accountId = (process.env.CF_ACCOUNT || '').trim();
out.hasSecrets = { tokenLen: token.length, accountLen: accountId.length };

if (token) {
  const cfTest = {};
  const cfHdr = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
  try {
    const vr = await fetch('https://api.cloudflare.com/client/v4/user/tokens/verify', { headers: cfHdr });
    const vj = await vr.json();
    cfTest.verifyStatus = vr.status;
    cfTest.verifyResult = vj?.result?.status || vj?.errors || null;
  } catch (e) {
    cfTest.verifyErr = String((e && e.message) || e);
  }
  try {
    const ar = await fetch('https://api.cloudflare.com/client/v4/accounts?per_page=10', { headers: cfHdr });
    const aj = await ar.json();
    cfTest.accountsStatus = ar.status;
    cfTest.accountsCount = (aj?.result || []).length;
    if (aj?.result?.[0]?.id) accountId = aj.result[0].id;
  } catch (e) {
    cfTest.accountsErr = String((e && e.message) || e);
  }

  let subdomain = 'zojfup';
  try {
    const subRes = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/subdomain`, { headers: cfHdr });
    const subJson = await subRes.json();
    cfTest.subStatus = subRes.status;
    if (subJson?.result?.subdomain) subdomain = subJson.result.subdomain;
  } catch (e) {
    cfTest.subErr = String((e && e.message) || e);
  }
  cfTest.subdomain = subdomain;

  const workerName = 'catpanel-test';
  const uploadUrl = `https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/scripts/${workerName}`;
  try {
    const script = readFileSync('dist-panel/catpanel.min.js', 'utf8');
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
    cfTest.uploadStatus = putRes.status;
    cfTest.uploadOk = Boolean(putJson?.success);
    cfTest.uploadErrors = putJson?.errors || [];
  } catch (e) {
    cfTest.uploadErr = String((e && e.message) || e);
  }

  if (cfTest.uploadOk) {
    try {
      const sr = await fetch(`${uploadUrl}/subdomain`, {
        method: 'POST',
        headers: cfHdr,
        body: JSON.stringify({ enabled: true, previews_enabled: false }),
      });
      cfTest.subdomainEnableStatus = sr.status;
    } catch (e) {
      cfTest.subdomainEnableErr = String((e && e.message) || e);
    }

    const testUrl = `https://${workerName}.${subdomain}.workers.dev`;
    cfTest.testUrl = testUrl;
    cfTest.probes = [];
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 3000));
      try {
        const pr = await fetch(`${testUrl}/api/version?t=${Date.now()}`, { redirect: 'manual' });
        const ptxt = await pr.text();
        cfTest.probes.push({
          i,
          status: pr.status,
          body: ptxt.trimStart().startsWith('<') ? summarizeHtml(ptxt) : ptxt.slice(0, 200),
        });
        if (pr.status === 200 && ptxt.trimStart().startsWith('{')) break;
      } catch (e) {
        cfTest.probes.push({ i, error: String((e && e.message) || e) });
      }
    }

    try {
      await fetch(uploadUrl, { method: 'DELETE', headers: { authorization: `Bearer ${token}` } });
      cfTest.cleanedUp = true;
    } catch (e) {
      cfTest.cleanupErr = String((e && e.message) || e);
    }
  }
  out.cfTest = cfTest;
}

const msg = JSON.stringify(out)
  .replace(/%/g, '%25')
  .replace(/\r/g, '%0D')
  .replace(/\n/g, '%0A');
console.log('::notice title=live-edge-probe::' + msg);
