// Chain config-link protocols: vless/trojan over ws/httpupgrade/tcp(+tls).
// Byte-level tests for the wire formats + fake-socket end-to-end wiring.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { _testing as T } from '../../app/src/main/assets/panels/catclient.worker.js';

const hex = (u8) => [...u8].map((b) => b.toString(16).padStart(2, '0')).join('');

test('parseChain: vless ws link → path comes from path= param (with ed)', () => {
  const c = T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@free.example.com:443?encryption=none&security=tls&sni=free.example.com&fp=chrome&type=ws&host=free.example.com&path=%2Fvl%2Fabc%3Fed%3D2560');
  assert.equal(c.type, 'vless');
  assert.equal(c.user, 'a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d');
  assert.equal(c.host, 'free.example.com');
  assert.equal(c.port, 443);
  assert.equal(c.tls, true);
  assert.equal(c.transport, 'ws');
  assert.equal(c.path, '/vl/abc?ed=2560');
  assert.equal(c.sni, 'free.example.com');
});

test('parseChain: trojan tcp link', () => {
  const c = T.parseChain('trojan://hunter2@relay.example.org:443?security=tls&type=tcp#my-relay');
  assert.equal(c.type, 'trojan');
  assert.equal(c.pass, 'hunter2');
  assert.equal(c.transport, 'tcp');
  assert.equal(c.tls, true);
});

