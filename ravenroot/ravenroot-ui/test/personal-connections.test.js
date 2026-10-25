import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountPersonalConnections } from '../src/personal-connections.js';
import { uiText } from '../src/ui-text.js';

let mounted;
afterEach(() => { mounted?.destroy(); mounted = null; vi.unstubAllGlobals(); document.body.replaceChildren(); });
const signed = (id = 'connection-A') => ({ state: 'signed-in', identity: 'owner-A', assistantAuthorized: false,
  connections: [{ id, selected: true, label: 'Account', health: 'connected' }] });
function setup(initial = signed()) {
  let state = initial;
  const fetch = vi.fn(async (url, options) => {
    const path = new URL(url).pathname;
    let body = state;
    if (path.endsWith('/models')) body = { models: [{ slug: 'account-model', display_name: 'Account model' }] };
    if (path.endsWith('/registration')) body = { registration: 'opaque-returning-code', expiresIn: 600 };
    if (path.endsWith('/protect')) body = { state: 'locked' };
    if (path.startsWith('/v1/executions/')) body = { output: 'private personal answer' };
    return { ok: true, status: 200, json: async () => body };
  });
  vi.stubGlobal('fetch', fetch);
  const host = document.createElement('div'); document.body.append(host);
  const callbacks = { onIdentity: vi.fn(), onMode: vi.fn(), onHistory: vi.fn(), onRun: vi.fn(), onCatalog: vi.fn() };
  mounted = mountPersonalConnections({ host, baseUrl: location.origin, tokenProvider: {}, ...callbacks });
  return { fetch, host, callbacks, setState(next) { state = next; } };
}
const button = (host, key) => [...host.querySelectorAll('button')].find(node => node.textContent === uiText(key));

describe('optional personal account controls', () => {
  it('uses only same-origin cookie requests and exposes account model choices without credentials', async () => {
    const { fetch, host, callbacks } = setup();
    await vi.waitFor(() => expect(callbacks.onCatalog).toHaveBeenCalledWith([{ slug: 'account-model', display_name: 'Account model' }]));
    expect(host.querySelector(`[aria-label="${uiText('personal.model')}"]`).value).toBe('account-model');
    expect(fetch.mock.calls.every(([url, options]) => new URL(url).origin === location.origin
      && options.credentials === 'same-origin' && !options.headers.Authorization)).toBe(true);
    expect(callbacks.onMode).toHaveBeenLastCalledWith('system', 'account-model', false);
  });
  it('clears a retained private answer on account switch even when local owner is unchanged', async () => {
    const fixture = setup(); await mounted.refresh();
    mounted.showExecution('execution-A'); button(fixture.host, 'personal.readResult').click();
    await vi.waitFor(() => expect(fixture.host.textContent).toContain('private personal answer'));
    const read = fixture.fetch.mock.calls.find(([url]) => new URL(url).pathname === '/v1/executions/execution-A');
    expect(read[1].headers['X-Ravenroot-Execution-Context']).toBe('personal');
    fixture.setState(signed('connection-B')); await mounted.refresh();
    expect(fixture.host.textContent).not.toContain('private personal answer');
    expect(fixture.host.querySelector(`[aria-label="${uiText('personal.executionId')}"]`).value).toBe('');
  });
  it('clears passphrase fields immediately and on disposal', async () => {
    const { host, fetch } = setup(); await mounted.refresh();
    const password = host.querySelector('input[type=password]'); password.value = 'a long local passphrase';
    button(host, 'personal.protect').click();
    await vi.waitFor(() => expect(fetch.mock.calls.some(([url]) => new URL(url).pathname.endsWith('/protect'))).toBe(true));
    expect(password.value).toBe(''); mounted.destroy(); expect(host.children).toHaveLength(0);
  });
  it('shows only an opaque returning code and clears it when the account changes', async () => {
    const fixture = setup(); await mounted.refresh();
    button(fixture.host, 'personal.createCode').click();
    const code = fixture.host.querySelector(`[aria-label="${uiText('personal.registrationCode')}"]`);
    await vi.waitFor(() => expect(code.value).toBe('opaque-returning-code'));
    fixture.setState(signed('connection-B')); await mounted.refresh(); expect(code.value).toBe('');
    expect(fixture.callbacks.onMode).toHaveBeenLastCalledWith('system', 'account-model', false);
  });
  it('does not contact a cross-origin personal service', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    const host = document.createElement('div'); document.body.append(host);
    mounted = mountPersonalConnections({ host, baseUrl: 'https://other.invalid', tokenProvider: {}, onIdentity: vi.fn(), onMode: vi.fn(), onHistory: vi.fn(), onRun: vi.fn() });
    await vi.waitFor(() => expect(host.textContent).toContain(uiText('personal.sameOrigin')));
    expect(fetch).not.toHaveBeenCalled();
  });
});
