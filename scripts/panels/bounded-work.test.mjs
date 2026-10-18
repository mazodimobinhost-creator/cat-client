import test from 'node:test';
import assert from 'node:assert/strict';

const source = new URL('../../app/src/main/assets/panels/catclient.worker.js?bounded-work', import.meta.url);
const { _testing } = await import(source);

test('mapBounded caps concurrent Worker operations and preserves order', async () => {
  let active = 0, peak = 0;
  const result = await _testing.mapBounded([0, 1, 2, 3, 4, 5, 6, 7], 3, async value => {
    active += 1; peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 4));
    active -= 1;
    return value * 2;
  });
  assert.ok(peak <= 3, `peak concurrency was ${peak}`);
  assert.deepEqual(result, [0, 2, 4, 6, 8, 10, 12, 14]);
});

test('mapBounded isolates one rejection instead of failing the invocation', async () => {
  const result = await _testing.mapBounded(['ok', 'bad', 'last'], 2, async value => {
    if (value === 'bad') throw new Error('probe_failed');
    return value;
  });
  assert.equal(result[0], 'ok');
  assert.deepEqual(result[1], { ok: false, error: 'probe_failed' });
  assert.equal(result[2], 'last');
});

test('mapBounded accepts an empty batch without spawning workers', async () => {
  assert.deepEqual(await _testing.mapBounded([], 3, async value => value), []);
});

test('canonical Worker keeps conservative probe and subscription limits', async () => {
  const sourceText = await (await import('node:fs/promises')).readFile(new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url), 'utf8');
  assert.match(sourceText, /mapBounded\(tests, 3,/);
  assert.match(sourceText, /mapBounded\(list, 2,/);
  assert.match(sourceText, /mapBounded\(targets, 2,/);
  assert.match(sourceText, /slice\(0, 24\)/);
  assert.match(sourceText, /entryLimit: 32/);
  assert.match(sourceText, /Math\.min\(96,/);
  assert.match(sourceText, /value\.length > 8192/);
  assert.match(sourceText, /chunk\.byteLength > 1024 \* 1024/);
  assert.match(sourceText, /withTimeout\(reader\.read\(\), 5000/);
  assert.match(sourceText, /first\.value\.byteLength > 65536/);
  assert.match(sourceText, /readBoundedBody\(request, maxBytes\)/);
  assert.match(sourceText, /DNS_CACHE_MAX = 512/);
  assert.match(sourceText, /readBoundedBody\(request, 65535\)/);
  assert.match(sourceText, /payload\.length > 512/);
  assert.match(sourceText, /readResponseTextBounded\(res, 512 \* 1024\)/);
  assert.match(sourceText, /mapBounded\(repos, 3,/);
  assert.doesNotMatch(sourceText, /access-control-allow-origin[^\n]+\*[^\n]+api\/colo/);
  assert.match(sourceText, /readResponseJsonBounded\(response/);
  assert.match(sourceText, /for \(const task of \[refreshRepos, refreshProxyRepos, cronSelfCheck, cronCountryQuality\]\)/);
  assert.doesNotMatch(sourceText, /Promise\.allSettled\(\[refreshRepos\(env\), refreshProxyRepos/);
  assert.match(sourceText, /GEO_CACHE_MAX = 256/);
  assert.match(sourceText, /while \(GEO_CACHE\.size > GEO_CACHE_MAX\)/);
  assert.match(sourceText, /while \(RELAY_COOLDOWN\.size > 256\)/);
});
