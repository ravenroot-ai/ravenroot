import { createReadStream } from 'node:fs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';

const port = Number.parseInt(process.env.RR_EMBED_SHELL_TEST_PORT ?? '4317', 10);
if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
  throw new Error('RR_EMBED_SHELL_TEST_PORT must be a valid port.');
}

const lifecycleModule = fileURLToPath(new URL('../src/embed-shell-lifecycle.js', import.meta.url));
const routeBudgetModule = fileURLToPath(new URL('../src/viewer-route-budget.js', import.meta.url));
const edgeRouteModule = fileURLToPath(new URL('../src/renderer-edge-route.js', import.meta.url));

const parentHtml = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Embed shell lifecycle fixture</title></head><body>
<button id="parent-before" type="button">Before viewer</button>
<iframe id="viewer-frame" title="Read-only embedded graph" src="/viewer"></iframe>
<button id="parent-after" type="button">After viewer</button>
</body></html>`;

const viewerHtml = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Read-only graph</title><style>
:root { color-scheme: dark; font-family: system-ui, sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; color: #f0f6fc; background: #0d1117; }
.focus-boundary { position: fixed; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
main { min-height: 20rem; padding: 1rem; }
h1, h2, p { margin-block: 0 .75rem; }
.controls { display: flex; gap: .5rem; flex-wrap: wrap; }
button { min-width: 48px; min-height: 48px; padding: .65rem; border: 2px solid #8c959f;
  border-radius: .4rem; color: #f0f6fc; background: #21262d; }
button:focus-visible { outline: 3px solid #58a6ff; outline-offset: 2px; }
.canvas { min-height: 8rem; margin-block: 1rem; border: 1px solid #8c959f; padding: 1rem; }
.alternative { overflow-wrap: anywhere; }
@media (max-width: 420px) { main { padding: .75rem; } .controls button { flex: 1 1 8rem; } }
@media (pointer: coarse) { button { min-width: 48px; min-height: 48px; } }
@media print {
  :root, body { color: #111; background: #fff; }
  .controls, [data-viewer-status], .focus-boundary, .canvas { display: none; }
  .alternative { display: block; }
}
</style></head><body>
<span id="focus-before" class="focus-boundary"></span>
<main id="shell" aria-labelledby="title">
  <h1 id="title">Read-only graph</h1>
  <div class="controls" aria-label="Graph controls">
    <button id="local-action" type="button">Fit graph</button>
  </div>
  <div class="canvas" role="img" aria-label="Graph visualization">Static graph surface</div>
  <section class="alternative" aria-labelledby="alternative-title">
    <h2 id="alternative-title">Graph contents</h2><ol id="alternative"></ol>
  </section>
  <p id="status" data-viewer-status></p>
</main>
<span id="focus-after" class="focus-boundary"></span>
<script type="module" src="/embed-shell-lifecycle-fixture-client.js"></script></body></html>`;

createServer((request, response) => {
  const pathname = new URL(request.url, `http://127.0.0.1:${port}`).pathname;
  const headers = {
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'self'; style-src 'unsafe-inline'; script-src 'self'",
  };
  if (pathname === '/healthz') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('ready');
    return;
  }
  if (pathname === '/embed-shell-lifecycle.js') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' });
    createReadStream(lifecycleModule).pipe(response);
    return;
  }
  if (pathname === '/viewer-route-budget.js') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' });
    createReadStream(routeBudgetModule).pipe(response);
    return;
  }
  if (pathname === '/renderer-edge-route.js') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' });
    createReadStream(edgeRouteModule).pipe(response);
    return;
  }
  if (pathname === '/embed-shell-lifecycle-fixture-client.js') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' });
    createReadStream(fileURLToPath(new URL('./embed-shell-lifecycle-fixture-client.js', import.meta.url)))
      .pipe(response);
    return;
  }
  if (pathname === '/viewer') {
    response.writeHead(200, { ...headers, 'Content-Type': 'text/html; charset=utf-8' });
    response.end(viewerHtml);
    return;
  }
  response.writeHead(200, { ...headers, 'Content-Type': 'text/html; charset=utf-8' });
  response.end(parentHtml);
}).listen(port, '127.0.0.1');