test('parseChain: reality and flow are rejected (not supported)', () => {
  assert.equal(T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@x.example:443?security=reality&pbk=KuxxAbc123&type=tcp&flow=xtls-rprx-vision'), null);
  assert.equal(T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@x.example:443?type=ws&flow=xtls-rprx-vision'), null);
});

test('vlessHeader: ver0 + uuid + cmdTCP + portBE + domain', () => {
  const h = T.vlessHeader('a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d', 'example.com', 443);
  assert.equal(hex(h.slice(0, 17)), '00' + 'a3b1c2d3e4f54a5b8c6d7e8f9a0b1c2d');
  assert.equal(h[17], 0x01);
  assert.equal(h[18], 0x01); assert.equal(h[19], 0xbb);
  assert.equal(h[20], 0x02); assert.equal(h[21], 11);
  assert.equal(new TextDecoder().decode(h.slice(22, 33)), 'example.com');
  const h4 = T.vlessHeader('a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d', '1.2.3.4', 80);
  assert.equal(h4[20], 0x01);
  assert.equal(hex(h4.slice(21, 25)), '01020304');
});

test('trojanRequest: sha224(pass) CRLF + addr + CRLF (exact length)', async () => {
  const tr = await T.trojanRequest('pw', 'example.com', 443);
  const sha = await T.sha224Hex('pw');
  const txt = new TextDecoder().decode(tr);
  assert.ok(txt.startsWith(sha + '\r\n'));
  assert.equal(tr[sha.length + 2], 0x03);
  assert.equal(tr[sha.length + 3], 11);
  assert.ok(txt.endsWith('\r\n'));
  assert.equal(tr.byteLength, sha.length + 2 + 2 + 11 + 2); // no trailing pad
});

function fakeServerSocket(serverWrites) {
  let si = 0;
  function frame(payload, opcode = 0x2, fin = true) {
    const len = payload.length;
    let hdr;
    if (len < 126) hdr = new Uint8Array([fin ? 0x80 | opcode : opcode, len]);
    else hdr = new Uint8Array([fin ? 0x80 | opcode : opcode, 126, (len >> 8) & 0xff, len & 0xff]);
    const out = new Uint8Array(hdr.length + len);
    out.set(hdr); out.set(payload, hdr.length);
    return out;
  }
  const received = [];
  const chunks = serverWrites;
  const readable = new ReadableStream({
    async pull(c) {
      if (si < chunks.length) { c.enqueue(chunks[si++]); return; }
      await new Promise((r) => setTimeout(r, 5));
      if (si >= chunks.length) { c.close(); return; }
    },
  });
  const writable = new WritableStream({ write(chunk) { received.push(new Uint8Array(chunk)); } });
  return { readable, writable, close() {}, received, frame };
}

test('wsClientLayer: frames split across chunks; masked write roundtrip', async () => {
  const enc = new TextEncoder();
  const probe = fakeServerSocket([]);
  const full = probe.frame(enc.encode('HTTP/1.1 200 OK\r\n\r\nhello-chain'));
  const sock = fakeServerSocket([full.slice(0, 3), full.slice(3, 9), full.slice(9)]);
  const layer = T.wsClientLayer(sock);
  const reader = layer.readable.getReader();
  const got = await reader.read();
  assert.equal(new TextDecoder().decode(got.value), 'HTTP/1.1 200 OK\r\n\r\nhello-chain');
  assert.equal((await reader.read()).done, true);
  const w = layer.writable.getWriter();
  await w.write(enc.encode('PING'));
  w.releaseLock();
  const frame = sock.received[0];
  assert.equal(frame[0], 0x82); // FIN+binary
  const mask = frame.slice(2, 6);
  const body = frame.slice(6);
  const un = new Uint8Array([...body].map((b, i) => b ^ mask[i % 4]));
  assert.equal(new TextDecoder().decode(un), 'PING');
});

test('dialViaChain: vless over httpupgrade+tls end-to-end (fake socket)', async () => {
  const enc = new TextEncoder(); const dec = new TextDecoder();
  let capturedHead = '';
  const serverSock = {
    readable: new ReadableStream({
      start(c) {
        const head = 'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n';
        const vlessResp = new Uint8Array([0, 0]);
        const data = enc.encode('HTTP/1.1 204 No Content\r\n\r\n');
        const all = new Uint8Array(head.length + vlessResp.length + data.length);
        all.set(enc.encode(head), 0); all.set(vlessResp, head.length); all.set(data, head.length + vlessResp.length);
        c.enqueue(all); c.close();
      },
    }),
    writable: new WritableStream({ write(ch) { capturedHead += dec.decode(ch); } }),
    close() {}, opened: Promise.resolve(),
    startTls() { return serverSock; },
  };
  const sockets = { connect: () => serverSock };
  const chain = T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@free.example.com:443?security=tls&sni=free.example.com&type=httpupgrade&host=free.example.com&path=%2Fhu');
  const dialed = await T.dialViaChain(sockets, chain, 'target.example', 80);
  assert.match(capturedHead, /GET \/hu HTTP\/1\.1/);
  assert.match(capturedHead, /Upgrade: websocket/i);
  assert.match(capturedHead, /Host: free\.example\.com/);
  const done = await dialed.socket.readable.getReader().read();
  assert.equal(done.done, true); // prefix consumed by the handshake
  assert.ok(dec.decode(dialed.leftover).startsWith('HTTP/1.1 204')); // pure target data
});

test('chainProbe: full round-trip reports exit ip/country through the chain', async () => {
  const enc = new TextEncoder(); const dec = new TextDecoder();
  let sent = '';
  const serverSock = {
    readable: new ReadableStream({
      start(c) {
        const head = 'HTTP/1.1 101 Switching Protocols\r\n\r\n';
        const vlessResp = new Uint8Array([0, 0]);
        const body = enc.encode('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"status":"success","query":"5.6.7.8","country":"Germany","countryCode":"DE"}');
        const all = new Uint8Array(head.length + vlessResp.length + body.length);
        all.set(enc.encode(head), 0); all.set(vlessResp, head.length); all.set(body, head.length + vlessResp.length);
        c.enqueue(all); c.close();
      },
    }),
    writable: new WritableStream({ write(ch) { sent += dec.decode(ch); } }),
    close() {}, opened: Promise.resolve(),
    startTls() { return serverSock; },
  };
  const chain = T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@free.example.com:443?security=tls&sni=free.example.com&type=httpupgrade&host=free.example.com&path=%2Fhu');
  const r = await T.chainProbe({ connect: () => serverSock }, chain);
  assert.equal(r.ok, true);
  assert.equal(r.exitIp, '5.6.7.8');
  assert.equal(r.cc, 'DE');
  assert.equal(r.country, 'Germany');
  assert.match(sent, /GET \/json\?fields=status,query,country,countryCode HTTP\/1\.1/);
  assert.match(sent, /Host: ip-api\.com/);
  // the probe request must be the FIRST bytes after the protocol handshake —
  // no injected junk (regression: the old probe leaked into real traffic)
  assert.ok(!sent.slice(0, sent.indexOf('GET /json')).includes('generate_204'));
});

test('chainProbe: dial failure rejects', async () => {
  const deadSock = {
    readable: new ReadableStream({ start(c) { c.close(); } }),
    writable: new WritableStream({ write() { throw new Error('boom'); } }),
    close() {}, opened: Promise.resolve(), startTls() { return deadSock; },
  };
  const chain = T.parseChain('vless://a3b1c2d3-e4f5-4a5b-8c6d-7e8f9a0b1c2d@free.example.com:443?security=tls&sni=free.example.com&type=httpupgrade&host=free.example.com&path=%2Fhu');
  await assert.rejects(() => T.chainProbe({ connect: () => deadSock }, chain));
});
