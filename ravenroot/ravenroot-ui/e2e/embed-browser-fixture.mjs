import { execFileSync } from 'node:child_process';
import { request as httpRequest } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const requiredOrigin = (name) => {
  const value = process.env[name];
  if (value === undefined || !/^https:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/u.test(value)) {
    throw new Error(`${name} must be an exact fixture HTTPS origin`);
  }
  return new URL(value);
};

const backend = new URL(process.env.RR_EMBED_BACKEND_ORIGIN ?? '');
if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1') {
  throw new Error('RR_EMBED_BACKEND_ORIGIN must be the loopback Ravenroot fixture server');
}
const parentOrigin = requiredOrigin('RR_EMBED_PARENT_ORIGIN');
const viewerOrigin = requiredOrigin('RR_EMBED_VIEWER_ORIGIN');
const foreignOrigin = requiredOrigin('RR_EMBED_FOREIGN_ORIGIN');

const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ravenroot-embed-browser-'));
const keyPath = join(temporaryDirectory, 'fixture-key.pem');
const certificatePath = join(temporaryDirectory, 'fixture-cert.pem');
execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes',
  '-keyout', keyPath, '-out', certificatePath, '-days', '1', '-subj', '/CN=127.0.0.1',
  '-addext', 'subjectAltName=IP:127.0.0.1'], { stdio: 'ignore' });
const tls = { key: readFileSync(keyPath), cert: readFileSync(certificatePath) };

const createSession = registrationId => new Promise((resolve, reject) => {
  const creationBody = JSON.stringify({ registrationId });
  const request = httpRequest({
    hostname: backend.hostname,
    port: backend.port,
    method: 'POST',
    path: '/v1/embed/sessions',
    headers: {
      Authorization: 'Bearer browser-workload',
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(creationBody),
    },
  }, (response) => {
    const chunks = [];
    response.on('data', (chunk) => chunks.push(chunk));
    response.on('end', () => resolve({
      status: response.statusCode,
      body: Buffer.concat(chunks).toString('utf8'),
    }));
  });
  request.on('error', reject);
  request.end(creationBody);
}).then(created => {
  if (created.status !== 201) {
    throw new Error(`Ravenroot fixture session creation failed for ${registrationId} (${created.status})`);
  }
  const creation = JSON.parse(created.body);
  if (typeof creation.launchUrl !== 'string' || !creation.launchUrl.startsWith(viewerOrigin.origin)) {
    throw new Error('Ravenroot fixture returned an unexpected launch origin');
  }
  return creation.launchUrl;
});
const invalidLaunchPaths = new Set();

const acknowledgeAtBackend = (hello) => new Promise((resolve, reject) => {
  const body = JSON.stringify({
    registrationId: 'browser-registration',
    acknowledgementId: hello.acknowledgementId,
    channelId: hello.channelId,
    correlationId: hello.correlationId,
  });
  const request = httpRequest({
    hostname: backend.hostname,
    port: backend.port,
    method: 'POST',
    path: '/v1/embed/acknowledgements',
    headers: {
      Authorization: 'Bearer browser-workload',
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
    },
  }, (response) => {
    response.resume();
    response.on('end', () => resolve(response.statusCode));
  });
  request.on('error', reject);
  request.end(body);
});

const observations = [];
const proxy = (request, response) => {
  const isEmbedPost = request.method === 'POST'
    && (request.url === '/v1/embed/exchange' || request.url === '/v1/embed/projection');
  if (isEmbedPost) {
    observations.push({
      path: request.url,
      cookiePresent: request.headers.cookie !== undefined,
      refererPresent: request.headers.referer !== undefined,
      originExact: request.headers.origin === viewerOrigin.origin,
      secFetchSite: request.headers['sec-fetch-site'] ?? null,
      bearerPresent: request.url === '/v1/embed/projection'
        && /^Bearer [A-Za-z0-9_-]+$/u.test(request.headers.authorization ?? ''),
    });
  }
  const headers = { ...request.headers, host: backend.host };
  delete headers.connection;
  const upstream = httpRequest({
    hostname: backend.hostname,
    port: backend.port,
    method: request.method,
    path: request.url,
    headers,
  }, (upstreamResponse) => {
    if (invalidLaunchPaths.delete(request.url)) {
      const chunks = [];
      upstreamResponse.on('data', chunk => chunks.push(chunk));
      upstreamResponse.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8').replace('"theme":null', '"theme":"auto"');
        const responseHeaders = { ...upstreamResponse.headers, 'content-length': Buffer.byteLength(body) };
        response.writeHead(upstreamResponse.statusCode ?? 502, responseHeaders);
        response.end(body);
      });
      return;
    }
    response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers);
    upstreamResponse.pipe(response);
  });
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502, { 'Content-Type': 'text/plain' });
    response.end('fixture upstream unavailable');
  });
  request.pipe(upstream);
};

