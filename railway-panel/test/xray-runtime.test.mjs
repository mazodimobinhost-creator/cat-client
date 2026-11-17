import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmod, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { XrayRuntime } from '../xray-runtime.js';

const fixture = async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cat-xray-'));
  const binary = join(dir, 'xray');
  const script = '#!/bin/sh\ncase "$*" in *"-test"*) exit 0;; esac\nfor last; do :; done\nif grep -q failStart "$last" 2>/dev/null; then exit 9; fi\ntrap "exit 0" TERM\nwhile true; do sleep 1; done\n';
  await writeFile(binary, script);
  await chmod(binary, 0o700);
  const sha = createHash('sha256').update(script).digest('hex');
  return { dir, binary, sha };
};

test('requires exact binary checksum and validates before install', async () => {
  const f = await fixture();
  const bad = new XrayRuntime({ binary: f.binary, expectedSha256: '0'.repeat(64), dataDir: join(f.dir, 'bad') });
  assert.equal((await bad.verifyBinary()).error, 'xray_checksum_mismatch');
  const runtime = new XrayRuntime({ binary: f.binary, expectedSha256: f.sha, dataDir: join(f.dir, 'data') });
  assert.equal((await runtime.installConfig({ inbounds: [], outbounds: [] })).ok, true);
  assert.deepEqual(JSON.parse(await readFile(runtime.configPath, 'utf8')), { inbounds: [], outbounds: [] });
});

test('starts, reports, and stops a validated runtime', async () => {
  const f = await fixture();
  const runtime = new XrayRuntime({ binary: f.binary, expectedSha256: f.sha, dataDir: join(f.dir, 'data') });
  await runtime.installConfig({ inbounds: [], outbounds: [] });
  assert.equal((await runtime.start()).ok, true);
  assert.equal(runtime.status().running, true);
  assert.equal((await runtime.stop()).ok, true);
  assert.equal(runtime.status().running, false);
});

test('failed new config startup restores and runs the previous config', async () => {
  const f = await fixture();
  const runtime = new XrayRuntime({ binary: f.binary, expectedSha256: f.sha, dataDir: join(f.dir, 'data') });
  await runtime.installConfig({ inbounds: [], outbounds: [], name: 'known-good' });
  await runtime.start();
  const result = await runtime.applyConfig({ inbounds: [], outbounds: [], failStart: true });
  assert.equal(result.ok, false);
  assert.equal(result.rolledBack, true);
  assert.equal(result.previousRunning, true);
  assert.equal(JSON.parse(await readFile(runtime.configPath, 'utf8')).name, 'known-good');
  await runtime.stop();
});
