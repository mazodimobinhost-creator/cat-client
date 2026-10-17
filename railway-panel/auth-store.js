import { createHash, randomBytes, randomUUID, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';
const scrypt = promisify(scryptCallback);
const tokenHash = token => createHash('sha256').update(String(token)).digest('hex');

const cleanName = value => String(value || '').trim().toLowerCase().match(/^[a-z0-9._-]{3,48}$/)?.[0] || '';
const hashPassword = async (password, salt = randomBytes(16).toString('hex')) => {
  const hash = await scrypt(String(password), salt, 32);
  return `${salt}:${Buffer.from(hash).toString('hex')}`;
};
const verifyPassword = async (password, encoded) => {
  const [salt, hex] = String(encoded || '').split(':');
  if (!/^[a-f0-9]{32}$/.test(salt || '') || !/^[a-f0-9]{64}$/.test(hex || '')) return false;
  const actual = Buffer.from(await scrypt(String(password), salt, 32));
  const expected = Buffer.from(hex, 'hex');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
};

export class AuthStore {
  constructor(dataDir, clock = () => Date.now()) { this.path = join(dataDir, 'auth.json'); this.clock = clock; }
  async load() {
    try {
      const value = JSON.parse(await readFile(this.path, 'utf8'));
      return { users: Array.isArray(value.users) ? value.users : [], sessions: Array.isArray(value.sessions) ? value.sessions : [] };
    } catch { return { users: [], sessions: [] }; }
  }
  async save(state) {
    await mkdir(dirname(this.path), { recursive: true });
    const temp = `${this.path}.${process.pid}.tmp`;
    await writeFile(temp, JSON.stringify(state, null, 2), { mode: 0o600 });
    await rename(temp, this.path);
  }
  async bootstrap(password) {
    const state = await this.load();
    if (state.users.length) return false;
    if (String(password).length < 12) throw new Error('initial_password_too_short');
    state.users.push({ id: randomBytes(16).toString('hex'), username: 'admin', role: 'admin', passwordHash: await hashPassword(password), enabled: true, quotaBytes: 0, expiresAt: 0, createdAt: this.clock() });
    await this.save(state); return true;
  }
  async login(username, password) {
    const state = await this.load();
    const user = state.users.find(x => x.username === cleanName(username) && x.enabled);
    if (!user || !(await verifyPassword(password, user.passwordHash))) return null;
    const now = this.clock();
    state.sessions = state.sessions.filter(x => x.expiresAt > now && x.userId !== user.id);
    const token = randomBytes(32).toString('base64url');
    state.sessions.push({ tokenHash: await hashPassword(token), userId: user.id, expiresAt: now + 24 * 60 * 60_000 });
    await this.save(state);
    return { token, user: this.publicUser(user) };
  }
  async authenticate(token) {
    if (!token) return null;
    const state = await this.load(); const now = this.clock();
    for (const session of state.sessions) {
      if (session.expiresAt > now && await verifyPassword(token, session.tokenHash)) {
        const user = state.users.find(x => x.id === session.userId && x.enabled);
        if (user && (!user.expiresAt || user.expiresAt > now)) return this.publicUser(user);
      }
    }
    return null;
  }
  async createUser(input) {
    const username = cleanName(input?.username); const password = String(input?.password || '');
    if (!username || password.length < 12) throw new Error('invalid_user');
    const state = await this.load();
    if (state.users.some(x => x.username === username)) throw new Error('user_exists');
    const subscriptionToken = randomBytes(24).toString('base64url');
    const user = { id: randomBytes(16).toString('hex'), username, role: 'user', passwordHash: await hashPassword(password), enabled: true, quotaBytes: Math.max(0, Number(input.quotaBytes) || 0), expiresAt: Math.max(0, Number(input.expiresAt) || 0), configUuid: randomUUID(), subscriptionTokenHash: tokenHash(subscriptionToken), links: [], createdAt: this.clock() };
    state.users.push(user); await this.save(state); return { ...this.publicUser(user), subscriptionToken };
  }
  async listUsers() { return (await this.load()).users.map(x => this.publicUser(x)); }
  async setLinks(userId, links) {
    const clean = [...new Set((Array.isArray(links) ? links : []).map(String).map(x => x.trim()).filter(x => /^(?:vless|trojan):\/\/[^\s]{1,4096}$/i.test(x)))].slice(0, 20);
    const state = await this.load(); const user = state.users.find(x => x.id === userId && x.role === 'user');
    if (!user) throw new Error('user_not_found');
    user.links = clean; await this.save(state); return { user: this.publicUser(user), links: clean };
  }
  async subscription(token) {
    const wanted = tokenHash(token); const now = this.clock(); const state = await this.load();
    const user = state.users.find(x => x.subscriptionTokenHash === wanted && x.enabled && (!x.expiresAt || x.expiresAt > now));
    return user ? { username: user.username, links: Array.isArray(user.links) ? user.links : [], quotaBytes: user.quotaBytes, expiresAt: user.expiresAt } : null;
  }
  publicUser(user) {
    const { passwordHash, subscriptionTokenHash, links, ...safe } = user;
    return { ...safe, linkCount: Array.isArray(links) ? links.length : 0 };
  }
}
