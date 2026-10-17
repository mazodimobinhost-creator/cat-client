import { randomBytes } from 'node:crypto';
import { isIP } from 'node:net';

const validHost = value => {
  const text = String(value || '').trim().toLowerCase();
  return text.length <= 253 && text.split('.').length >= 2 && text.split('.').every(x => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(x));
};
const validUuid = value => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(String(value));

export function buildVlessWsInbound({ user, users = [user], plan, publicHost, localPort = 18080, pathToken }) {
  publicHost = String(publicHost || '').trim().toLowerCase();
  const activeUsers = users.filter(x => validUuid(x?.configUuid) && x?.username);
  if (!validHost(publicHost) || !validUuid(user?.configUuid) || !activeUsers.length) return { ok: false, error: 'invalid_public_endpoint_or_user' };
  if (!plan?.ok || !plan.primary || plan.primary.sni !== publicHost || !isIP(plan.primary.address)) return { ok: false, error: 'candidate_not_proven_for_public_host' };
  localPort = Number(localPort);
  if (!Number.isInteger(localPort) || localPort < 1024 || localPort > 65535) return { ok: false, error: 'invalid_local_port' };
  const token = String(pathToken || randomBytes(18).toString('base64url'));
  if (!/^[A-Za-z0-9_-]{20,64}$/.test(token)) return { ok: false, error: 'invalid_path_token' };
  const path = `/cat-tunnel/${token}`;
  const config = {
    log: { loglevel: 'warning' },
    inbounds: [{ listen: '127.0.0.1', port: localPort, protocol: 'vless', settings: { clients: activeUsers.map(x => ({ id: x.configUuid, email: x.username })), decryption: 'none' }, streamSettings: { network: 'ws', wsSettings: { path } }, tag: 'cat-vless-ws' }],
    outbounds: [{ protocol: 'freedom', tag: 'direct' }, { protocol: 'blackhole', tag: 'blocked' }],
  };
  const address = plan.primary.address.includes(':') ? `[${plan.primary.address}]` : plan.primary.address;
  const query = new URLSearchParams({ encryption: 'none', security: 'tls', type: 'ws', host: publicHost, sni: publicHost, path });
  const link = `vless://${user.configUuid}@${address}:443?${query}#${encodeURIComponent(`Cat-${plan.country}-${user.username}`)}`;
  return { ok: true, config, link, path, localPort, primary: plan.primary, fallbacks: plan.fallbacks || [] };
}
