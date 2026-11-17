import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

const keyFrom = value => {
  try { const key = Buffer.from(String(value || ''), 'base64'); return key.length === 32 ? key : null; } catch { return null; }
};
export class BackupStore {
  constructor(dataDir, secret) { this.dataDir = dataDir; this.key = keyFrom(secret); }
  available() { return Boolean(this.key); }
  async export() {
    if (!this.key) throw new Error('backup_key_not_configured');
    const names = ['state.json', 'auth.json', 'nodes.json', 'xray/xray.json', 'xray/xray.last-good.json'];
    const files = {};
    for (const name of names) {
      const text = await readFile(join(this.dataDir, name), 'utf8').catch(() => null);
      if (text != null) files[name] = text;
    }
    const payload = Buffer.from(JSON.stringify({ format: 1, createdAt: Date.now(), files }));
    const iv = randomBytes(12); const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    const ciphertext = Buffer.concat([cipher.update(payload), cipher.final()]);
    return { format: 1, algorithm: 'AES-256-GCM', iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') };
  }
  async restore(envelope) {
    if (!this.key) throw new Error('backup_key_not_configured');
    if (envelope?.format !== 1 || envelope?.algorithm !== 'AES-256-GCM') throw new Error('invalid_backup');
    try {
      const decipher = createDecipheriv('aes-256-gcm', this.key, Buffer.from(envelope.iv, 'base64'));
      decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
      const plain = Buffer.concat([decipher.update(Buffer.from(envelope.ciphertext, 'base64')), decipher.final()]);
      const data = JSON.parse(plain.toString('utf8'));
      if (data.format !== 1 || !data.files || typeof data.files !== 'object') throw new Error();
      const allowed = new Set(['state.json', 'auth.json', 'nodes.json', 'xray/xray.json', 'xray/xray.last-good.json']);
      for (const [name, text] of Object.entries(data.files)) {
        if (!allowed.has(name) || typeof text !== 'string' || text.length > 2_000_000) throw new Error();
        JSON.parse(text);
      }
      for (const [name, text] of Object.entries(data.files)) {
        const path = join(this.dataDir, name); await mkdir(dirname(path), { recursive: true });
        const temp = `${path}.${process.pid}.restore`; await writeFile(temp, text, { mode: 0o600 }); await rename(temp, path);
      }
      return { ok: true, restored: Object.keys(data.files).sort() };
    } catch { throw new Error('invalid_or_tampered_backup'); }
  }
}
