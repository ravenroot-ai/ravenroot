/** Static UI and same-origin streaming proxy; no backend execution runtime. */
import http from 'node:http';
import https from 'node:https';
import { createReadStream, statSync, readFileSync } from 'node:fs';
import { resolve, extname } from 'node:path';

export function configuration(env = process.env) {
  const prefix = env.RAVENROOT_UI_PREFIX || '';
  if (prefix && !/^\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+$/.test(prefix)) {
    throw new Error('RAVENROOT_UI_PREFIX must be empty or a slash-prefixed path without trailing slash');
  }
  const upstream = new URL(env.RAVENROOT_UI_BACKEND_URL || 'http://127.0.0.1:8080');
  if (!['http:', 'https:'].includes(upstream.protocol) || upstream.username || upstream.password
      || upstream.search || upstream.hash || /%|\/\//.test(upstream.pathname)) {
    throw new Error('Backend URL must be HTTP(S), without credentials, query, fragment or encoded path');
  }
  if (env.NODE_TLS_REJECT_UNAUTHORIZED === '0') throw new Error('TLS verification cannot be disabled');
  const backendPrefix = upstream.pathname.replace(/\/$/, '');
  const port = Number(env.RAVENROOT_UI_PORT || 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid UI port');
  return { prefix, upstream, backendPrefix, port, headerTimeoutMs: 15000, root: resolve(env.RAVENROOT_UI_ROOT || '/opt/ravenroot/ui') };
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

export function createServer(config) {
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
      const path = local === '/backend-health' ? '/health' : local === '/backend-ready' ? '/ready' : local;
      const target = new URL(config.upstream);
      target.pathname = config.backendPrefix + path;
      target.search = url.search;
      const forwarded = headers(req.headers);
      forwarded.host = target.host;
      // Client-supplied proxy authority never becomes trusted upstream metadata.
      delete forwarded['forwarded'];
      for (const key of Object.keys(forwarded)) if (key.startsWith('x-forwarded-')) delete forwarded[key];
      const request = (target.protocol === 'https:' ? https : http).request(target,
        { method: req.method, headers: forwarded }, response => {
          clearTimeout(headerTimer);
          res.writeHead(response.statusCode, headers(response.headers));
          res.flushHeaders();
          response.pipe(res); // no buffering: SSE bytes flow as the backend emits them
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
        `id="service-url" value="${config.prefix}"`);
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