const parentHtml = (viewerLaunchUrl) => `<!doctype html><html><head><meta charset="utf-8"><meta name="referrer"
content="no-referrer"><title>Ravenroot embed boundary fixture</title></head><body>
<iframe id="viewer" name="viewer" width="800" height="500"
sandbox="allow-scripts allow-same-origin" referrerpolicy="no-referrer"
src=${JSON.stringify(viewerLaunchUrl)}></iframe>
<iframe id="foreign" name="foreign" hidden src=${JSON.stringify(`${foreignOrigin.origin}/`)}></iframe>
<iframe id="sibling" name="sibling" hidden src="/sibling"></iframe>
<script>
(() => {
  'use strict';
  const viewer = document.getElementById('viewer');
  const expectedKeys = ['channelId', 'correlationId', 'direction', 'protocolVersion', 'type'];
  const helloKeys = ['acknowledgementId', ...expectedKeys].sort();
  window.embedMessages = [];
  window.embedHello = null;
  addEventListener('message', (event) => {
    const message = event.data;
    if (event.source !== viewer.contentWindow || event.origin !== ${JSON.stringify(viewerOrigin.origin)}
        || event.ports.length !== 0 || message === null || typeof message !== 'object'
        || Array.isArray(message)) return;
    const keys = Object.keys(message).sort();
    if (keys.join(',') === helloKeys.join(',')
        && message.protocolVersion === 'ravenroot.embed/1'
        && message.direction === 'viewer-to-parent' && message.type === 'HELLO'
        && typeof message.channelId === 'string' && typeof message.correlationId === 'string'
        && typeof message.acknowledgementId === 'string') {
      window.embedHello = message;
      return;
    }
    if (keys.join(',') !== expectedKeys.join(',')
        || message.protocolVersion !== 'ravenroot.embed/1'
        || message.direction !== 'viewer-to-parent'
        || !['READY', 'FAILED', 'PONG'].includes(message.type)
        || typeof message.channelId !== 'string' || typeof message.correlationId !== 'string') return;
    window.embedMessages.push(message);
  });
  window.acknowledgeBackend = async () => {
    const hello = window.embedHello;
    if (hello === null) throw new Error('HELLO unavailable');
    const response = await fetch('/__embed-ack', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(hello),
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    });
    return response.status;
  };
  window.postViewerAcknowledgement = (overrides = {}) => {
    const hello = window.embedHello;
    if (hello === null) throw new Error('HELLO unavailable');
    viewer.contentWindow.postMessage({
      protocolVersion: hello.protocolVersion,
      channelId: hello.channelId,
      correlationId: hello.correlationId,
      direction: 'parent-to-viewer',
      type: 'ACK',
      ...overrides,
    }, ${JSON.stringify(viewerOrigin.origin)});
  };
})();
</script></body></html>`;

