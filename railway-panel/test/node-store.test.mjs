import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { NodeStore } from '../node-store.js';

const make = async () => { let now = 1_000_000; return { store: new NodeStore(await mkdtemp(join(tmpdir(), 'cat-node-')), () => now), setNow: x => { now = x; } }; };

test('node API key is returned once and never listed', async () => {
  const { store } = await make(); const created = await store.create({ name: 'Germany', country: 'de', url: 'https://de.example.com' });
  assert.ok(created.apiKey.length >= 40);
  assert.equal((await store.list())[0].apiKey, undefined);
  assert.equal((await store.heartbeat({ nodeId: created.id, apiKey: 'wrong', healthy: true })), null);
  assert.equal((await store.heartbeat({ nodeId: created.id, apiKey: created.apiKey, healthy: true, latencyMs: 30, version: '0.11.0' })).healthy, true);
});

test('selection uses only fresh healthy node in requested country', async () => {
  const { store, setNow } = await make();
  const de = await store.create({ name: 'DE', country: 'DE', url: 'https://de.example.com' });
  const us = await store.create({ name: 'US', country: 'US', url: 'https://us.example.com' });
  await store.heartbeat({ nodeId: de.id, apiKey: de.apiKey, healthy: true, latencyMs: 80 });
  await store.heartbeat({ nodeId: us.id, apiKey: us.apiKey, healthy: true, latencyMs: 1 });
  assert.equal((await store.select('DE')).id, de.id);
  setNow(1_200_001);
  assert.equal(await store.select('DE'), null);
});

test('rejects non-HTTPS and URL paths', async () => {
  const { store } = await make();
  await assert.rejects(() => store.create({ name: 'x', country: 'DE', url: 'http://example.com' }), /invalid_node/);
  await assert.rejects(() => store.create({ name: 'x', country: 'DE', url: 'https://example.com/path' }), /invalid_node/);
});
