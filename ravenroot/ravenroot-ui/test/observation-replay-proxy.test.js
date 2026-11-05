// @vitest-environment node
import { test, expect } from 'vitest';
import { createServer, request } from 'node:http';
import { createObservationReplayProxy, isObservationTarget } from '../e2e/deployment-persistence-real/observation-replay-proxy.mjs';

const target = `/v1/deployments/owned/events?graphVersion=${'a'.repeat(64)}&include=diagnostics`;
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const close = server => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); });
const get = (port, path, method = 'GET', headers = {}) => new Promise((resolve, reject) => {
  const call = request({ hostname: '127.0.0.1', port, path, method, headers }, response => {
    const chunks = []; response.on('data', chunk => chunks.push(chunk));
    response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString() }));
  });
  call.on('error', reject); call.end();
});

test('only canonical fixture observation targets are accepted', () => {
  expect(isObservationTarget(target)).toBe(true);
  for (const bad of ['http://elsewhere' + target, '//elsewhere' + target, '/other', target + '#fragment',
    target.replace('owned', '%GG'), target.replace('owned', '%2Fother'), target.replace('owned', '..'),
    target.replace('graphVersion=', 'unknown='), target + '&graphVersion=' + 'b'.repeat(64),
    target.replace('include=diagnostics', 'include=other'), target + '\\foreign', target + '\n']) {
    expect(isObservationTarget(bad), bad).toBe(false);
  }
  for (const origin of ['https://127.0.0.1:1234', 'http://localhost:1234', 'http://user@127.0.0.1:1234',
    'http://127.0.0.1:1234/path', 'http://127.0.0.1:1234/?other']) {
    expect(() => createObservationReplayProxy(origin, () => {})).toThrow();
  }
});

test('foreign authorities and unsupported requests never reach any upstream', async () => {
  let intended = 0, foreign = 0;
  const backend = createServer((_req, res) => { intended++; res.end(); });
  const outsider = createServer((_req, res) => { foreign++; res.end(); });
  let proxy;
  try {
    await listen(backend); await listen(outsider);
    proxy = createObservationReplayProxy(`http://127.0.0.1:${backend.address().port}`, () => {});
    await listen(proxy);
    const foreignOrigin = `127.0.0.1:${outsider.address().port}`;
    for (const path of [`http://${foreignOrigin}${target}`, `//${foreignOrigin}${target}`, '/unsupported',
      target.replace('owned', '%ZZ'), target + '#fragment']) {
      expect((await get(proxy.address().port, path)).status).toBe(400);
    }
    expect((await get(proxy.address().port, target, 'POST')).status).toBe(400);
    expect({ intended, foreign }).toEqual({ intended: 0, foreign: 0 });
  } finally { if (proxy) await close(proxy); await close(backend); await close(outsider); }
});

test('the owned authority receives exact binding and cursor headers and only the ready frame is cut', async () => {
  let seen, cut, ready;
  const confirmed = new Promise(resolve => { ready = resolve; });
  const backend = createServer((req, res) => {
    seen = { path: req.url, headers: req.headers };
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.write('event: life');
    res.write('cycle\ndata: {"state":"READY"}\n\n');
    res.write('event: execution\ndata: real-later-frame\n\n');
  });
  let proxy;
  try {
    await listen(backend);
    proxy = createObservationReplayProxy(`http://127.0.0.1:${backend.address().port}`, fn => { cut = fn; ready(); });
    await listen(proxy);
    const response = get(proxy.address().port, target, 'GET', { host: 'foreign.invalid',
      'x-ravenroot-deployment-incarnation': 'owned-binding', 'last-event-id': 'opaque-cursor' });
    await confirmed; cut();
    expect(await response).toEqual({ status: 200, body: 'event: lifecycle\ndata: {"state":"READY"}\n\n' });
    expect(seen.path).toBe(target);
    expect(seen.headers).toMatchObject({ host: `127.0.0.1:${backend.address().port}`,
      'x-ravenroot-deployment-incarnation': 'owned-binding', 'last-event-id': 'opaque-cursor' });
  } finally { if (proxy) await close(proxy); await close(backend); }
});
