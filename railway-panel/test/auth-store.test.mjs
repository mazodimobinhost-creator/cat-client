import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { AuthStore } from '../auth-store.js';

const store = async () => new AuthStore(await mkdtemp(join(tmpdir(), 'cat-auth-')), () => 1_000_000);

test('bootstraps once and never returns password hashes', async () => {
  const auth = await store();
  assert.equal(await auth.bootstrap('a-long-random-password'), true);
  assert.equal(await auth.bootstrap('another-long-password'), false);
  const users = await auth.listUsers();
  assert.equal(users[0].username, 'admin');
  assert.equal('passwordHash' in users[0], false);
});

test('login creates an authenticated expiring session', async () => {
  const auth = await store();
  await auth.bootstrap('a-long-random-password');
  assert.equal(await auth.login('admin', 'wrong-password'), null);
  const login = await auth.login('admin', 'a-long-random-password');
  assert.equal(login.user.role, 'admin');
  assert.equal((await auth.authenticate(login.token)).username, 'admin');
});

test('admin can create quota and expiry user without leaking hash', async () => {
  const auth = await store();
  await auth.bootstrap('a-long-random-password');
  const user = await auth.createUser({ username: 'alice', password: 'another-long-password', quotaBytes: 1024, expiresAt: 2_000_000 });
  assert.equal(user.quotaBytes, 1024);
  assert.equal(user.expiresAt, 2_000_000);
  assert.equal('passwordHash' in user, false);
  await assert.rejects(() => auth.createUser({ username: 'alice', password: 'another-long-password' }), /user_exists/);
});
