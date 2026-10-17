import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const html = await readFile(new URL('../public/index.html', import.meta.url), 'utf8');
test('mobile RTL UI is self-contained and exposes core workflows', () => {
  assert.match(html, /<html lang="fa" dir="rtl">/);
  assert.match(html, /name="viewport"/);
  for (const route of ['/api/login', '/api/users', '/api/candidates', '/api/auto-inbound', '/api/xray/']) assert.match(html, new RegExp(route.replaceAll('/', '\\/')));
  assert.doesNotMatch(html, /<script[^>]+src=|<link[^>]+href=/);
  assert.doesNotMatch(html, /localStorage/);
});
