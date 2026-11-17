import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');

const REQUIRED_FILES = [
  'README.md',
  'LICENSE',
  'CONTRIBUTING.md',
  'CODE_OF_CONDUCT.md',
  'SECURITY.md',
  'CHANGELOG.md',
  'Dockerfile',
  'docker-compose.yml',
  '.dockerignore',
  '.editorconfig',
  '.gitignore',
  '.github/dependabot.yml',
  'renovate.json',
  '.github/PULL_REQUEST_TEMPLATE.md',
  '.github/ISSUE_TEMPLATE/bug_report.md',
  '.github/ISSUE_TEMPLATE/feature_request.md',
];

test('repository health: all 10 checklist files and templates exist and are non-empty', () => {
  for (const rel of REQUIRED_FILES) {
    const abs = join(root, rel);
    assert.ok(existsSync(abs), `Missing required repo health file: ${rel}`);
    const content = readFileSync(abs, 'utf8').trim();
    assert.ok(content.length > 20, `Repo health file is unexpectedly empty: ${rel}`);
  }
});

test('repository health: CHANGELOG.md starts with # Changelog and includes latest release', () => {
  const changelog = readFileSync(join(root, 'CHANGELOG.md'), 'utf8');
  assert.match(changelog, /^# Changelog\b/);
  assert.match(changelog, /1\.10\.0-beta152/);
});

test('repository health: .github/workflows contains no .orig or backup files', () => {
  const workflowsDir = join(root, '.github', 'workflows');
  const entries = readdirSync(workflowsDir);
  const stray = entries.filter((name) => !name.endsWith('.yml') && !name.endsWith('.yaml'));
  assert.deepEqual(stray, [], `Unexpected non-workflow files in .github/workflows: ${stray.join(', ')}`);
});
