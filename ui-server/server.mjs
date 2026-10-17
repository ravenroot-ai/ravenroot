/** Static UI and same-origin streaming proxy; no backend execution runtime. */
import http from 'node:http';
import https from 'node:https';
import { createReadStream, statSync, readFileSync } from 'node:fs';
import { resolve, extname } from 'node:path';
import { Transform } from 'node:stream';

const backendTokens = new WeakMap();

export function configuration(env = process.env) {
  const prefix = env.RAVENROOT_UI_PREFIX || '';
  if (prefix && !/^\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+$/.test(prefix)) {
    throw new Error('RAVENROOT_UI_PREFIX must be empty or a slash-prefixed path without trailing slash');
  }
  const port = Number(env.RAVENROOT_UI_PORT || 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid UI port');
  if (env.NODE_TLS_REJECT_UNAUTHORIZED === '0') throw new Error('TLS verification cannot be disabled');
  // Unconfigured standalone UI is a static authoring surface, never a proxy to itself.
  const configured = env.RAVENROOT_UI_BACKEND_URL?.trim();
  let upstream = null;
  if (configured) {
    try { upstream = new URL(configured); }
    catch { throw new Error('UI_BACKEND_CONFIGURATION_REFUSED: backend URL is invalid'); }
  }
  if (upstream && (!['http:', 'https:'].includes(upstream.protocol) || upstream.username || upstream.password
      || upstream.search || upstream.hash || /%|\/\//.test(upstream.pathname))) {
    throw new Error('Backend URL must be HTTP(S), without credentials, query, fragment or encoded path');
  }
  if (upstream) {
    const hostname = upstream.hostname.toLowerCase().replace(/\.$/, '');
    const upstreamPort = Number(upstream.port || (upstream.protocol === 'https:' ? 443 : 80));
    if (upstreamPort === port && (/^127\./.test(hostname)
        || ['localhost', '[::1]', '0.0.0.0', '[::]'].includes(hostname))) {
      throw new Error('Backend loopback URL must not target the UI listening port');
    }
  }
  const token = env.RAVENROOT_UI_BACKEND_TOKEN;
  if (token !== undefined && (typeof token !== 'string' || token.length === 0 || token.length > 16384
      || !/^[A-Za-z0-9._~+/-]+=*$/.test(token) || !upstream)) {
    throw new Error('UI_BACKEND_TOKEN_CONFIGURATION_REFUSED: provide a valid bearer value with a configured backend URL');
  }
  const backendPrefix = upstream?.pathname.replace(/\/$/, '') || '';
  const config = { prefix, upstream, backendPrefix, port, headerTimeoutMs: 15000, root: resolve(env.RAVENROOT_UI_ROOT || '/opt/ravenroot/ui') };
  if (token !== undefined) backendTokens.set(config, token);
  return config;
}

const hop = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade']);
function headers(source) {
  const excluded = new Set([...hop, ...String(source.connection || '').toLowerCase().split(',').map(v => v.trim())]);
  return Object.fromEntries(Object.entries(source).filter(([key]) => !excluded.has(key.toLowerCase())));
}
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.graphml': 'application/xml' };

// Hold only a suffix that could be the beginning of the secret. Ordinary SSE bytes flow
// immediately; a secret split across arbitrary UTF-8 chunks is still removed byte for byte.
function secretFilter(secret) {
  const needle = Buffer.from(secret);
  const prefixes = new Uint32Array(needle.length);
  for (let i = 1, match = 0; i < needle.length; i += 1) {
    while (match && needle[i] !== needle[match]) match = prefixes[match - 1];
    if (needle[i] === needle[match]) match += 1;
    prefixes[i] = match;
  }
  let pending = Buffer.alloc(0);
  return new Transform({
    transform(chunk, _encoding, done) {
      const data = Buffer.concat([pending, chunk]);
      let cursor = 0;
      for (let found = data.indexOf(needle, cursor); found !== -1; found = data.indexOf(needle, cursor)) {
        this.push(data.subarray(cursor, found)); this.push('[redacted]'); cursor = found + needle.length;
      }
      let keep = 0;
      // Linear prefix matching prevents adversarial near-matches from making suffix scanning
      // quadratic in the configured token size. Complete matches were already removed above.
      for (let i = Math.max(cursor, data.length - needle.length + 1); i < data.length; i += 1) {
        while (keep && data[i] !== needle[keep]) keep = prefixes[keep - 1];
        if (data[i] === needle[keep]) keep += 1;
      }
      this.push(data.subarray(cursor, data.length - keep));
      pending = data.subarray(data.length - keep);
      done();
    },
    flush(done) { if (pending.length) this.push(pending); done(); },
  });
}

function browserMayUseDefault(req) {
  const site = req.headers['sec-fetch-site'];
  if (site && !['same-origin', 'none'].includes(site)) return false;
  if (req.headers.origin === undefined) return true;
  try {
    const origin = new URL(req.headers.origin);
    // TLS may terminate at the deployment's access-controlled HTTPS edge. Host is the public
    // request authority; never accept Forwarded headers as an authority override.
    return ['http:', 'https:'].includes(origin.protocol) && origin.host === req.headers.host
      && origin.origin === req.headers.origin;
  } catch { return false; }
}

export function createServer(configuration) {
  const defaultToken = backendTokens.get(configuration);
  // Snapshot authority before accepting traffic. Mutable configuration objects cannot retarget it.
  const config = { ...configuration, upstream: configuration.upstream ? new URL(configuration.upstream) : null };
  const server = http.createServer({ requestTimeout: 30000, headersTimeout: 10000 }, (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=()');
    res.setHeader('Content-Security-Policy', "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'none'");
    if (req.url === '/health' || req.url === '/ready') {
      res.setHeader('Content-Type', 'application/json');
      res.end('{"status":"UP"}'); return;
    }
    // Reject encoded delimiters/dot segments before URL normalization or backend decoding.
    if (/%(?:2f|5c|2e|25)/i.test(req.url.split('?')[0])) { res.writeHead(404).end(); return; }
    let url;
    try { url = new URL(req.url, 'http://ui.invalid'); } catch { res.writeHead(400).end(); return; }
    if (config.prefix && url.pathname === config.prefix) {
      res.writeHead(308, { Location: `${config.prefix}/${url.search}` }).end(); return;
    }
    if (!url.pathname.startsWith(`${config.prefix}/`)) { res.writeHead(404).end(); return; }
    const local = url.pathname.slice(config.prefix.length);
    if (local === '/v1' || local.startsWith('/v1/') || local === '/backend-health' || local === '/backend-ready') {
      if (!config.upstream) {
        res.writeHead(503, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
        res.end('{"error":"UI_BACKEND_NOT_CONFIGURED","message":"Set RAVENROOT_UI_BACKEND_URL to an existing backend"}');
        return;
      }
      const suppliedAuth = [];
      for (let i = 0; i < req.rawHeaders.length; i += 2) {
        if (req.rawHeaders[i].toLowerCase() === 'authorization') suppliedAuth.push(req.rawHeaders[i + 1]);
      }
      if (suppliedAuth.length > 1 || (suppliedAuth.length === 1
          && !/^Bearer +[A-Za-z0-9._~+/-]+=*$/i.test(suppliedAuth[0]))) {
        res.writeHead(400, { 'Cache-Control': 'no-store' }).end('Invalid Authorization header'); return;
      }
      const api = local === '/v1' || local.startsWith('/v1/');
      const useDefault = api && !suppliedAuth.length && Boolean(defaultToken);
      if (useDefault && !browserMayUseDefault(req)) {
        res.writeHead(403, { 'Cache-Control': 'no-store' }).end('Browser origin refused'); return;
      }
      const path = local === '/backend-health' ? '/health' : local === '/backend-ready' ? '/ready' : local;
      const target = new URL(config.upstream);
      target.pathname = config.backendPrefix + path;
      target.search = url.search;
      const forwarded = headers(req.headers);
      forwarded.host = target.host;
      // Presence is decided BEFORE hop filtering. Connection: Authorization must not turn an
      // explicit (possibly rejected) personal identity into the shared operator identity.
      if (suppliedAuth.length) forwarded.authorization = suppliedAuth[0];
      else if (useDefault) forwarded.authorization = `Bearer ${defaultToken}`;
      if (defaultToken) forwarded['accept-encoding'] = 'identity';
      // Client-supplied proxy authority never becomes trusted upstream metadata.
      delete forwarded['forwarded'];
      for (const key of Object.keys(forwarded)) if (key.startsWith('x-forwarded-')) delete forwarded[key];
      const request = (target.protocol === 'https:' ? https : http).request(target,
        { method: req.method, headers: forwarded }, response => {
          clearTimeout(headerTimer);
          // Do not let a browser redirect become a second destination or expose upstream secrets
          // through Location. No request is retried with a different credential.
          if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
            response.resume(); res.writeHead(502, { 'Cache-Control': 'no-store' }).end('Backend redirect refused'); return;
          }
          if (defaultToken && response.headers['content-encoding']
              && response.headers['content-encoding'] !== 'identity') {
            response.resume(); res.writeHead(502).end('Unsupported backend response encoding'); return;
          }
          const replyHeaders = headers(response.headers);
          if (defaultToken) {
            delete replyHeaders['content-length'];
            for (const [key, value] of Object.entries(replyHeaders)) {
              const redact = item => String(item).split(defaultToken).join('[redacted]');
              replyHeaders[key] = Array.isArray(value) ? value.map(redact) : redact(value);
            }
          }
          replyHeaders['cache-control'] = 'no-store';
          res.writeHead(response.statusCode, replyHeaders);
          res.flushHeaders();
          if (defaultToken) {
            const filter = secretFilter(defaultToken);
            response.pipe(filter).pipe(res); filter.on('error', () => res.destroy());
          } else response.pipe(res);
          response.on('error', () => res.destroy());
        });
      const headerTimer = setTimeout(() => request.destroy(new Error('Backend header timeout')), config.headerTimeoutMs).unref();
      request.on('error', () => { clearTimeout(headerTimer); if (!res.headersSent) res.writeHead(502).end('Backend unavailable'); else res.destroy(); });
      req.on('aborted', () => request.destroy());
      res.on('close', () => { clearTimeout(headerTimer); request.destroy(); });
      req.pipe(request); return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405, { Allow: 'GET, HEAD' }).end(); return; }
    let decoded;
    try { decoded = decodeURIComponent(local); } catch { res.writeHead(400).end(); return; }
    const path = resolve(config.root, `.${decoded === '/' ? '/index.html' : decoded}`);
    if (!path.startsWith(config.root + '/') || decoded.includes('\0') || decoded.includes('\\')) {
      res.writeHead(404).end(); return;
    }
    let size;
    try { const stat = statSync(path); if (!stat.isFile()) throw new Error(); size = stat.size; }
    catch { res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', types[extname(path)] || 'application/octet-stream');
    res.setHeader('Cache-Control', path.endsWith('/index.html') ? 'no-store' : 'public, max-age=3600');
    if (path.endsWith('/index.html')) {
      const html = readFileSync(path, 'utf8').replace('id="service-url" value=""',
        `id="service-url" value="${config.prefix}" data-server-auth="${Boolean(defaultToken)}"`);
      res.setHeader('Content-Length', Buffer.byteLength(html));
      res.end(req.method === 'HEAD' ? undefined : html);
    } else {
      res.setHeader('Content-Length', size);
      if (req.method === 'HEAD') res.end(); else createReadStream(path).on('error', () => res.destroy()).pipe(res);
    }
  });
  server.maxConnections = 256;
  return server;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) {
  const config = configuration();
  const server = createServer(config);
  server.listen(config.port, '0.0.0.0');
  const stop = () => { server.close(); setTimeout(() => process.exit(0), 10000).unref(); };
  process.on('SIGTERM', stop); process.on('SIGINT', stop);
}
