import { probeDeployment } from '../panels/worker-1101-doctor.mjs';

const base = process.env.LIVE_PANEL_URL || 'https://edge-aybca.catclient-ldqlurrj.workers.dev';
const out = { base };

try {
  out.doctor = await probeDeployment(base, '6.69.17', { attempts: 3, timeoutMs: 8000 });
} catch (e) {
  out.doctorError = String((e && e.message) || e);
}

for (const p of [
  '/health',
  '/api/version',
  '/?av=1.10.0-beta132&pv=6.69.17',
  '/panel/?av=1.10.0-beta132&pv=6.69.17',
  '/api/selfcheck?throw=1',
]) {
  try {
    const r = await fetch(base + p, { redirect: 'manual' });
    const txt = (await r.text()).slice(0, 320).replace(/\s+/g, ' ');
    out[p] = {
      status: r.status,
      location: r.headers.get('location'),
      cfRay: r.headers.get('cf-ray'),
      contentType: r.headers.get('content-type'),
      head: txt,
    };
  } catch (e) {
    out[p] = { error: String((e && e.message) || e) };
  }
}

try {
  const lr = await fetch(base + '/api/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin' }),
  });
  const lbody = await lr.text();
  const cookie = (lr.headers.get('set-cookie') || '').split(';')[0];
  out.login = {
    status: lr.status,
    hasCookie: cookie.startsWith('cat_session='),
    body: lbody.slice(0, 160),
  };
  if (cookie) {
    const ur = await fetch(base + '/api/update-check', { headers: { cookie } });
    out.updateCheck = { status: ur.status, body: (await ur.text()).slice(0, 260) };
  }
} catch (e) {
  out.login = { error: String((e && e.message) || e) };
}

const msg = JSON.stringify(out)
  .replace(/%/g, '%25')
  .replace(/\r/g, '%0D')
  .replace(/\n/g, '%0A');
console.log('::notice title=live-edge-probe::' + msg);
