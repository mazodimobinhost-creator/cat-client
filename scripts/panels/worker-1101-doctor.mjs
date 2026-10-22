#!/usr/bin/env node
/**
 * Cat Worker 1101 Doctor
 *
 * A local, fail-closed diagnostic tool. It does not rename protocol strings,
 * obfuscate code, upload source, or attempt to bypass Cloudflare enforcement.
 */
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const VERSION_RE = /CAT_PANEL_VERSION\s*=\s*['"]([0-9]+(?:\.[0-9]+)+)['"]/;
const SECRET_KEY_RE = /(authorization|cookie|password|passwd|secret|token|uuid|api[-_]?key)/i;
const MAX_SOURCE_BYTES = 2 * 1024 * 1024;

export function redact(value, key = '') {
  if (SECRET_KEY_RE.test(key)) return '[REDACTED]';
  if (Array.isArray(value)) return value.map((v) => redact(v));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redact(v, k)]));
  if (typeof value !== 'string') return value;
  return value
    .replace(/\bBearer\s+[^\s"']+/gi, 'Bearer [REDACTED]')
    .replace(/\b[0-9a-f]{8}-[0-9a-f-]{27,}\b/gi, '[REDACTED_UUID]')
    .replace(/((?:[?&]|\b)(?:p|token|key|password|uuid)=)[^&#\s]+/gi, '$1[REDACTED]');
}

function finding(severity, code, message, evidence = '') {
  return { severity, code, message, evidence: redact(String(evidence)).slice(0, 240) };
}

export function analyzeSource(source, name = 'worker.js') {
  const bytes = Buffer.byteLength(source);
  const findings = [];
  const version = source.match(VERSION_RE)?.[1] || null;
  if (!version) findings.push(finding('error', 'VERSION_MISSING', 'CAT_PANEL_VERSION marker is missing.'));
  if (version === '6.69.12') findings.push(finding('error', 'BLOCKED_1101_VERSION', 'Panel version 6.69.12 caused Error 1101 in production and must not be deployed.', version));
  if (bytes > MAX_SOURCE_BYTES) findings.push(finding('error', 'SOURCE_TOO_LARGE', 'Readable source exceeds the 2 MiB safety ceiling.', `${bytes} bytes`));
  else if (bytes > 1024 * 1024) findings.push(finding('warning', 'SOURCE_LARGE', 'Readable source is over 1 MiB; verify deployed and gzip sizes.', `${bytes} bytes`));

  const rules = [
    ['error', 'DYNAMIC_CODE', /\b(?:eval|Function)\s*\(/g, 'Dynamic code execution makes failures and audits unreliable.'],
    ['warning', 'FULL_BODY_BUFFER', /await\s+[^;\n]{0,100}\.(?:arrayBuffer|text|json)\s*\(/g, 'A response/request body is buffered; enforce a size cap or stream untrusted bodies.'],
    ['warning', 'UNBOUNDED_PROMISE_ALL', /Promise\.all\s*\(\s*[^\n;]{0,120}\.map\s*\(/g, 'Promise.all(map(...)) may exceed Cloudflare connection or memory limits; use bounded concurrency.'],
    ['warning', 'EMPTY_CATCH', /catch\s*\([^)]*\)\s*\{\s*\}/g, 'An empty catch hides the exception that may explain a 1101.'],
    ['warning', 'OPEN_ENDED_WAIT', /new\s+Promise\s*\(\s*\([^)]*\)\s*=>\s*\{(?![^}]{0,400}(?:resolve|reject))/gs, 'A Promise may have no visible resolve/reject path.'],
  ];
  for (const [severity, code, regex, message] of rules) {
    const matches = [...source.matchAll(regex)];
    if (matches.length) findings.push(finding(severity, code, message, `${matches.length} match(es): ${matches[0][0]}`));
  }

  if (/new\s+WebSocketPair\s*\(/.test(source)) {
    if (!/\.accept\s*\(/.test(source)) findings.push(finding('error', 'WEBSOCKET_NOT_ACCEPTED', 'WebSocketPair exists but no accept() call was found.'));
    if (!/(?:\.close\s*\(|safeClose|closeWebSocket)/.test(source)) findings.push(finding('warning', 'WEBSOCKET_CLOSE_MISSING', 'No explicit WebSocket close path was found.'));
  }

  const errors = findings.filter((x) => x.severity === 'error').length;
  return {
    ok: errors === 0,
    kind: 'source-audit',
    name,
    version,
    bytes,
    sha256: createHash('sha256').update(source).digest('hex'),
    findings,
    summary: { errors, warnings: findings.filter((x) => x.severity === 'warning').length },
  };
}

async function fetchWithTimeout(url, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { signal: controller.signal, redirect: 'error', headers: { accept: 'application/json', 'cache-control': 'no-cache' } });
  } finally { clearTimeout(timer); }
}

export async function probeDeployment(baseUrl, expectedVersion, { attempts = 3, timeoutMs = 10000, fetcher = fetchWithTimeout } = {}) {
  const base = String(baseUrl).replace(/\/+$/, '');
  if (!/^https:\/\//i.test(base)) throw new Error('Probe URL must use HTTPS');
  const probes = [];
  for (let i = 0; i < attempts; i++) {
    const nonce = `${Date.now()}-${i}-${Math.random().toString(36).slice(2)}`;
    const url = `${base}/api/version?doctor=${encodeURIComponent(nonce)}`;
    try {
      const response = await fetcher(url, timeoutMs);
      const cache = response.headers?.get?.('cf-cache-status') || '';
      const body = await response.json();
      const passed = response.status >= 200 && response.status < 300 && body?.version === expectedVersion && body?.version !== '6.69.12' && !/HIT/i.test(cache);
      probes.push({ passed, status: response.status, version: body?.version || null, cache: cache || null });
    } catch (error) {
      probes.push({ passed: false, error: redact(error?.name === 'AbortError' ? 'timeout' : String(error?.message || error)) });
    }
  }
  return {
    ok: probes.length === attempts && probes.every((p) => p.passed),
    kind: 'deployment-probe',
    expectedVersion,
    attempts,
    probes,
    recommendation: probes.every((p) => p.passed) ? 'verified' : 'do-not-promote; inspect Workers Logs and roll back if this was a new deployment',
  };
}

async function main() {
  const args = process.argv.slice(2);
  const sourcePath = args.find((x) => !x.startsWith('--')) || 'app/src/main/assets/panels/catclient.worker.js';
  const urlAt = args.indexOf('--url');
  const source = await readFile(sourcePath, 'utf8');
  const audit = analyzeSource(source, sourcePath);
  const output = { generatedAt: new Date().toISOString(), audit };
  if (urlAt >= 0 && args[urlAt + 1]) output.deployment = await probeDeployment(args[urlAt + 1], audit.version);
  output.ok = audit.ok && (!output.deployment || output.deployment.ok);
  process.stdout.write(JSON.stringify(redact(output), null, 2) + '\n');
  process.exitCode = output.ok ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) main().catch((error) => {
  process.stderr.write(JSON.stringify({ ok: false, error: redact(String(error?.message || error)) }) + '\n');
  process.exitCode = 1;
});
