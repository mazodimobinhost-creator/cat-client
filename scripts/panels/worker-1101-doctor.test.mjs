import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeSource, probeDeployment, redact } from './worker-1101-doctor.mjs';

test('source audit identifies version and dangerous runtime shapes', () => {
  const report = analyzeSource("const CAT_PANEL_VERSION='1.2.3'; eval('x'); Promise.all(rows.map(run));");
  assert.equal(report.version, '1.2.3');
  assert.equal(report.ok, false);
  assert.ok(report.findings.some((x) => x.code === 'DYNAMIC_CODE'));
  assert.ok(report.findings.some((x) => x.code === 'UNBOUNDED_PROMISE_ALL'));
  assert.match(report.sha256, /^[0-9a-f]{64}$/);
});

test('source audit catches incomplete WebSocket lifecycle and blocked 6.69.12 version', () => {
  const report = analyzeSource("const CAT_PANEL_VERSION='6.69.12'; const pair=new WebSocketPair();");
  assert.equal(report.ok, false);
  assert.ok(report.findings.some((x) => x.code === 'BLOCKED_1101_VERSION'));
  assert.ok(report.findings.some((x) => x.code === 'WEBSOCKET_NOT_ACCEPTED'));
  assert.ok(report.findings.some((x) => x.code === 'WEBSOCKET_CLOSE_MISSING'));
});

test('redaction removes credentials from nested diagnostics', () => {
  const value = redact({ password: 'admin', note: 'Bearer abc123', url: 'https://x.test/?token=secret', uuid: '11111111-2222-4333-8444-555555555555' });
  assert.equal(value.password, '[REDACTED]');
  assert.equal(value.uuid, '[REDACTED]');
  assert.doesNotMatch(JSON.stringify(value), /abc123|secret|11111111/);
});

test('deployment verification requires three fresh exact-version 2xx responses', async () => {
  let calls = 0;
  const good = async (url) => {
    calls++;
    assert.match(url, /doctor=/);
    return { status: 200, headers: { get: () => 'DYNAMIC' }, json: async () => ({ version: '6.69.7' }) };
  };
  const report = await probeDeployment('https://panel.example', '6.69.7', { fetcher: good });
  assert.equal(report.ok, true);
  assert.equal(calls, 3);
  assert.equal(new Set(report.probes.map((_, i) => i)).size, 3);
});

test('deployment verification fails closed on mismatch, cache, and exception', async () => {
  let call = 0;
  const bad = async () => {
    call++;
    if (call === 1) return { status: 200, headers: { get: () => 'HIT' }, json: async () => ({ version: '6.69.7' }) };
    if (call === 2) return { status: 200, headers: { get: () => null }, json: async () => ({ version: 'old' }) };
    throw new Error('request failed token=do-not-leak');
  };
  const report = await probeDeployment('https://panel.example/', '6.69.7', { fetcher: bad });
  assert.equal(report.ok, false);
  assert.match(report.recommendation, /roll back/);
  assert.doesNotMatch(JSON.stringify(report), /do-not-leak/);
});

test('deployment verification rejects plaintext endpoints', async () => {
  await assert.rejects(() => probeDeployment('http://panel.example', '1.0.0'), /HTTPS/);
});
