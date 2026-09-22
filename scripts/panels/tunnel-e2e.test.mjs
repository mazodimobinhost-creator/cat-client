/**
 * End-to-end data-plane test: drives handleTunnelConnection with a fake
 * WebSocket and a fake `cloudflare:sockets` module, then checks that
 *  - the VLESS response header ([version, 0]) is prefixed to the first chunk,
 *  - the header payload + later frames reach the "remote" in order,
 *  - Trojan handshakes work without a response header,
 *  - Cloudflare-hosted destinations are dialled through a proxy IP,
 *  - unknown UUIDs are rejected before any dial,
 *  - VLESS UDP/53 is answered via DoH.
 */
import { _testing as T } from '../../app/src/main/assets/panels/catclient.worker.js';

let failures = 0;
function check(name, ok, extra) {
  console.log((ok ? '✓ ' : '✗ ') + name + (ok || extra === undefined ? '' : ' — ' + extra));
  if (!ok) failures++;
}

class FakeWs {
  constructor() {
    this.readyState = 1;
    this.sent = [];
    this.listeners = {};
    this.closed = null;
  }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  emit(type, event) { (this.listeners[type] || []).forEach((fn) => fn(event)); }
  send(data) { this.sent.push(new Uint8Array(data instanceof Uint8Array ? data : new Uint8Array(data))); }
  close(code, reason) { if (this.closed) return; this.closed = { code, reason }; this.readyState = 3; this.emit('close', {}); }
}

/** Fake TCP socket: records writes, echoes a canned reply once. */
function makeFakeSockets(record) {
  return {
    connect(addr) {
      record.dials.push(addr.hostname + ':' + addr.port);
      if (record.refuse && record.refuse.has(addr.hostname)) throw new Error('ECONNREFUSED');
      const written = [];
      let pullController;
      const readable = new ReadableStream({
        start(controller) { pullController = controller; },
      });
      const writable = new WritableStream({
        write(chunk) {
          written.push(new Uint8Array(chunk));
          record.written = written;
          // First write → reply with the echo.
          if (written.length === 1) {
            pullController.enqueue(new TextEncoder().encode('HTTP/1.1 200 OK\r\n\r\nhello'));
          }
        },
        close() { try { pullController.close(); } catch (e) { /* ignore */ } },
      });
      return { readable, writable, opened: Promise.resolve(), close() { try { pullController.close(); } catch (e) { /* ignore */ } } };
    },
  };
}

const encoder = new TextEncoder();
const uuid = '11111111-2222-3333-4444-555555555555';
const uuidBytes = uuid.replace(/-/g, '').match(/../g).map((h) => parseInt(h, 16));
function vlessFrame(host, port, payload, command = 1, id = uuidBytes) {
  const domain = Array.from(encoder.encode(host));
  return new Uint8Array([0, ...id, 0, command, port >> 8, port & 255, 2, domain.length, ...domain, ...payload]);
}

async function runTunnel(ws, frames, env, record, options = {}) {
  const originalImport = T.__setSockets;
  const sockets = makeFakeSockets(record);
  T.__setSockets(sockets);
  const done = T.handleTunnelConnection(ws, env, Object.assign({ masterUuid: uuid }, options));
  // give the listener a tick to attach, then feed frames
  await new Promise((r) => setTimeout(r, 5));
  for (const frame of frames) {
    ws.emit('message', { data: frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength) });
    await new Promise((r) => setTimeout(r, 5));
  }
  await new Promise((r) => setTimeout(r, 40));
  ws.close(1000, 'client done');
  await Promise.race([done, new Promise((r) => setTimeout(r, 300))]);
  void originalImport;
}

// 1. VLESS TCP: response header + payload ordering
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const first = vlessFrame('example.org', 80, Array.from(encoder.encode('GET / ')));
  const second = encoder.encode('HTTP/1.1\r\n\r\n');
  await runTunnel(ws, [first, second], {}, record);
  check('vless dials the destination directly', record.dials[0] === 'example.org:80', record.dials.join(','));
  const upstreamText = (record.written || []).map((b) => new TextDecoder().decode(b)).join('');
  check('vless forwards header payload then next frame', upstreamText === 'GET / HTTP/1.1\r\n\r\n', JSON.stringify(upstreamText));
  const firstDown = ws.sent[0] || new Uint8Array(0);
  check('vless response header [0,0] precedes data', firstDown[0] === 0 && firstDown[1] === 0 && new TextDecoder().decode(firstDown.subarray(2)).startsWith('HTTP/1.1 200'));
}

