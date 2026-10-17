import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { chmod, copyFile, mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';

const sha256File = async path => createHash('sha256').update(await readFile(path)).digest('hex');
const wait = child => new Promise(resolveDone => {
  let stderr = '';
  child.stderr?.on('data', chunk => { stderr = (stderr + chunk).slice(-4096); });
  child.once('error', error => resolveDone({ ok: false, code: null, error: error.message }));
  child.once('exit', code => resolveDone({ ok: code === 0, code, error: stderr.trim() }));
});

export class XrayRuntime {
  constructor({ binary, expectedSha256, dataDir }) {
    this.binary = resolve(binary || 'xray');
    this.expectedSha256 = String(expectedSha256 || '').toLowerCase();
    this.dataDir = resolve(dataDir);
    this.configPath = join(this.dataDir, 'xray.json');
    this.backupPath = join(this.dataDir, 'xray.last-good.json');
    this.process = null;
    this.startedAt = 0;
  }

  async verifyBinary() {
    if (!/^[a-f0-9]{64}$/.test(this.expectedSha256)) return { ok: false, error: 'xray_checksum_not_configured' };
    const actual = await sha256File(this.binary).catch(() => '');
    return actual === this.expectedSha256 ? { ok: true, sha256: actual } : { ok: false, error: actual ? 'xray_checksum_mismatch' : 'xray_binary_missing' };
  }

  async validate(configPath = this.configPath) {
    const verified = await this.verifyBinary();
    if (!verified.ok) return verified;
    const parsed = await readFile(configPath, 'utf8').then(JSON.parse).catch(() => null);
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.inbounds) || !Array.isArray(parsed.outbounds)) return { ok: false, error: 'invalid_xray_json' };
    const child = spawn(this.binary, ['run', '-test', '-config', configPath], { stdio: ['ignore', 'ignore', 'pipe'] });
    return wait(child);
  }

  async installConfig(config) {
    await mkdir(this.dataDir, { recursive: true });
    const candidate = join(this.dataDir, `xray.${process.pid}.candidate.json`);
    await writeFile(candidate, JSON.stringify(config, null, 2), { mode: 0o600 });
    const check = await this.validate(candidate);
    if (!check.ok) return check;
    await copyFile(this.configPath, this.backupPath).catch(() => {});
    await rename(candidate, this.configPath);
    return { ok: true };
  }

  async start() {
    if (this.process && this.process.exitCode == null) return { ok: true, alreadyRunning: true };
    const check = await this.validate();
    if (!check.ok) return check;
    const child = spawn(this.binary, ['run', '-config', this.configPath], { stdio: ['ignore', 'ignore', 'pipe'], env: { PATH: process.env.PATH || '' } });
    this.process = child;
    this.startedAt = Date.now();
    child.stderr?.on('data', chunk => console.error('[xray]', String(chunk).replace(/[0-9a-f]{8}-[0-9a-f-]{27}/ig, '[uuid]').slice(0, 1000)));
    child.once('exit', () => { if (this.process === child) this.process = null; });
    await new Promise(resolveWait => setTimeout(resolveWait, 250));
    return child.exitCode == null ? { ok: true, pid: child.pid } : { ok: false, error: 'xray_exited_during_start' };
  }

  async stop() {
    const child = this.process;
    if (!child || child.exitCode != null) return { ok: true, alreadyStopped: true };
    child.kill('SIGTERM');
    await Promise.race([wait(child), new Promise(resolveWait => setTimeout(resolveWait, 5000))]);
    if (child.exitCode == null) child.kill('SIGKILL');
    this.process = null;
    return { ok: true };
  }

  async restart() { await this.stop(); return this.start(); }
  status() { return { running: Boolean(this.process && this.process.exitCode == null), pid: this.process?.pid || null, uptimeSeconds: this.startedAt ? Math.floor((Date.now() - this.startedAt) / 1000) : 0 }; }
}
