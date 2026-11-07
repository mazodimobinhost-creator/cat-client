// Permanent regression test for the «app opens then closes» bug class.
//
// Root cause found in beta70..beta77: MainActivity had a class-level property
// initializer `scannerForceV6 = getSharedPreferences(...)`. Property
// initializers run inside the Activity constructor, which ActivityThread calls
// via Class.newInstance() BEFORE attachBaseContext() — the Activity context is
// still null, so getSharedPreferences throws NullPointerException and the phone
// shows only «the app opens and closes instantly». Compilation, unit tests and
// the (KVM-less) emulator step all missed it; this test makes sure it cannot
// come back:
//
//  1. fixture with the exact crashing shape  → the guard must FAIL and name it
//  2. fixture with the legal patterns        → the guard must PASS
//     (by lazy, hardcoded defaults, lateinit)
//  3. the real app sources                   → the guard must PASS
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const guard = join(root, 'scripts', 'android-startup-guard.py');

function runGuard(files) {
  try {
    const stdout = execFileSync('python3', [guard, ...files], { encoding: 'utf8' });
    return { code: 0, output: stdout };
  } catch (error) {
    return { code: error.status ?? 1, output: `${error.stdout ?? ''}${error.stderr ?? ''}` };
  }
}

function fixture(content) {
  const dir = mkdtempSync(join(tmpdir(), 'startup-guard-'));
  const file = join(dir, 'Fixture.kt');
  writeFileSync(file, content);
  return file;
}

const CRASHING = `package com.cat.client

class FixtureActivity : Activity() {
    private var scannerForceV6: Boolean =
        getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("scanner_force_v6", false)
    private var scannerAiList: LinearLayout = LinearLayout(this)
}
`;

const SAFE = `package com.cat.client

class SafeActivity : Activity() {
    private var scannerForceV6: Boolean = false
    private var scannerAiList: LinearLayout? = null
    private lateinit var later: LinearLayout
    private val palette: CatClientPalette by lazy { CatClientDesignTokens.forContext(this) }
    private val label: String = "static"
}
`;

test('guard fails on the exact beta77 crash shape (prefs + view in initializers)', () => {
  const { code, output } = runGuard([fixture(CRASHING)]);
  assert.equal(code, 1, 'a crashing fixture must fail the guard');
  assert.match(output, /getSharedPreferences/);
  assert.match(output, /LinearLayout\(this\)/);
});

test('guard passes on safe patterns (lazy, defaults, lateinit)', () => {
  const { code, output } = runGuard([fixture(SAFE)]);
  assert.equal(code, 0, `safe fixture must pass, got: ${output}`);
  assert.match(output, /startup guard OK/);
});

test('the real app sources contain no constructor-time Context use', () => {
  const { code, output } = runGuard([]);
  assert.equal(code, 0, `app sources must stay clean:\n${output}`);
  assert.match(output, /startup guard OK/);
});
