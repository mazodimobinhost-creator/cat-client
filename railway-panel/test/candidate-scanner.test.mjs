import test from 'node:test';
import assert from 'node:assert/strict';
import { isPublicIp } from '../candidate-scanner.js';

test('blocks private, loopback, documentation and carrier NAT IPv4 ranges', () => {
  for (const ip of ['10.0.0.1', '100.64.0.1', '127.0.0.1', '169.254.1.1', '172.16.0.1', '192.168.1.1', '192.0.2.1', '198.51.100.1', '203.0.113.1']) assert.equal(isPublicIp(ip), false, ip);
  assert.equal(isPublicIp('1.1.1.1'), true);
});

test('blocks local, multicast and documentation IPv6 ranges', () => {
  for (const ip of ['::', '::1', 'fc00::1', 'fd00::1', 'fe80::1', 'ff02::1', '2001:db8::1']) assert.equal(isPublicIp(ip), false, ip);
  assert.equal(isPublicIp('2606:4700:4700::1111'), true);
});
