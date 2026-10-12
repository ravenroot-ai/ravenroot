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
  const sentinel = 'diagnostic-sentinel';
  let failure;
  try { configuration({ RAVENROOT_UI_BACKEND_URL: `http://user:${sentinel}@` }); }
  catch (error) { failure = error; }
  assert.match(failure?.message, /UI_BACKEND_CONFIGURATION_REFUSED/);
  const diagnostic = JSON.stringify({ message: failure.message, stack: failure.stack, ...failure });
  assert.ok(!diagnostic.includes(sentinel));
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
    assert.ok(html.includes(`value="${prefix}"`)); assert.ok(html.includes('data-server-auth="false"')); assert.ok(!html.includes(origin));
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


test('unset upstream serves static UI safely and returns explicit 503 for every backend route', async () => {
  assert.equal(configuration({}).upstream, null);
  assert.equal(configuration({ RAVENROOT_UI_BACKEND_URL: '   ' }).upstream, null);
  const root = mkdtempSync(join(tmpdir(), 'ravenroot-ui-static-'));
  writeFileSync(join(root, 'index.html'), '<input id="service-url" value="">');
  const server = createServer(configuration({ RAVENROOT_UI_ROOT: root }));
  const ui = await listen(server);
  try {
    assert.equal((await fetch(ui + '/')).status, 200);
    for (const path of ['/health', '/ready']) assert.equal((await fetch(ui + path)).status, 200);
    for (const path of ['/v1', '/v1/runtime', '/v1/events', '/backend-health', '/backend-ready']) {
      const response = await fetch(ui + path);
      assert.equal(response.status, 503);
      assert.equal((await response.json()).error, 'UI_BACKEND_NOT_CONFIGURED');
    }
  } finally { await stop(server); rmSync(root, { recursive: true }); }
});

test('explicit loopback targets cannot point at the configured UI port', () => {
  for (const host of ['localhost', 'localhost.', '127.0.0.1', '127.1', '127.0.0.2', '[::1]', '0.0.0.0', '[::]']) {
    assert.throws(() => configuration({ RAVENROOT_UI_BACKEND_URL: `http://${host}:8080` }), /UI listening port/);
  }
  assert.throws(() => configuration({ RAVENROOT_UI_PORT: '80', RAVENROOT_UI_BACKEND_URL: 'http://localhost' }), /UI listening port/);
  assert.ok(configuration({ RAVENROOT_UI_PORT: '8081', RAVENROOT_UI_BACKEND_URL: 'http://localhost:8080' }).upstream);
});

test('server-held bearer configuration stays private and never resolves references', () => {
  const sentinel = 'server-secret-canary';
  for (const token of ['', 'Bearer secret', 'bad\nsecret', 'bad:secret', 'a'.repeat(16385)]) {
    let error;
    try { configuration({ RAVENROOT_UI_BACKEND_URL: 'http://backend:8081', RAVENROOT_UI_BACKEND_TOKEN: token }); }
    catch (caught) { error = caught; }
    assert.ok(error); assert.ok(!String(error.stack).includes(token || 'empty-token-canary'));
  }
  assert.throws(() => configuration({ RAVENROOT_UI_BACKEND_TOKEN: sentinel }), /TOKEN_CONFIGURATION_REFUSED/);
  const config = configuration({ RAVENROOT_UI_BACKEND_URL: 'http://backend:8081', RAVENROOT_UI_BACKEND_TOKEN: sentinel });
  assert.ok(!JSON.stringify(config).includes(sentinel));
  // A value that happens to resemble a relative reference is a literal bearer, not a file read.
  assert.ok(configuration({ RAVENROOT_UI_BACKEND_URL: 'http://backend:8081', RAVENROOT_UI_BACKEND_TOKEN: 'secrets/backend-token' }));
});

