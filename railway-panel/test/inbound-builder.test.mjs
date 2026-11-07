import test from 'node:test';
import assert from 'node:assert/strict';
import { buildVlessWsInbound } from '../inbound-builder.js';

const user = { username: 'alice', configUuid: '123e4567-e89b-42d3-a456-426614174000' };
const candidate = { address: '1.1.1.1', sni: 'cat.up.railway.app', port: 443, evidence: { ok: true } };
const plan = { ok: true, country: 'DE', primary: candidate, fallbacks: [] };

test('builds validated local-only Xray WS inbound and public VLESS link', () => {
  const built = buildVlessWsInbound({ user, plan, publicHost: 'cat.up.railway.app', pathToken: 'abcdefghijklmnopqrstuvwx' });
  assert.equal(built.ok, true);
  assert.equal(built.config.inbounds[0].listen, '127.0.0.1');
  assert.equal(built.config.inbounds[0].settings.clients[0].id, user.configUuid);
  assert.match(built.link, /^vless:\/\/123e4567/);
  assert.match(built.link, /security=tls/);
});

test('refuses an IP not proven with the actual public panel SNI', () => {
  assert.equal(buildVlessWsInbound({ user, plan, publicHost: 'other.up.railway.app' }).error, 'candidate_not_proven_for_public_host');
});

test('refuses malformed user, host, port and path', () => {
  assert.equal(buildVlessWsInbound({ user: {}, plan, publicHost: 'cat.up.railway.app' }).ok, false);
  assert.equal(buildVlessWsInbound({ user, plan, publicHost: 'bad host' }).ok, false);
  assert.equal(buildVlessWsInbound({ user, plan, publicHost: 'cat.up.railway.app', localPort: 80 }).ok, false);
  assert.equal(buildVlessWsInbound({ user, plan, publicHost: 'cat.up.railway.app', pathToken: 'short' }).ok, false);
});