const failureViewerHtml = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="referrer" content="no-referrer"><link rel="stylesheet" href="/embed-viewer.css">
<title>Embed failure cleanup fixture</title></head><body>
<span class="embed-focus-sentinel" tabindex="0"></span>
<main id="ravenroot-embed-viewer" class="embed-viewer" data-viewer-state="loading">
<header class="embed-viewer-header"><p data-viewer-metadata></p><nav class="embed-viewer-controls">
<select data-viewer-mode><option value="cyto">Cyto</option><option value="n8n">N8N</option>
<option value="elastic">Elastic</option></select><button type="button" data-viewer-command="fit">Fit</button>
</nav></header><div class="embed-viewer-canvas" data-viewer-canvas tabindex="0"></div>
<canvas class="embed-viewer-minimap" data-viewer-minimap tabindex="0"></canvas>
<ol class="embed-viewer-alternative" data-viewer-alternative></ol>
<p class="embed-viewer-status" data-viewer-status></p></main>
<span class="embed-focus-sentinel" tabindex="0"></span>
<script type="module" src="/failure-driver.js"></script></body></html>`;

const failureDriver = `
const evidence = { observers: 0, disconnected: 0, listenerAdds: 0, listenerRemoves: 0 };
const NativeResizeObserver = window.ResizeObserver;
window.ResizeObserver = class {
  constructor(callback) { evidence.observers += 1; this.inner = new NativeResizeObserver(callback); }
  observe(...args) { return this.inner.observe(...args); }
  unobserve(...args) { return this.inner.unobserve(...args); }
  disconnect() { evidence.disconnected += 1; return this.inner.disconnect(); }
};
const nativeRequestAnimationFrame = window.requestAnimationFrame.bind(window);
const nativeCancelAnimationFrame = window.cancelAnimationFrame.bind(window);
const pendingFrames = new Set();
let requestedFrames = 0;
let cancelledFrames = 0;
let executedFrames = 0;
window.requestAnimationFrame = callback => {
  requestedFrames += 1;
  const id = nativeRequestAnimationFrame(timestamp => {
    pendingFrames.delete(id);
    executedFrames += 1;
    callback(timestamp);
  });
  pendingFrames.add(id);
  return id;
};
window.cancelAnimationFrame = id => {
  if (pendingFrames.delete(id)) cancelledFrames += 1;
  return nativeCancelAnimationFrame(id);
};
const tracked = [...document.querySelectorAll('[data-viewer-canvas], [data-viewer-minimap], '
  + '[data-viewer-mode], [data-viewer-command]')];
