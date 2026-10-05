import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { configuration, createServer } from './server.mjs';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}
async function stop(server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }

test('validates runtime configuration, refuses credentials and insecure TLS', () => {
  for (const value of ['http://user:secret@backend', 'ftp://backend', 'https://backend/a%2fb', 'https://backend?a=b']) {
    assert.throws(() => configuration({ RAVENROOT_UI_BACKEND_URL: value }));
  }
  for (const value of ['/a/', '/a/../b', '/a"', '//a']) assert.throws(() => configuration({ RAVENROOT_UI_PREFIX: value }));
  assert.throws(() => configuration({ NODE_TLS_REJECT_UNAUTHORIZED: '0' }));
});

for (const [prefix, backendPrefix] of [['', ''], ['/workspace/ui', '/embedded/ravenroot']]) test(`real HTTP/static/API/incremental SSE, UI prefix ${prefix || '/'}`, async () => {
  const root = mkdtempSync(join(tmpdir(), 'ravenroot-ui-test-'));
  writeFileSync(join(root, 'index.html'), '<input id="service-url" value=""><script src="./app.js"></script>');
  writeFileSync(join(root, 'app.js'), 'export const ui = true;');
  let seen;
  let finishStream;
  const backend = http.createServer((req, res) => {
    seen = { path: req.url, auth: req.headers.authorization, host: req.headers.host,
      spoof: req.headers['x-forwarded-host'], hop: req.headers['x-hop'] };
    if (req.url.includes('/events')) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write('data: first\n\n');
      finishStream = () => res.end('data: second\n\n');
    } else {
      let body = ''; req.on('data', chunk => body += chunk);
      req.on('end', () => { res.writeHead(req.headers.authorization ? 200 : 401); res.end(body || 'backend'); });
    }
  });
  const origin = (await listen(backend)).replace('127.0.0.1', 'localhost');
  const server = createServer(configuration({ RAVENROOT_UI_ROOT: root, RAVENROOT_UI_PREFIX: prefix,
    RAVENROOT_UI_BACKEND_URL: `${origin}${backendPrefix}` }));
  const ui = await listen(server);
  try {
    assert.equal((await fetch(ui + '/health')).status, 200);
    const html = await (await fetch(ui + prefix + '/')).text();
    assert.ok(html.includes(`value="${prefix}"`)); assert.ok(!html.includes(origin));
    assert.equal((await fetch(ui + prefix + '/app.js')).headers.get('content-type'), 'text/javascript; charset=utf-8');
    for (const path of ['/missing', '/%2e%2e%2fserver.mjs', '/%00', '/%5cserver.mjs']) {
      assert.equal((await fetch(ui + prefix + path)).status, 404);
    }
    assert.equal((await fetch(ui + prefix + '/v1/runtime')).status, 401);
    const reply = await fetch(ui + prefix + '/v1/run?x=one%20two', { method: 'POST', body: 'payload',
      headers: { Authorization: 'Bearer test-token', 'X-Forwarded-Host': 'evil.invalid' } });
    assert.equal(await reply.text(), 'payload');
    assert.deepEqual(seen, { path: `${backendPrefix}/v1/run?x=one%20two`, auth: 'Bearer test-token',
      host: new URL(origin).host, spoof: undefined, hop: undefined });
    const stream = await fetch(ui + prefix + '/v1/events', { headers: { Authorization: 'Bearer test-token' } });
    const reader = stream.body.getReader();
    const first = await Promise.race([reader.read(), new Promise((_, reject) => setTimeout(() => reject(new Error('SSE buffered')), 2000).unref())]);
    assert.equal(new TextDecoder().decode(first.value), 'data: first\n\n');
    finishStream();
    const second = await reader.read();
    assert.equal(new TextDecoder().decode(second.value), 'data: second\n\n');
    assert.ok((await reader.read()).done);
    // Connection-nominated hop header is stripped, even if supplied by a raw client.
    await new Promise((resolve, reject) => {
      http.get(ui + prefix + '/v1/runtime', { headers: { Connection: 'x-hop', 'X-Hop': 'drop' } }, res => { res.resume(); res.on('end', resolve); }).on('error', reject);
    });
    assert.equal(seen.hop, undefined);
  } finally { await stop(server); await stop(backend); rmSync(root, { recursive: true }); }
});

test('HTTPS upstream rejects an untrusted certificate and accepts an operator CA', async () => {
  const root = mkdtempSync(join(tmpdir(), 'ravenroot-ui-tls-'));
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', join(root, 'key'),
    '-out', join(root, 'cert'), '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], { stdio: 'ignore' });
  let contacted = false;
  const backend = https.createServer({ key: readFileSync(join(root, 'key')), cert: readFileSync(join(root, 'cert')) }, (_req, res) => { contacted = true; res.end('trusted'); });
  await listen(backend);
  const server = createServer(configuration({ RAVENROOT_UI_ROOT: root,
    RAVENROOT_UI_BACKEND_URL: `https://localhost:${backend.address().port}` }));
  const ui = await listen(server);
  try {
    assert.equal((await fetch(ui + '/v1/runtime', { headers: { Authorization: 'Bearer secret' } })).status, 502);
    assert.equal(contacted, false);
    const child = spawn(process.execPath, ['--input-type=module', '-e',
      `import { configuration, createServer } from './ui-server/server.mjs';
       const server=createServer(configuration()); server.listen(0,'127.0.0.1',()=>console.log(server.address().port));`],
      { env: { ...process.env, NODE_EXTRA_CA_CERTS: join(root, 'cert'),
        RAVENROOT_UI_BACKEND_URL: `https://localhost:${backend.address().port}`, RAVENROOT_UI_ROOT: root } });
    try {
      const port = await new Promise((resolve, reject) => {
        child.stdout.once('data', data => resolve(data.toString().trim()));
        child.once('error', reject); child.once('exit', () => reject(new Error('TLS test server exited')));
      });
      const reply = await fetch(`http://127.0.0.1:${port}/v1/runtime`, { headers: { Authorization: 'Bearer test' } });
      assert.equal(reply.status, 200); assert.equal(await reply.text(), 'trusted'); assert.equal(contacted, true);
    } finally { child.kill(); await new Promise(resolve => child.once('exit', resolve)); }
  } finally { await stop(server); await stop(backend); rmSync(root, { recursive: true }); }
});


test('bounded header wait and disconnect release upstream sockets without closing idle SSE', async () => {
  let closed;
  const disconnected = new Promise(resolve => closed = resolve);
  const backend = http.createServer((req, res) => {
    if (req.url === '/v1/stalled') return;
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write('data: open\n\n');
    res.once('close', closed);
  });
  const origin = await listen(backend);
  const config = configuration({ RAVENROOT_UI_BACKEND_URL: origin });
  config.headerTimeoutMs = 50;
  const server = createServer(config);
  const ui = await listen(server);
  try {
    assert.equal((await fetch(ui + '/v1/stalled')).status, 502);
    const controller = new AbortController();
    const response = await fetch(ui + '/v1/events', { signal: controller.signal });
    const reader = response.body.getReader();
    assert.equal(new TextDecoder().decode((await reader.read()).value), 'data: open\n\n');
    await new Promise(resolve => setTimeout(resolve, 100)); // Headers arrived: no idle-SSE timeout.
    controller.abort();
    await Promise.race([disconnected, new Promise((_, reject) => setTimeout(() => reject(new Error('Upstream socket leaked')), 2000).unref())]);
  } finally { await stop(server); await stop(backend); }
});
