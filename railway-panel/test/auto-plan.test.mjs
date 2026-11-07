import test from 'node:test';
import assert from 'node:assert/strict';
import { buildAutoPlan } from '../auto-plan.js';

const candidate = (address, country, latency, checkedAt, ok = true, ipVersion = 4) => ({ address, country, ipVersion, port: 443, sni: 'example.com', evidence: { ok, tls: ok, httpStatus: ok ? 204 : 0, latencyMs: latency, checkedAt } });

test('country-only mode selects fastest fresh proven candidate and fallbacks', () => {
  const now = 1_000_000;
  const plan = buildAutoPlan([
    candidate('1.1.1.1', 'DE', 80, now - 1000),
    candidate('1.0.0.1', 'DE', 20, now - 2000),
    candidate('8.8.8.8', 'US', 1, now),
    candidate('9.9.9.9', 'DE', 2, now - 900_000),
  ], { country: 'de' }, now);
  assert.equal(plan.ok, true);
  assert.equal(plan.primary.address, '1.0.0.1');
  assert.equal(plan.fallbacks[0].address, '1.1.1.1');
});

test('never uses stale, failed, wrong-country or wrong-IP-version evidence', () => {
  const now = 1_000_000;
  const list = [candidate('1.1.1.1', 'DE', 1, now, false), candidate('2606:4700::1', 'DE', 2, now, true, 6)];
  assert.equal(buildAutoPlan(list, { country: 'DE', ipVersion: 4 }, now).error, 'no_fresh_healthy_candidate');
  assert.equal(buildAutoPlan(list, { country: 'DE', ipVersion: 6 }, now).primary.ipVersion, 6);
});

test('rejects invalid country rather than guessing', () => {
  assert.equal(buildAutoPlan([], { country: 'Germany' }).error, 'invalid_country');
});
