import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

const root = new URL('../../', import.meta.url);

test('Pages is a separate advanced-mode target generated from canonical source', async () => {
  const run = spawnSync(process.execPath, ['scripts/pages/build.mjs'], { cwd: root, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr || run.stdout);
  const [source, artifact, config] = await Promise.all([
    readFile(new URL('app/src/main/assets/panels/catclient.worker.js', root), 'utf8'),
    readFile(new URL('pages/dist/_worker.js', root), 'utf8'),
    readFile(new URL('pages/wrangler.jsonc', root), 'utf8'),
  ]);
  assert.ok(artifact.endsWith(source));
  assert.match(artifact, /Generated Pages advanced-mode entrypoint/);
  assert.match(config, /"pages_build_output_dir"\s*:\s*"\.\/dist"/);
  assert.match(config, /"CAT_DEPLOY_TARGET"\s*:\s*"pages"/);
});
