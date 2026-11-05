import { it, expect, vi, afterEach } from 'vitest';
import { webcrypto } from 'node:crypto';
import { releaseOperationIdentity, observeReleaseProposal } from '../src/release-proposal-progress.js';
import { GraphAuthoringClient, validateGraphAuthoringCapability } from '../src/graph-authoring-client.js';
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const binding = { mode: 'isolated-automerge', deploymentId: 'automation',
  registrationIdentity: '11111111-1111-1111-1111-111111111111', graphVersion: 'a'.repeat(64), sourceNodeId: 'source' };
const candidate = { documentId: 'orders.graphml', graphId: 'orders', releaseVersion: 2, graphMl: '<exact/>' };
const feedback = () => ({ signal: new AbortController().signal, published: vi.fn(), released: vi.fn(), pending: vi.fn() });

it('derives the same operation across reload/object ordering and changes identity for another candidate/proposal/binding', async () => {
  vi.stubGlobal('crypto', webcrypto);
  const first = await releaseOperationIdentity([candidate, 'proposal', binding]);
  expect(first).toMatch(/^release-[a-f0-9]{64}$/);
  expect(await releaseOperationIdentity([Object.fromEntries(Object.entries(candidate).reverse()), 'proposal', binding])).toBe(first);
  for (const parts of [[{ ...candidate, graphMl: '<other/>' }, 'proposal', binding],
    [candidate, 'different-proposal', binding], [candidate, 'proposal', { ...binding, sourceNodeId: 'other' }]])
    expect(await releaseOperationIdentity(parts)).not.toBe(first);
});

it('preserves uncertain acknowledgement and retries with the same identity after a new incarnation observation', async () => {
  let incarnation = 'first', calls = [];
  const fetchImpl = vi.fn(async (url, options) => {
    if (!url.endsWith('/trigger')) return new Response(JSON.stringify({ ...binding,
      runtimeIncarnationId: incarnation, desiredState: 'RUNNING', observedState: 'READY' }));
    calls.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ requestId: 'same-identity', outcome: calls.length === 1 ? 'RECONCILE' : 'DUPLICATE' }),
      { status: calls.length === 1 ? 503 : 200 });
  });
  const client = new GraphAuthoringClient('', { fetchImpl });
  expect((await client.triggerAutomation(binding, 'same-identity')).outcome).toBe('RECONCILE');
  incarnation = 'after-restart';
  expect((await client.triggerAutomation(binding, 'same-identity')).outcome).toBe('DUPLICATE');
  expect(calls.map(call => call.requestId)).toEqual(['same-identity', 'same-identity']);
  expect(calls[1]).toMatchObject({ registrationIdentity: binding.registrationIdentity, incarnationId: 'after-restart' });
});

it('refuses stopped/replaced bindings before trigger and keeps network acknowledgement loss uncertain', async () => {
  for (const changed of [{ desiredState: 'PAUSED' }, { registrationIdentity: 'replacement' }, { graphVersion: 'b'.repeat(64) }]) {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ ...binding, desiredState: 'RUNNING', observedState: 'READY', ...changed })));
    await expect(new GraphAuthoringClient('', { fetchImpl }).triggerAutomation(binding, 'same')).rejects.toThrow();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  }
  const fetchImpl = vi.fn(async url => {
    if (url.endsWith('/trigger')) throw new TypeError('connection lost after send');
    return new Response(JSON.stringify({ ...binding, desiredState: 'RUNNING', observedState: 'READY', runtimeIncarnationId: 'first' }));
  });
  expect((await new GraphAuthoringClient('', { fetchImpl }).triggerAutomation(binding, 'same')).outcome).toBe('RECONCILE');
  const capability = { mode: 'git', provider: 'github', operations: [] };
  for (const value of [null, { ...binding, extra: true }, { ...binding, registrationIdentity: '-'.repeat(36) }])
    expect(() => validateGraphAuthoringCapability({ ...capability, releaseAutomation: value })).toThrow();
});

it('only acknowledges the exact RELEASE source bytes/version and associated publication', async () => {
  const seen = feedback();
  const client = { open: vi.fn(async () => ({ ...candidate, published: true })) };
  await observeReleaseProposal({ client, candidate, feedback: seen, assertCurrent: () => {}, decode: value => value.graphMl });
  expect(client.open).toHaveBeenCalledWith('orders.graphml', 'release', expect.objectContaining({ signal: expect.anything() }));
  expect(seen.published).toHaveBeenCalledOnce();
  for (const wrong of [{ releaseVersion: 3 }, { graphMl: '<different/>' }, { graphId: 'different' }]) {
    const failed = feedback();
    await observeReleaseProposal({ client: { open: async () => ({ ...candidate, ...wrong, published: true }) },
      candidate, feedback: failed, assertCurrent: () => {}, decode: value => value.graphMl, attempts: 1, interval: 0 });
    expect(failed.published).not.toHaveBeenCalled(); expect(failed.released).not.toHaveBeenCalled();
    expect(failed.pending).toHaveBeenCalledOnce();
  }
});

it('bounds reads, handles an initially absent release, and cancels pending reads/timers on modal close', async () => {
  let reads = 0; const seen = feedback();
  await observeReleaseProposal({ client: { open: async () => {
    if (++reads === 1) throw Object.assign(new Error('absent'), { status: 404 });
    return { ...candidate, published: true };
  } }, candidate, feedback: seen, assertCurrent: () => {}, decode: value => value.graphMl, interval: 0 });
  expect(reads).toBe(2); expect(seen.published).toHaveBeenCalledOnce();
  const closed = new AbortController(); const stopped = { ...feedback(), signal: closed.signal }; let readSignal;
  const task = observeReleaseProposal({ client: { open: (_id, _source, { signal }) => {
    readSignal = signal; return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  } }, candidate, feedback: stopped, assertCurrent: () => {}, decode: value => value.graphMl });
  closed.abort(); await task; expect(readSignal.aborted).toBe(true);
  expect(stopped.pending).not.toHaveBeenCalled();
  const bounded = feedback(); const boundedClient = { open: vi.fn(async () => ({ ...candidate, releaseVersion: 1 })) };
  await observeReleaseProposal({ client: boundedClient, candidate, feedback: bounded, assertCurrent: () => {}, decode: value => value.graphMl,
    attempts: 3, interval: 0 });
  expect(boundedClient.open).toHaveBeenCalledTimes(3);
});
