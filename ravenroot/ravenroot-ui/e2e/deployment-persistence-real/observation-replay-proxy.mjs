import { createServer, request as httpRequest } from 'node:http';

/** Only the real browser fixture's canonical observation route may be forwarded. */
export function isObservationTarget(target) {
  if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//')
      || /[\\#\s]/.test(target)) return false;
  const parts = target.split('?');
  const match = /^\/v1\/deployments\/([^/]+)\/events$/.exec(parts[0]);
  if (!match || parts.length !== 2) return false;
  try {
    const id = decodeURIComponent(match[1]);
    if (!id || id === '.' || id === '..' || /[\/\\?#]/.test(id)
        || encodeURIComponent(id) !== match[1]) return false;
    const query = new URLSearchParams(parts[1]);
    return query.toString() === parts[1]
      && query.getAll('graphVersion').length === 1
      && /^[0-9a-f]{64}$/.test(query.get('graphVersion') || '')
      && query.getAll('include').length <= 1
      && (!query.has('include') || query.get('include') === 'diagnostics')
      && [...query.keys()].every(key => ['graphVersion', 'include'].includes(key));
  } catch { return false; }
}

export function createObservationReplayProxy(origin, ready) {
  const fixture = new URL(origin);
  if (fixture.protocol !== 'http:' || fixture.hostname !== '127.0.0.1' || !fixture.port
      || fixture.username || fixture.password || fixture.pathname !== '/'
      || fixture.search || fixture.hash) throw new Error('Replay requires the owned loopback fixture');
  return createServer((incoming, outgoing) => {
    if (incoming.method !== 'GET' || !isObservationTarget(incoming.url)) {
      outgoing.writeHead(400); outgoing.end(); return;
    }
    // Request data never supplies the protocol, hostname, port, or Host authority.
    const forwarded = httpRequest({ hostname: '127.0.0.1', port: Number(fixture.port),
      method: 'GET', path: incoming.url, headers: { ...incoming.headers, host: fixture.host } }, upstream => {
      outgoing.writeHead(upstream.statusCode, upstream.headers);
      let bytes = Buffer.alloc(0);
      upstream.on('data', chunk => {
        bytes = Buffer.concat([bytes, chunk]); const boundary = bytes.indexOf('\n\n');
        if (boundary < 0) return;
        outgoing.write(bytes.subarray(0, boundary + 2));
        ready(() => { outgoing.end(); upstream.destroy(); });
        upstream.removeAllListeners('data');
        upstream.on('data', () => {});
      });
      outgoing.on('close', () => upstream.destroy());
      upstream.on('error', () => { if (!outgoing.writableEnded) outgoing.end(); });
    });
    outgoing.on('close', () => forwarded.destroy());
    forwarded.on('error', () => outgoing.destroy()); forwarded.end();
  });
}