for (const prefix of ['', '/ui']) test(`default bearer precedence, origin, confinement and streaming ${prefix || '/'}`, async () => {
  const root = mkdtempSync(join(tmpdir(), 'ravenroot-default-token-test-'));
  const secret = 'server-only-canary-555';
  writeFileSync(join(root, 'index.html'), '<input id="service-url" value="">');
  writeFileSync(join(root, 'asset.js'), 'export const safe = true;');
  const seen = [];
  let finishStream;
  const backend = http.createServer((req, res) => {
    seen.push({ path: req.url, auth: req.headers.authorization });
    const redirectStatus = /\/redirect\/(301|302|303|307|308)$/.exec(req.url)?.[1];
    if (redirectStatus) {
      res.writeHead(Number(redirectStatus), { Location: `https://external.invalid/${secret}`, 'X-Canary': secret }); res.end(secret); return;
    }
    if (req.url.endsWith('/not-modified')) { res.writeHead(304, { ETag: 'public-version' }).end(); return; }
    if (req.url.endsWith('/events')) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Canary': secret });
      res.write('data: ready ☃\n\n');
      finishStream = () => { res.write('data: ' + secret.slice(0, 6)); res.write(secret.slice(6) + ' ☃\n\n'); res.end(); }; return;
    }
    if (req.url.endsWith('/reflect')) {
      res.writeHead(500, { 'Content-Type': 'application/json', 'X-Canary': secret });
      res.write('{"message":"' + secret.slice(0, 7)); res.end(secret.slice(7) + '"}'); return;
    }
    if (req.url.endsWith('/expired')) { res.writeHead(401).end('Expired'); return; }
    res.writeHead(req.headers.authorization ? 200 : 401, { 'Content-Type': 'application/json' }).end('{"ok":true}');
  });
  const upstream = await listen(backend);
  const config = configuration({ RAVENROOT_UI_ROOT: root, RAVENROOT_UI_PREFIX: prefix,
    RAVENROOT_UI_BACKEND_URL: upstream + '/private', RAVENROOT_UI_BACKEND_TOKEN: secret });
  const server = createServer(config);
  // Mutation after startup cannot redirect the captured upstream or its credential.
  config.upstream.hostname = 'external.invalid';
  const ui = await listen(server);
  try {
    const route = ui + prefix;
    for (const path of ['/', '/asset.js']) {
      const body = await (await fetch(route + path)).text(); assert.ok(!body.includes(secret));
      if (path === '/') assert.ok(body.includes('data-server-auth="true"'));
    }
    await fetch(ui + '/health'); assert.equal(seen.length, 0);
    assert.equal((await fetch(route + '/backend-health')).status, 401); assert.equal(seen.at(-1).auth, undefined);
    for (let i = 0; i < 5; i += 1) {
      const response = await fetch(route + '/v1/runtime', { headers: { Origin: ui, 'Sec-Fetch-Site': 'same-origin' } });
      assert.equal(response.status, 200); assert.equal(seen.at(-1).auth, 'Bearer ' + secret);
      assert.ok(!(await response.text()).includes(secret));
    }
    const rawRequest = (authorizationHeaders, path = '/v1/runtime') => new Promise((resolve, reject) => {
      const request = http.request(route + path, { headers: ['Host', new URL(route).host, ...authorizationHeaders] }, response => {
        response.resume(); response.on('end', () => resolve(response.statusCode)); response.on('error', reject);
      });
      request.setTimeout(5000, () => request.destroy(new Error('Raw proxy request timed out')));
      request.on('error', reject); request.end();
    });
    // RFC 6750 section 2.1 permits 1*SP. Use raw HTTP so a fetch implementation cannot normalize
    // the field before the proxy sees it; preserve every valid field byte even after hop filtering.
    for (const authorization of ['Bearer personal-token', 'Bearer  personal-token', 'bEaReR    personal-token']) {
      const count = seen.length;
      assert.equal(await rawRequest(['Authorization', authorization, 'Connection', 'Authorization']), 200);
      assert.equal(seen.length, count + 1); assert.equal(seen.at(-1).auth, authorization);
    }
    for (const authorization of ['', 'Bearer', 'Bearer  ', 'Basic abc', 'Bearer\tpersonal-token',
      'Bearer \tpersonal-token', 'Bearer bad token', 'Bearer secret, Bearer other', 'Bearer per:sonal']) {
      const count = seen.length;
      assert.equal(await rawRequest(['Authorization', authorization, 'Connection', 'Authorization']), 400);
      assert.equal(seen.length, count); // Presence/malformed input must never select the server default.
    }
    const beforeDuplicate = seen.length;
    assert.equal(await rawRequest(['Authorization', 'Bearer a', 'Authorization', 'Bearer  b']), 400);
    assert.equal(seen.length, beforeDuplicate);
    for (const headers of [{ Origin: 'https://evil.invalid' }, { Origin: 'null' }, { 'Sec-Fetch-Site': 'cross-site' }, { 'Sec-Fetch-Site': 'same-site' }]) {
      const count = seen.length; assert.equal((await fetch(route + '/v1/runtime', { headers })).status, 403); assert.equal(seen.length, count);
    }
    // Explicit client authority still reaches the backend's existing origin policy unchanged.
    await fetch(route + '/v1/runtime', { headers: { Origin: 'https://evil.invalid', Authorization: 'Bearer personal' } });
    assert.equal(seen.at(-1).auth, 'Bearer personal');
    const count = seen.length;
    const rejectedAuthorization = 'Bearer  rejected-personal';
    assert.equal(await rawRequest(['Authorization', rejectedAuthorization, 'Connection', 'Authorization'], '/v1/expired'), 401);
    assert.equal(seen.length, count + 1); assert.equal(seen.at(-1).auth, rejectedAuthorization);
    assert.equal((await fetch(route + '/v1/expired')).status, 401); assert.equal(seen.at(-1).auth, 'Bearer ' + secret);
    for (const status of [301, 302, 303, 307, 308]) {
      const beforeRedirect = seen.length;
      const redirect = await fetch(route + '/v1/redirect/' + status);
      assert.equal(redirect.status, 502); assert.equal(redirect.headers.get('location'), null);
      assert.equal(await redirect.text(), 'Backend redirect refused'); assert.equal(seen.length, beforeRedirect + 1);
    }
    const notModified = await fetch(route + '/v1/not-modified');
    assert.equal(notModified.status, 304); assert.equal(notModified.headers.get('etag'), 'public-version');
    assert.equal(await notModified.text(), '');
    const reflected = await fetch(route + '/v1/reflect'); assert.equal(reflected.status, 500);
    assert.equal(reflected.headers.get('x-canary'), '[redacted]'); assert.equal(await reflected.text(), '{"message":"[redacted]"}');
    const stream = await fetch(route + '/v1/events'); const reader = stream.body.getReader(); const decoder = new TextDecoder();
    const first = await Promise.race([reader.read(), new Promise((_, reject) => setTimeout(() => reject(new Error('Incremental stream blocked')), 1000))]);
    assert.equal(decoder.decode(first.value), 'data: ready ☃\n\n'); assert.equal(stream.headers.get('x-canary'), '[redacted]');
    finishStream(); let rest = '';
    for (;;) { const chunk = await reader.read(); if (chunk.done) break; rest += decoder.decode(chunk.value, { stream: true }); }
    rest += decoder.decode(); assert.equal(rest, 'data: [redacted] ☃\n\n');
    assert.ok(seen.every(request => request.path.startsWith('/private/')));
  } finally { await stop(server); await stop(backend); rmSync(root, { recursive: true, force: true }); }
});
