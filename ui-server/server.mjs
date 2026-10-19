/** Static UI and same-origin streaming proxy; no backend execution runtime. */
import http from 'node:http';
import https from 'node:https';
import { createReadStream, statSync, readFileSync } from 'node:fs';
import { resolve, extname } from 'node:path';
import { Transform } from 'node:stream';

const backendTokens = new WeakMap();

function punycodeDigit(value) {
  if (value >= 'a' && value <= 'z') return value.charCodeAt(0) - 97;
  if (value >= '0' && value <= '9') return value.charCodeAt(0) - 22;
  return -1;
}

function adaptPunycodeBias(delta, outputSize, firstCodePoint) {
  delta = firstCodePoint ? Math.floor(delta / 700) : Math.floor(delta / 2);
  delta += Math.floor(delta / outputSize);
  let adjustment = 0;
  while (delta > 455) {
    delta = Math.floor(delta / 35);
    adjustment += 36;
  }
  return adjustment + Math.floor(36 * delta / (delta + 38));
}

function validPunycodeLabel(encoded) {
  if (!encoded) return false;
  const decoded = [];
  const delimiter = encoded.lastIndexOf('-');
  let cursor = 0;
  if (delimiter >= 0) {
    for (; cursor < delimiter; cursor += 1) decoded.push(encoded.charCodeAt(cursor));
    cursor += 1;
  }
  let codePoint = 128;
  let delta = 0;
  let bias = 72;
  while (cursor < encoded.length) {
    const previousDelta = delta;
    let weight = 1;
    for (let thresholdBase = 36; ; thresholdBase += 36) {
      if (cursor >= encoded.length) return false;
      const digit = punycodeDigit(encoded[cursor++]);
      if (digit < 0 || digit > Math.floor((Number.MAX_SAFE_INTEGER - delta) / weight)) return false;
      delta += digit * weight;
      const threshold = thresholdBase <= bias ? 1 : Math.min(thresholdBase - bias, 26);
      if (digit < threshold) break;
      if (weight > Math.floor(Number.MAX_SAFE_INTEGER / (36 - threshold))) return false;
      weight *= 36 - threshold;
    }
    const outputSize = decoded.length + 1;
    bias = adaptPunycodeBias(delta - previousDelta, outputSize, previousDelta === 0);
    const increment = Math.floor(delta / outputSize);
    if (increment > 0x10ffff - codePoint) return false;
    codePoint += increment;
    delta %= outputSize;
    if (codePoint <= 0x9f || (codePoint >= 0xd800 && codePoint <= 0xdfff)
        || (codePoint >= 0xfdd0 && codePoint <= 0xfdef) || (codePoint & 0xfffe) === 0xfffe) return false;
    decoded.splice(delta, 0, codePoint);
    delta += 1;
  }
  return decoded.some(value => value >= 0x80);
}

function configuredFrameAncestors(declared) {
  if (declared === undefined || declared === null || String(declared).trim() === '') return [];
  const ancestors = new Set();
  for (const item of String(declared).split(',')) {
    const value = item.trim();
    if (!value) throw new Error('RAVENROOT_UI_FRAME_ANCESTORS must not contain empty entries');
    if (value === 'self') {
      ancestors.add("'self'");
      continue;
    }
    let origin;
    try { origin = new URL(value); }
    catch { throw new Error('RAVENROOT_UI_FRAME_ANCESTORS entries must be self or canonical HTTP(S) origins'); }
    if (origin.hostname.startsWith('[')) {
      throw new Error('RAVENROOT_UI_FRAME_ANCESTORS does not support explicit IPv6 origins because CSP cannot represent them; use self for same-origin framing or a DNS hostname');
    }
    // Each nonempty DNS label must use only the characters CSP host-sources can represent.
    const cspHostname = /^[a-z0-9-]+(?:\.[a-z0-9-]+)*$/.test(origin.hostname);
    // Node URL releases differ on malformed A-labels, so enforce the shared decoder explicitly.
    const validPunycode = origin.hostname.split('.').every(label => !label.startsWith('xn--')
      || validPunycodeLabel(label.substring(4)));
    if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password
        || origin.pathname !== '/' || origin.search || origin.hash || origin.port === '0'
        || origin.hostname.endsWith('.') || !cspHostname || !validPunycode || origin.origin !== value) {
      throw new Error('RAVENROOT_UI_FRAME_ANCESTORS entries must be self or canonical HTTP(S) origins with CSP-compatible DNS or IPv4 hosts');
    }
    ancestors.add(value);
  }
  return [...ancestors].sort();
}

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
  const config = { prefix, upstream, backendPrefix, port, headerTimeoutMs: 15000,
    root: resolve(env.RAVENROOT_UI_ROOT || '/opt/ravenroot/ui'),
    frameAncestors: configuredFrameAncestors(env.RAVENROOT_UI_FRAME_ANCESTORS) };
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
  const config = { ...configuration, upstream: configuration.upstream ? new URL(configuration.upstream) : null,
    frameAncestors: [...configuration.frameAncestors] };
  const frameAncestorPolicy = config.frameAncestors.length ? config.frameAncestors.join(' ') : "'none'";
  const pluginFrameAncestorPolicy = [...new Set(["'self'", ...config.frameAncestors])].join(' ');
  const commonCsp = `default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors ${frameAncestorPolicy}; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'none'`;
  const server = http.createServer({ requestTimeout: 30000, headersTimeout: 10000 }, (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    if (!config.frameAncestors.length) res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=()');
    res.setHeader('Content-Security-Policy', commonCsp);
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
          // A backend response with its own framing contract remains authoritative. This is
          // required for the separately authorized embedded viewer, whose CSP names one registered
          // parent and deliberately omits X-Frame-Options.
          if (replyHeaders['x-frame-options'] === undefined
              && String(replyHeaders['content-security-policy'] || '').includes('frame-ancestors ')) {
            res.removeHeader('X-Frame-Options');
          }
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
    if (path.endsWith('/index.html')) {
      // The Workbench owns one same-origin nested document: the opaque plugin sandbox below.
      res.setHeader('Content-Security-Policy', commonCsp.replace("frame-src 'none'", "frame-src 'self'"));
    } else if (path.endsWith('/frontend-plugin-sandbox.html')) {
      // Every ancestor must satisfy CSP, so a framed Workbench's validated ancestors are carried
      // through alongside self. The response sandbox keeps the document opaque even if an allowed
      // portal addresses it directly; every network and nesting channel remains closed.
      res.removeHeader('X-Frame-Options');
      res.setHeader('Content-Security-Policy', `default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors ${pluginFrameAncestorPolicy}; form-action 'none'; script-src 'self' blob:; style-src 'none'; img-src 'none'; connect-src 'none'; frame-src 'none'; worker-src 'none'; sandbox allow-scripts`);
    }
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
