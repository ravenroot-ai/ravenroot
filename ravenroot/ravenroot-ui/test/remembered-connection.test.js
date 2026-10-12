import { describe, expect, it, vi } from 'vitest';
import { createRememberedConnection, connectionService, CONNECTION_STORAGE_KEY } from '../src/remembered-connection.js';
import { RavenrootRuntimeClient } from '../src/runtime-client.js';
import { RavenrootAssistantClient } from '../src/assistant-client.js';
import { RavenrootCredentialClient } from '../src/credential-client.js';
import { GraphAuthoringClient } from '../src/graph-authoring-client.js';
const origin = 'https://ui.example.test';
function storageFixture() {
  const values = new Map();
  return { values, getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}
function session(storage, extra = {}) { return createRememberedConnection({ origin, storage: () => storage, ...extra }); }
async function remember(storage, service = '/api', token = 'opaque-token') {
  const s = session(storage); s.startup(); s.authenticate(service, token); await s.authenticated(() => true); return s;
}
const record = storage => JSON.parse(storage.getItem(CONNECTION_STORAGE_KEY));
const failure = status => new Response('{}', { status, headers: { 'Content-Type': 'application/json' } });
describe('one explicitly opted-in browser connection', () => {
  it('asks once after explicit success, keeps decline memory only and never asks on restoration', async () => {
    const storage = storageFixture(); const s = session(storage); s.startup(); const confirmation = vi.fn(() => false);
    await s.authenticated(confirmation); expect(confirmation).not.toHaveBeenCalled();
    s.authenticate('/api', 'opaque-token'); expect(storage.values.size).toBe(0);
    await s.authenticated(confirmation); await s.authenticated(confirmation);
    s.authenticate('/api', 'opaque-token'); await s.authenticated(confirmation);
    const rejectedProvider = s.provider('/api'); rejectedProvider.clearAccessTokenIfCurrent(rejectedProvider.getAccessTokenSnapshot());
    s.authenticate('/api', 'replacement'); await s.authenticated(confirmation);
    expect(confirmation).toHaveBeenCalledTimes(1); expect(storage.values.size).toBe(0);
    await remember(storage); const restored = session(storage);
    expect(restored.startup('/operator')).toBe(origin + '/api');
    await restored.authenticated(confirmation); expect(confirmation).toHaveBeenCalledTimes(1);
    expect(restored.provider('/api').getAccessToken()).toBe('opaque-token');
    expect(record(storage)).toEqual({ version: 1, service: origin + '/api', token: 'opaque-token' });
  });
  it('resolves manual > saved > operator > same origin including public prefix binding', async () => {
    const storage = storageFixture(); expect(session(storage).startup()).toBe(origin);
    expect(session(storage).startup('/operator')).toBe(origin + '/operator');
    await remember(storage, '/saved'); const restored = session(storage);
    expect(restored.startup('/changed')).toBe(origin + '/saved'); expect(restored.select('/manual')).toBe(origin + '/manual');
    expect(restored.provider('/manual').getAccessToken()).toBe(''); expect(restored.provider('/saved').getAccessToken()).toBe('');
    restored.authenticate('/manual', 'new-token'); expect(restored.provider('/saved').getAccessToken()).toBe('');
    expect(restored.provider('/manual').getAccessToken()).toBe('new-token');
  });
  it('restores a validated legacy service-only preference below the new record and never a token', async () => {
    const storage = storageFixture(); const legacy = session(storage);
    expect(legacy.startup('/operator', 'https://legacy.example.test/api')).toBe('https://legacy.example.test/api');
    expect(legacy.provider('https://legacy.example.test/api').getAccessToken()).toBe('');
    await remember(storage, '/saved'); expect(session(storage).startup('/operator', 'https://legacy.example.test')).toBe(origin + '/saved');
  });
  it('does not persist after confirmation when connection or credential changed', async () => {
    const storage = storageFixture(); const s = session(storage); s.startup(); s.authenticate('/api', 'old');
    let answer; const pending = s.authenticated(() => new Promise(resolve => { answer = resolve; }));
    s.authenticate('/other', 'replacement'); answer(true); await pending;
    expect(storage.values.size).toBe(0); expect(s.provider('/other').getAccessToken()).toBe('replacement');
  });
  it('forgets memory and stored opt-in, retains service and prevents restoration on reopening', async () => {
    const storage = storageFixture(); const s = await remember(storage); s.forget();
    expect(s.provider('/api').getAccessToken()).toBe(''); expect(record(storage).token).toBeNull();
    const reopened = session(storage); expect(reopened.startup('/other')).toBe(origin + '/api'); expect(reopened.provider('/api').getAccessToken()).toBe('');
  });
  it.each([401, 403, 500, 'network'])('invalidates only definitive 401, not %s', async status => {
    const storage = storageFixture(); const s = await remember(storage);
    const fetchImpl = vi.fn(() => status === 'network' ? Promise.reject(new TypeError('network')) : Promise.resolve(failure(status)));
    const client = new RavenrootRuntimeClient('/api', { tokenProvider: s.provider('/api'), fetchImpl });
    await expect(client.nodeTypes()).rejects.toThrow(); if (status === 401) expect(() => s.provider('/api').getAccessToken()).toThrow('replacement');
    else expect(s.provider('/api').getAccessToken()).toBe('opaque-token');
    expect(record(storage).token).toBe(status === 401 ? null : 'opaque-token'); expect(record(storage).service).toBe(origin + '/api');
  });
  it.each(['json', 'stream'])('an in-flight %s 401 cannot clear replacement or another service', async mode => {
    const storage = storageFixture(); const s = await remember(storage); let rejectRequest;
    const fetchImpl = vi.fn(() => new Promise(resolve => { rejectRequest = resolve; }));
    const client = new RavenrootRuntimeClient('/api', { tokenProvider: s.provider('/api'), fetchImpl });
    const states = []; const result = mode === 'json' ? client.nodeTypes().catch(e => e) : client.connect(() => {}, state => states.push(state));
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalled()); s.authenticate('/api', 'replacement'); await s.authenticated(() => true); rejectRequest(failure(401));
    if (mode === 'json') await result;
    else { await vi.waitFor(() => expect(states).toContain('authentication-required')); result(); }
    expect(s.provider('/api').getAccessToken()).toBe('replacement'); expect(record(storage).token).toBe('replacement');
    const old = s.provider('/api'); const snapshot = old.getAccessTokenSnapshot(); s.authenticate('/other', 'other');
    expect(old.clearAccessTokenIfCurrent(snapshot)).toBe(false); expect(s.provider('/other').getAccessToken()).toBe('other'); expect(record(storage).token).toBe('replacement');
  });
  it.each([401, 403])('applies status %s to live streams without erasing scope-denied tokens', async status => {
    const storage = storageFixture(); const s = await remember(storage);
    const client = new RavenrootRuntimeClient('/api', { tokenProvider: s.provider('/api'), fetchImpl: async () => failure(status) });
    const states = []; const disconnect = client.connect(() => {}, state => states.push(state));
    await vi.waitFor(() => expect(states).toContain(status === 401 ? 'authentication-required' : 'revoked')); disconnect();
    expect(record(storage).token).toBe(status === 401 ? null : 'opaque-token');
  });
  it.each(['assistant', 'credentials', 'authoring'])('invalidates 401 in the %s transport', async transport => {
    const storage = storageFixture(); const s = await remember(storage);
    const options = { tokenProvider: s.provider('/api'), fetchImpl: vi.fn(async () => failure(401)) };
    if (transport === 'assistant') await new RavenrootAssistantClient('/api', options).status();
    if (transport === 'credentials') await new RavenrootCredentialClient('/api', options).list().catch(() => {});
    if (transport === 'authoring') await new GraphAuthoringClient('/api', options).list().catch(() => {});
    expect(record(storage).token).toBeNull();
  });
  it.each(['bad json', JSON.stringify({ version: 99, service: origin, token: 'secret' }),
    JSON.stringify({ version: 1, service: origin, token: 'secret', trusted: true }),
    JSON.stringify({ version: 1, service: 'https://user:secret@service.test', token: 'secret' }), 'x'.repeat(25 * 1024)])('rejects malformed/unsupported/oversized records without echoing credentials', raw => {
    const storage = storageFixture(); storage.setItem(CONNECTION_STORAGE_KEY, raw); const notices = [];
    const s = session(storage, { onNotice: message => notices.push(message) });
    expect(s.startup('/operator')).toBe(origin + '/operator'); expect(s.provider('/operator').getAccessToken()).toBe('');
    expect(storage.values.size).toBe(0); expect(notices.join()).not.toContain('secret');
  });
  it('catches storage getter/read/write/remove failures and preserves explicit memory authentication', async () => {
    for (const operation of ['getter', 'getItem', 'setItem', 'removeItem']) {
      const storage = storageFixture(); if (operation === 'removeItem') storage.setItem(CONNECTION_STORAGE_KEY, 'bad json');
      if (operation !== 'getter') storage[operation] = () => { throw new Error('secret'); };
      const s = createRememberedConnection({ origin, storage: () => { if (operation === 'getter') throw new Error('secret'); return storage; } });
      s.startup('/api'); s.authenticate('/api', 'explicit'); await s.authenticated(() => true);
      expect(s.provider('/api').getAccessToken()).toBe('explicit'); s.forget(); expect(s.provider('/api').getAccessToken()).toBe(''); expect(s.notice).not.toContain('secret');
    }
  });
  it.each(['https://user:secret@service.test', 'https://service.test?token=secret', 'https://service.test#secret', 'file:///tmp/a', '/api%2fother'])('rejects unsafe service metadata %s', service => {
    expect(() => connectionService(service, origin)).toThrow();
  });
});
