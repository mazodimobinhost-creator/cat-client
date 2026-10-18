import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../../app/src/main/java/com/cat/client/AppUpdate.kt', import.meta.url), 'utf8');
test('beta app checks the releases list rather than GitHub stable-only latest', () => {
  assert.match(source, /releases\?per_page=20/);
  assert.match(source, /parseReleaseList/);
  assert.doesNotMatch(source, /"https:\/\/api\.github\.com[^"\n]*releases\/latest"/);
});
test('panel repair resolves its Worker from the prerelease list too', () => {
  const asset = source.slice(source.indexOf('suspend fun releaseAsset'), source.indexOf('suspend fun expectedSha256'));
  assert.match(asset, /readText\(RELEASES_URL/);
  assert.match(asset, /resolveReleaseAssetUrl/);
  assert.doesNotMatch(asset, /"https:\/\/github\.com[^"\n]*releases\/latest\/download/);
});

test('drafts are rejected while signed prereleases can be selected', () => {
  const parse = source.slice(source.indexOf('fun parseRelease('), source.indexOf('fun parseChecksum'));
  assert.match(parse, /getBoolean\("draft"\)/);
  assert.doesNotMatch(parse, /getBoolean\("prerelease"\)/);
});