// 2. Unknown UUID → rejected, no dial
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const badId = uuidBytes.slice(); badId[0] = 0x99;
  const frame = vlessFrame('example.org', 80, [], 1, badId);
  await runTunnel(ws, [frame], {}, record);
  check('unknown uuid is refused before dialling', record.dials.length === 0 && ws.closed && ws.closed.code === 1008, JSON.stringify(ws.closed));
}

// 3. Cloudflare destination → proxy IP
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const frame = vlessFrame('104.16.5.5', 443, []);
  // atyp=1 ipv4 frame
  const v4 = new Uint8Array([0, ...uuidBytes, 0, 1, 0x01, 0xBB, 1, 104, 16, 5, 5]);
  void frame;
  await runTunnel(ws, [v4], { PROXYIP: 'relay.example.net:8443' }, record);
  check('cloudflare-hosted target goes through PROXYIP', record.dials[0] === 'relay.example.net:8443', record.dials.join(','));
}

// 4. Refused direct dial → falls back to proxy IP
{
  const ws = new FakeWs();
  const record = { dials: [], refuse: new Set(['blocked.example']) };
  const frame = vlessFrame('blocked.example', 443, []);
  await runTunnel(ws, [frame], { PROXYIP: 'relay.example.net' }, record);
  check('refused direct dial falls back to proxy', record.dials.join(',') === 'blocked.example:443,relay.example.net:443', record.dials.join(','));
}

// 5. Trojan handshake (password = master uuid)
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const hash = await T.trojanHash(uuid);
  const domain = Array.from(encoder.encode('trojan.example'));
  const frame = new Uint8Array([...encoder.encode(hash), 13, 10, 1, 3, domain.length, ...domain, 0x01, 0xBB, 13, 10, ...encoder.encode('PING')]);
  await runTunnel(ws, [frame], {}, record);
  check('trojan dials destination', record.dials[0] === 'trojan.example:443', record.dials.join(','));
  const up = (record.written || []).map((b) => new TextDecoder().decode(b)).join('');
  check('trojan forwards payload', up === 'PING', JSON.stringify(up));
  const down = ws.sent[0] || new Uint8Array(0);
  check('trojan has no response header', new TextDecoder().decode(down).startsWith('HTTP/1.1 200'));
}

// 6. Early data via Sec-WebSocket-Protocol
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const frame = vlessFrame('early.example', 8080, Array.from(encoder.encode('ED')));
  const header = Buffer.from(frame).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  await runTunnel(ws, [], {}, record, { earlyDataHeader: header });
  check('early data handshake dials without a message frame', record.dials[0] === 'early.example:8080', record.dials.join(','));
  const up = (record.written || []).map((b) => new TextDecoder().decode(b)).join('');
  check('early data payload forwarded', up === 'ED', JSON.stringify(up));
}

// 7. VLESS UDP/53 answered through DoH
{
  const ws = new FakeWs();
  const record = { dials: [] };
  const origFetch = globalThis.fetch;
  let dohCalled = 0;
  globalThis.fetch = async (url, init) => {
    dohCalled++;
    const q = new Uint8Array(init.body);
    return new Response(new Uint8Array([q[0], q[1], 0x81, 0x80]), { headers: { 'content-type': 'application/dns-message' } });
  };
  const query = [0xAB, 0xCD, 1, 0];
  const frame = vlessFrame('8.8.8.8', 53, [0, query.length, ...query], 2);
  await runTunnel(ws, [frame], {}, record);
  globalThis.fetch = origFetch;
  check('udp dns never opens tcp', record.dials.length === 0);
  check('udp dns answered via DoH', dohCalled === 1);
  const down = ws.sent[0] || new Uint8Array(0);
  check('udp dns reply carries vless header + length prefix', down[0] === 0 && down[1] === 0 && down[2] === 0 && down[3] === 4 && down[4] === 0xAB, Array.from(down).join(','));
}

console.log(failures === 0 ? '\nTUNNEL E2E PASSED' : '\n' + failures + ' TUNNEL TEST(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
