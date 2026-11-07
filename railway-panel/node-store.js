import { createHash, randomBytes } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
const hash = value => createHash('sha256').update(String(value)).digest('hex');
const hostUrl = value => { try { const u = new URL(String(value)); return u.protocol === 'https:' && !u.username && !u.password && !u.port && u.pathname === '/' && !u.search && !u.hash ? u.origin : ''; } catch { return ''; } };

export class NodeStore {
  constructor(dataDir, clock = () => Date.now()) { this.path = join(dataDir, 'nodes.json'); this.clock = clock; }
  async load() { try { const x = JSON.parse(await readFile(this.path, 'utf8')); return Array.isArray(x.nodes) ? x : { nodes: [] }; } catch { return { nodes: [] }; } }
  async save(state) { await mkdir(dirname(this.path), { recursive: true }); const temp = `${this.path}.${process.pid}.tmp`; await writeFile(temp, JSON.stringify(state, null, 2), { mode: 0o600 }); await rename(temp, this.path); }
  async create(input) {
    const name = String(input?.name || '').trim().slice(0, 64); const country = String(input?.country || '').trim().toUpperCase(); const url = hostUrl(input?.url);
    if (!name || !/^[A-Z]{2}$/.test(country) || !url) throw new Error('invalid_node');
    const state = await this.load(); if (state.nodes.some(x => x.url === url)) throw new Error('node_exists');
    const apiKey = randomBytes(32).toString('base64url');
    const node = { id: randomBytes(16).toString('hex'), name, country, url, apiKeyHash: hash(apiKey), createdAt: this.clock(), lastSeenAt: 0, healthy: false, latencyMs: 0, version: '' };
    state.nodes.push(node); await this.save(state); return { ...this.safe(node), apiKey };
  }
  async heartbeat(input) {
    const state = await this.load(); const node = state.nodes.find(x => x.id === String(input?.nodeId || '') && x.apiKeyHash === hash(input?.apiKey || ''));
    if (!node) return null;
    node.lastSeenAt = this.clock(); node.healthy = input?.healthy === true; node.latencyMs = Math.max(0, Math.min(120_000, Number(input?.latencyMs) || 0)); node.version = String(input?.version || '').slice(0, 32);
    await this.save(state); return this.safe(node);
  }
  async list() { return (await this.load()).nodes.map(x => this.safe(x)); }
  async select(country, maxAgeMs = 120_000) {
    const now = this.clock(); const code = String(country || '').toUpperCase();
    return (await this.list()).filter(x => x.country === code && x.healthy && x.lastSeenAt >= now - maxAgeMs && x.lastSeenAt <= now).sort((a, b) => a.latencyMs - b.latencyMs)[0] || null;
  }
  safe(node) { const { apiKeyHash, ...safe } = node; return safe; }
}
