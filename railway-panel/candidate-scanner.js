import tls from 'node:tls';

const ipv4Number = value => value.split('.').reduce((n, part) => (n * 256) + Number(part), 0) >>> 0;
const inV4 = (n, base, bits) => (n >>> (32 - bits)) === (ipv4Number(base) >>> (32 - bits));
export function isPublicIp(address) {
  if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) {
    const n = ipv4Number(address);
    return ![
      ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
      ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24],
      ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24],
      ['224.0.0.0', 4], ['240.0.0.0', 4],
    ].some(([base, bits]) => inV4(n, base, bits));
  }
  const v = address.toLowerCase();
  return v.includes(':') && v !== '::' && v !== '::1' && !v.startsWith('fc') && !v.startsWith('fd') && !/^fe[89ab]/.test(v) && !v.startsWith('ff') && !v.startsWith('2001:db8:');
}

export function probeCandidate(candidate, timeoutMs = 7000) {
  return new Promise(resolve => {
    const started = Date.now(); let settled = false; let response = '';
    const finish = result => {
      if (settled) return; settled = true;
      socket.destroy(); resolve({ ...result, latencyMs: Date.now() - started, checkedAt: Date.now() });
    };
    const socket = tls.connect({ host: candidate.address, port: candidate.port, servername: candidate.sni, rejectUnauthorized: true, ALPNProtocols: ['http/1.1'], timeout: timeoutMs }, () => {
      socket.write(`HEAD / HTTP/1.1\r\nHost: ${candidate.sni}\r\nConnection: close\r\nUser-Agent: CatRailwayProbe/1\r\n\r\n`);
    });
    socket.on('data', chunk => {
      response = (response + chunk).slice(0, 1024);
      const match = /^HTTP\/1\.[01] (\d{3})/m.exec(response);
      if (match) finish({ ok: Number(match[1]) >= 200 && Number(match[1]) < 500, tls: true, httpStatus: Number(match[1]) });
    });
    socket.on('timeout', () => finish({ ok: false, tls: false, error: 'timeout' }));
    socket.on('error', error => finish({ ok: false, tls: false, error: ['CERT', 'TLS'].some(x => error.code?.includes(x)) ? 'tls' : 'network' }));
    socket.on('close', () => finish({ ok: false, tls: socket.authorized, error: 'closed_without_http' }));
  });
}
