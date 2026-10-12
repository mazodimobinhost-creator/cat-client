// Guard for the one status message that must never be wrong.
//
// Measured on a real Cloudflare account (live test, 2026-10-12): after an upload,
// a brand-new workers.dev route keeps answering the EDGE's own 404 page for a
// while before it serves the Worker — 13 s in one run, most of a minute in
// another. The app used to stop probing after 5 × 2.5 s and fall through to
// «the file did not load, paste the 3-line file by hand», which tells a user that
// a perfectly good upload is broken and sends him to fix nothing.
//
// These assertions keep the three cases apart and cannot be satisfied by a
// cosmetic rename:
//
//   1. the probe still recognises the edge's own 404 page as «route not live yet»
//   2. the wait budget still covers the measured window
//   3. the renderer asks that question BEFORE it asks «did the file load»
//   4. both locales carry the pending message, with the status placeholder
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');

const worker = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'CloudflareWorker.kt');
const activity = read('app', 'src', 'main', 'java', 'com', 'cat', 'client', 'MainActivity.kt');
const en = read('app', 'src', 'main', 'res', 'values', 'strings.xml');
const fa = read('app', 'src', 'main', 'res', 'values-fa', 'strings.xml');

/** The block of a Kotlin function/property, up to the next declaration. */
function block(source, marker, max = 2_000) {
  const at = source.indexOf(marker);
  assert.notEqual(at, -1, `${marker} is gone`);
  return source.slice(at, at + max);
}

test('the probe still tells «route not live yet» apart from «the file never loaded»', () => {
  const probe = block(worker, 'val routeNotLiveYet');
  assert.match(probe, /status == 404/, 'the edge answers 404 when no Worker is bound to the route');
  assert.match(probe, /bodyHead\.trimStart\(\)\.startsWith\("<"\)/,
    'an HTML body is the edge page; the panel always answers JSON, so HTML is never us');
  assert.match(probe, /!online/, 'it must not fire for a panel that answered');
  // and the old failure state must still exist for the cases that are real failures
  assert.match(worker, /val scriptNeverLoaded: Boolean get\(\) = !online && status > 0/,
    'the «file never loaded» state must survive: it is the 1101 that has no report');
});

test('the wait budget covers the measured window of a fresh route', () => {
  const wait = block(worker, 'private fun waitForPanel');
  const attempts = Number((wait.match(/attempts:\s*Int\s*=\s*(\d+)/) || [])[1]);
  const delay = Number((wait.match(/delayMs:\s*Long\s*=\s*([\d_]+)L/) || [])[1]?.replace(/_/g, ''));
  assert.ok(Number.isFinite(attempts) && attempts > 0, 'attempts default is unreadable');
  assert.ok(Number.isFinite(delay) && delay > 0, 'delayMs default is unreadable');
  const budgetMs = (attempts - 1) * delay;
  assert.ok(budgetMs >= 30_000,
    `the wait gives up after ${(budgetMs / 1000).toFixed(1)}s, inside the window a fresh route is still 404-ing in`);
  // the loop still short-circuits, so a healthy panel is never made to wait
  assert.match(wait, /if \(health\.online\) return health/, 'an answered probe must return immediately');
});

test('the pending message is rendered before the failure message', () => {
  const line = block(activity, 'private fun panelStatusLine');
  const pending = line.indexOf('routeNotLiveYet');
  const failed = line.indexOf('scriptNeverLoaded');
  assert.notEqual(pending, -1, 'panelStatusLine no longer considers the pending state');
  assert.notEqual(failed, -1, 'panelStatusLine no longer considers the never-loaded state');
  assert.ok(pending < failed,
    'the 404-from-the-edge case must be decided before «the file did not load», or a fresh route is reported as a broken upload');
  assert.match(line, /R\.string\.cloud_panel_route_pending/, 'the pending case must have its own wording');
});

test('both locales carry the pending message', () => {
  for (const [name, source] of [['values', en], ['values-fa', fa]]) {
    const m = source.match(/<string name="cloud_panel_route_pending">([^<]*)<\/string>/);
    assert.ok(m, `${name}/strings.xml is missing cloud_panel_route_pending`);
    assert.match(m[1], /%1\$s/, `${name} must show the status code that came back`);
  }
});
