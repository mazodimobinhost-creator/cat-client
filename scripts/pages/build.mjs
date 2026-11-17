import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const sourcePath = resolve(root, 'app/src/main/assets/panels/catclient.worker.js');
const outputDir = resolve(root, 'pages/dist');
const outputPath = resolve(outputDir, '_worker.js');
const source = await readFile(sourcePath, 'utf8');

if (!/export\s+default\s*\{/.test(source)) throw new Error('canonical Worker has no module default export');
if (!/CAT_PANEL_VERSION\s*=\s*'[0-9]+(?:\.[0-9]+)+'/.test(source)) throw new Error('canonical Worker version is missing');

await rm(outputDir, { recursive: true, force: true });
await mkdir(outputDir, { recursive: true });
await writeFile(outputPath,
  `/* Generated Pages advanced-mode entrypoint. Do not edit; source: app/src/main/assets/panels/catclient.worker.js */\n${source}`,
  'utf8');
await writeFile(resolve(outputDir, '.assetsignore'), '_worker.js\n', 'utf8');
console.log(`Pages artifact: ${outputPath}`);
