import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomBytes } from 'node:crypto';
import { BackupStore } from '../backup-store.js';

test('encrypted backup round-trips allowed state files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cat-backup-')); await mkdir(join(dir, 'xray'));
  await writeFile(join(dir, 'state.json'), '{"candidates":[]}');
  await writeFile(join(dir, 'xray/xray.json'), '{"inbounds":[],"outbounds":[]}');
  const store = new BackupStore(dir, randomBytes(32).toString('base64'));
  const backup = await store.export();
  assert.equal(backup.algorithm, 'AES-256-GCM');
  assert.equal(JSON.stringify(backup).includes('candidates'), false);
  await writeFile(join(dir, 'state.json'), '{"changed":true}');
  const restored = await store.restore(backup);
  assert.deepEqual(restored.restored, ['state.json', 'xray/xray.json']);
  assert.equal(await readFile(join(dir, 'state.json'), 'utf8'), '{"candidates":[]}');
});

test('tampering or wrong key is rejected', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cat-backup-')); await writeFile(join(dir, 'state.json'), '{}');
  const good = new BackupStore(dir, randomBytes(32).toString('base64'));
  const envelope = await good.export();
  envelope.ciphertext = envelope.ciphertext.slice(0, -4) + 'AAAA';
  await assert.rejects(() => good.restore(envelope), /invalid_or_tampered_backup/);
  const wrong = new BackupStore(dir, randomBytes(32).toString('base64'));
  const validEnvelope = await good.export();
  await assert.rejects(() => wrong.restore(validEnvelope), /invalid_or_tampered_backup/);
});
