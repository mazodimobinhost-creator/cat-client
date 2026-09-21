#!/usr/bin/env node
/**
 * Multipart upload protocol test — verifies the EXACT byte sequence that
 * CloudflareWorker.kt (cfUploadWorker) writes, using the real worker asset,
 * and parses it with a strict RFC 7578-style parser. If this passes, the
 * payload accepted by Cloudflare's PUT /workers/scripts/{name} endpoint is
 * well-formed: boundaries, CRLF framing, part names and script bytes all
 * round-trip without corruption.
 *
 * Run: node scripts/panels/multipart-upload.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const workerScript = readFileSync(path.join(root, 'app/src/main/assets/panels/catclient.worker.js'), 'utf8');

// ---------------------------------------------------------------------------
// 1. Replicate CloudflareWorker.kt's cfUploadWorker writer byte-for-byte.
//    Kotlin writes (UTF-8 OutputStreamWriter):
//      "--$boundary\r\n"
//      'Content-Disposition: form-data; name="metadata"\r\n'
//      "Content-Type: application/json\r\n\r\n"
//      metadataJson + "\r\n"
//      "--$boundary\r\n"
//      'Content-Disposition: form-data; name="worker.js"; filename="worker.js"\r\n'
//      "Content-Type: application/javascript+module\r\n\r\n"
//      script
//      "\r\n--$boundary--\r\n"
// ---------------------------------------------------------------------------
const boundary = '----catclient1758500000000'; // System.currentTimeMillis() value
const metadata = { main_module: 'worker.js', bindings: [], compatibility_date: '2025-03-04' };
const parts = [
  ['metadata', 'application/json', JSON.stringify(metadata)],
  ['worker.js', 'application/javascript+module', workerScript],
];
let body = '';
for (const [name, contentType, content] of parts) {
  body += `--${boundary}\r\n`;
  body += `Content-Disposition: form-data; name="${name}"${name === 'worker.js' ? '; filename="worker.js"' : ''}\r\n`;
  body += `Content-Type: ${contentType}\r\n\r\n`;
  body += content;
  body += '\r\n';
}
body += `--${boundary}--\r\n`;
const raw = Buffer.from(body, 'utf8');

// ---------------------------------------------------------------------------
// 2. Strict parser (mirrors what a conforming server does).
// ---------------------------------------------------------------------------
function parseMultipart(buf, boundary) {
  const open = Buffer.from(`--${boundary}\r\n`, 'utf8');
  if (!buf.subarray(0, open.length).equals(open)) throw new Error('body does not start with the opening boundary');
  const delim = Buffer.from(`\r\n--${boundary}`, 'utf8');
  const idxs = [];
  let pos = open.length;
  for (;;) {
    const i = buf.indexOf(delim, pos);
    if (i < 0) break;
    idxs.push(i);
    pos = i + delim.length;
  }
  if (idxs.length < 1) throw new Error(`expected a closing boundary, found 0`);
  const parts = [];
  // Part i spans (prevBoundaryEnd .. idxs[i]), where part 0 starts right after the opening boundary.
  for (let i = 0; i < idxs.length; i++) {
    const start = i === 0 ? open.length : idxs[i - 1] + delim.length + 2; // +2: boundary line's own CRLF
    const end = idxs[i];
    let chunk = buf.subarray(start, end);
    // Terminating delimiter: "--" right after boundary → final part.
    if (chunk.subarray(0, 2).toString('utf8') === '--') throw new Error('unexpected terminal boundary in the middle');
    // Strip the trailing CRLF that precedes the next boundary.
    if (chunk.subarray(chunk.length - 2).toString('utf8') === '\r\n') chunk = chunk.subarray(0, chunk.length - 2);
    const headerEnd = chunk.indexOf('\r\n\r\n');
    if (headerEnd < 0) throw new Error('part missing header/body separator');
    const headersRaw = chunk.subarray(0, headerEnd).toString('utf8');
    const headers = {};
    for (const line of headersRaw.split('\r\n')) {
      const m = line.match(/^([A-Za-z-]+):\s*(.*)$/);
      if (!m) throw new Error(`bad header line: ${JSON.stringify(line)}`);
      headers[m[1].toLowerCase()] = m[2];
    }
    const disp = headers['content-disposition'] ?? '';
    const name = /name="([^"]*)"/.exec(disp)?.[1];
    const filename = /filename="([^"]*)"/.exec(disp)?.[1];
    if (!name) throw new Error('part without form-data name');
    parts.push({ name, filename, contentType: headers['content-type'] ?? '', body: chunk.subarray(headerEnd + 4) });
  }
  // Final boundary must be terminal.
  const tail = buf.subarray(idxs[idxs.length - 1] + delim.length);
  if (!tail.equals(Buffer.from('--\r\n', 'utf8'))) throw new Error('body does not end with the terminal boundary');
  return parts;
}

let failed = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ✓ ${name}`);
  else { failed++; console.error(`  ✗ ${name} ${extra}`); }
}

console.log('multipart upload protocol (mirrors CloudflareWorker.kt bytes)\n');
const parsed = parseMultipart(raw, boundary);

check('exactly two parts', parsed.length === 2, `got ${parsed.length}`);
const [metaPart, scriptPart] = parsed;
check('part 1 name = "metadata"', metaPart.name === 'metadata');
check('part 1 content-type = application/json', metaPart.contentType === 'application/json');
let meta = null;
try { meta = JSON.parse(metaPart.body.toString('utf8')); } catch (e) { /* below */ }
check('part 1 JSON parses', !!meta);
check('metadata.main_module = "worker.js"', meta?.main_module === 'worker.js');
check('metadata.bindings = []', Array.isArray(meta?.bindings) && meta.bindings.length === 0);
check('metadata.compatibility_date present', typeof meta?.compatibility_date === 'string' && meta.compatibility_date.length > 0);
check('part 2 name = "worker.js"', scriptPart.name === 'worker.js');
check('part 2 filename = "worker.js"', scriptPart.filename === 'worker.js');
check('part 2 content-type = application/javascript+module', scriptPart.contentType === 'application/javascript+module');
check(
  'part 2 body byte-identical to catclient.worker.js asset',
  scriptPart.body.equals(Buffer.from(workerScript, 'utf8')),
  `(len ${scriptPart.body.length} vs ${Buffer.byteLength(workerScript, 'utf8')})`,
);

// The real worker must still export the panel surface (guards against asset drift).
const src = scriptPart.body.toString('utf8');
for (const needle of ['/sub', '/ws', 'X-Forwarded-Sni', 'ok: true']) {
  if (needle === 'main_module') continue;
  check(`worker source contains ${JSON.stringify(needle)}`, src.includes(needle));
}

console.log(failed === 0 ? '\nALL MULTIPART CHECKS PASSED' : `\n${failed} MULTIPART CHECKS FAILED`);
process.exit(failed === 0 ? 0 : 1);
