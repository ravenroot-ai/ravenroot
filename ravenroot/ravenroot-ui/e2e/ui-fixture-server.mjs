import { createReadStream, existsSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

import { SERVICE_ORIGIN, UI_PORT } from './ports.mjs';

const root = fileURLToPath(new URL('../dist/', import.meta.url));
const MIME_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
]);
// The one origin the page may reach across a boundary is DERIVED from the service knob. Pinning it
// as a literal while the port moved is what made the CSP and the service disagree by construction.
const CSP = "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
  + "form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
  + `connect-src 'self' ${SERVICE_ORIGIN}`;

createServer(async (request, response) => {
  const pathname = new URL(request.url, 'http://127.0.0.1').pathname;
  if (pathname === '/v1/configuration') {
    const body = JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 });
    response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Security-Policy': CSP,
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(body),
    });
    response.end(body);
    return;
  }
  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const file = normalize(join(root, relative));
  if (!file.startsWith(root) || !existsSync(file) || !(await stat(file)).isFile()) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Content-Security-Policy': CSP });
    response.end('not found');
    return;
  }
  response.writeHead(200, {
    'Content-Type': MIME_TYPES.get(extname(file)) || 'application/octet-stream',
    'Content-Security-Policy': CSP,
    'Cache-Control': 'no-store',
  });
  createReadStream(file).pipe(response);
}).listen(UI_PORT, '127.0.0.1');
