import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCandidate } from '../server.js';

test('accepts public IPv4 and IPv6 candidates with a valid SNI', () => {
  assert.deepEqual(normalizeCandidate({ address: '1.1.1.1', sni: 'Example.COM', port: 443 }), { address: '1.1.1.1', ipVersion: 4, sni: 'example.com', port: 443 });
  assert.equal(normalizeCandidate({ address: '2606:4700:4700::1111', sni: 'cloudflare.com' })?.ipVersion, 6);
});

test('rejects loopback, malformed SNI, and invalid ports', () => {
  assert.equal(normalizeCandidate({ address: '127.0.0.1', sni: 'example.com' }), null);
  assert.equal(normalizeCandidate({ address: '::1', sni: 'example.com' }), null);
  assert.equal(normalizeCandidate({ address: '1.1.1.1', sni: 'not a host' }), null);
  assert.equal(normalizeCandidate({ address: '1.1.1.1', sni: 'example.com', port: 70000 }), null);
});
