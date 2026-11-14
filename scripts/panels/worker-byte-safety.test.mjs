import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const files = [
  'app/src/main/assets/panels/catclient.worker.js',
  'app/src/main/assets/panels/catpanel.min.js',
  'dist-panel/catpanel.min.js',
];

test('deployable Worker sources contain no raw NUL bytes', async () => {
  for (const file of files) {
    const bytes = await readFile(new URL(`../../${file}`, import.meta.url));
    assert.equal(bytes.includes(0), false, `${file} contains a raw NUL byte`);
  }
});

test('the relay deduplication delimiter remains an escaped JavaScript literal', async () => {
  const source = await readFile(new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url), 'utf8');
  assert.match(source, /String\(e\.k \|\| ''\) \+ '\\0' \+ String\(e\.d \|\| ''\)/);
});