for (const target of tracked) {
  const add = target.addEventListener.bind(target);
  const remove = target.removeEventListener.bind(target);
  target.addEventListener = (...args) => { evidence.listenerAdds += 1; return add(...args); };
  target.removeEventListener = (...args) => { evidence.listenerRemoves += 1; return remove(...args); };
}
window.__failureEvidence = (async () => {
  const { createEmbedViewer } = await import('/embed-viewer.js');
  const viewer = createEmbedViewer(document.getElementById('ravenroot-embed-viewer'));
  const controller = new AbortController();
  const mounting = viewer.mount({ viewerContractVersion: '1.0', graphId: 'fixture',
    graphVersionId: 'version', canonicalDigest: 'digest',
    nodes: [{ id: 'start', kind: 'START', layout: { x: 80, y: 80, width: 84, height: 84 } }],
    edges: [] }, { signal: controller.signal });
  // Let the renderer register its first readiness frame, then abort before a browser frame runs.
  await Promise.resolve();
  controller.abort(new Error('forced fixture abort'));
  try {
    await mounting;
  } catch {}
  const immediateFrames = {
    requested: requestedFrames,
    cancelled: cancelledFrames,
    executed: executedFrames,
    pending: pendingFrames.size,
  };
  await new Promise(resolve => setTimeout(resolve, 100));
  const result = {
    ...evidence,
    state: viewer.state,
    rootState: document.getElementById('ravenroot-embed-viewer').dataset.viewerState,
    status: document.querySelector('[data-viewer-status]').textContent,
    immediateFrames,
    pendingFrames: pendingFrames.size,
    executedFrames,
    elasticPresent: document.querySelector('.embed-viewer-elastic') !== null,
    alternativeChildren: document.querySelector('[data-viewer-alternative]').children.length,
    focusBoundaries: document.querySelectorAll('[data-embed-focus-boundary]').length,
  };
  await new Promise(resolve => setTimeout(resolve, 100));
  result.lateState = viewer.state;
  return result;
})();`;

const parentServer = createHttpsServer(tls, async (request, response) => {
  if (request.url === '/healthz') {
    response.writeHead(200, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    response.end('ready');
    return;
  }
  if (request.url === '/__observations') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(observations));
    return;
  }
  if (request.url === '/__embed-ack' && request.method === 'POST') {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', async () => {
      try {
        const hello = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        const keys = Object.keys(hello).sort().join(',');
        if (keys !== 'acknowledgementId,channelId,correlationId,direction,protocolVersion,type'
            || hello.protocolVersion !== 'ravenroot.embed/1'
            || hello.direction !== 'viewer-to-parent' || hello.type !== 'HELLO') {
          response.writeHead(400, { 'Cache-Control': 'no-store' });
          response.end();
          return;
        }
        const status = await acknowledgeAtBackend(hello);
        response.writeHead(status, { 'Cache-Control': 'no-store' });
        response.end();
      } catch {
        response.writeHead(503, { 'Cache-Control': 'no-store' });
        response.end();
      }
    });
    return;
  }
  if (request.url === '/sibling') {
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': `default-src 'none'; script-src 'unsafe-inline'`,
    });
    response.end(`<!doctype html><html><body><script>
    addEventListener('message', (event) => {
      if (event.source !== parent || event.origin !== ${JSON.stringify(parentOrigin.origin)}
          || event.data?.command !== 'CONTACT_VIEWER') return;
      parent.frames.viewer.postMessage(event.data.message, ${JSON.stringify(viewerOrigin.origin)});
    });
    </script></body></html>`);
    return;
  }
  const registration = new Map([
    ['/theme-light', 'theme-light'], ['/theme-dark', 'theme-dark'],
    ['/theme-auto-light', 'theme-auto-light'], ['/theme-auto-dark', 'theme-auto-dark'],
    ['/theme-invalid', 'theme-invalid'],
  ]).get(request.url) ?? 'browser-registration';
  try {
    const freshLaunchUrl = await createSession(registration);
    if (registration === 'theme-invalid') {
      const invalid = new URL(freshLaunchUrl);
      invalidLaunchPaths.add(invalid.pathname + invalid.search);
    }
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': `default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'; frame-src ${parentOrigin.origin} ${viewerOrigin.origin} ${foreignOrigin.origin}`,
    });
    response.end(parentHtml(freshLaunchUrl));
  } catch {
    response.writeHead(503, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
    response.end('fixture session unavailable');
  }
});

const viewerServer = createHttpsServer(tls, (request, response) => {
  if (request.url === '/failure-viewer') {
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': `default-src 'none'; script-src 'self'; style-src 'self'; frame-ancestors ${parentOrigin.origin}`,
    });
    response.end(failureViewerHtml);
    return;
  }
  if (request.url === '/failure-driver.js') {
    response.writeHead(200, {
      'Content-Type': 'text/javascript; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    response.end(failureDriver);
    return;
  }
  proxy(request, response);
});

const foreignServer = createHttpsServer(tls, (request, response) => {
  response.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': `default-src 'none'; script-src 'unsafe-inline'`,
  });
  response.end(`<!doctype html><html><body><script>
  addEventListener('message', (event) => {
    if (event.source !== parent || event.origin !== ${JSON.stringify(parentOrigin.origin)}
        || event.data?.command !== 'CONTACT_VIEWER') return;
    parent.frames.viewer.postMessage(event.data.message, ${JSON.stringify(viewerOrigin.origin)});
  });
  </script></body></html>`);
});

const listen = (server, url) => new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(Number(url.port), '127.0.0.1', resolve);
});
await Promise.all([
  listen(parentServer, parentOrigin),
  listen(viewerServer, viewerOrigin),
  listen(foreignServer, foreignOrigin),
]);

let stopping = false;
const shutdown = async (exitCode) => {
  if (stopping) return;
  stopping = true;
  await Promise.all([parentServer, viewerServer, foreignServer].map((server) =>
    new Promise((resolve) => server.close(resolve))));
  rmSync(temporaryDirectory, { recursive: true, force: true });
  process.exit(exitCode);
};
process.once('SIGTERM', () => void shutdown(0));
process.once('SIGINT', () => void shutdown(130));
process.once('uncaughtException', () => void shutdown(1));
