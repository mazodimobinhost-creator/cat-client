/** Cat Wizard Vercel host: wrapper maps paths and ships the same installer. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '../..');
const api = path.join(root, 'wizard-web/api/index.js');
const vz = path.join(root, 'wizard-web/vercel.json');
const lib = path.join(root, 'wizard-web/lib/catclient.wizard.js');
const src = path.join(root, 'app/src/main/assets/panels/catclient.wizard.js');

execFileSync(process.execPath, ['--check', api], { stdio: 'pipe' });
execFileSync(process.execPath, ['--check', lib], { stdio: 'pipe' });

let failures = 0;
function check(name, cond, extra) {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); }
}

const apiSrc = readFileSync(api, 'utf8');
const vzSrc = readFileSync(vz, 'utf8');
const libSrc = readFileSync(lib, 'utf8');
const wizardSrc = readFileSync(src, 'utf8');

check('Vercel wrapper is edge', apiSrc.includes("runtime: 'edge'"));
check('wrapper imports the wizard', apiSrc.includes("from '../lib/catclient.wizard.js'"));
check('wrapper remaps __wpath', apiSrc.includes('__wpath'));
check('vercel.json rewrites /api/install', vzSrc.includes('/api/install'));
check('lib wizard matches app asset', libSrc === wizardSrc);
check('fresh install closes OPEN_SUB', wizardSrc.includes("name: 'OPEN_SUB', text: 'false'"));
check('fresh install closes OPEN_PANEL', wizardSrc.includes("name: 'OPEN_PANEL', text: 'false'"));

if (failures) process.exit(1);
console.log('wizard-vercel ok');
