import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url), 'utf8');
test('canonical Worker does not impersonate an unrelated site or server', () => {
  assert.doesNotMatch(source, /Sora's notes|camouflagePage|<center>nginx<\/center>/i);
});
