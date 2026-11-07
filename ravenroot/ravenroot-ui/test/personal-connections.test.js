import { afterEach, describe, expect, it, vi } from 'vitest';
import { mountPersonalConnections } from '../src/personal-connections.js';
import { uiText } from '../src/ui-text.js';

let mounted;
afterEach(() => { mounted?.destroy(); mounted = null; vi.unstubAllGlobals(); document.body.replaceChildren(); });
const signed = (id = 'connection-A') => ({ state: 'signed-in', identity: 'owner-A', assistantAuthorized: false,
  connections: [{ id, selected: true, label: 'Account', health: 'connected' }] });
function setup(initial = signed()) {
  let state = initial;
  const pending = [];
  const fetch = vi.fn(async (url, options) => {
    const path = new URL(url).pathname;
    if (path.endsWith('/sign-out')) state = { state: 'signed-out', connections: [] };
    if (path.endsWith('/select')) state = signed(JSON.parse(options.body).connection);
    if (path.endsWith('/disconnect')) state = { ...signed(), connections: [] };
    let body = state;
    if (path.endsWith('/sign-in')) body = { authorizationUrl: `${location.origin}/authorize` };
    if (path.endsWith('/models')) body = { models: [{ slug: 'account-model', display_name: 'Account model' }] };
    if (path.endsWith('/registration')) body = { registration: 'opaque-returning-code', expiresIn: 600 };
    if (path.endsWith('/protect')) body = { state: 'locked' };
    if (path.startsWith('/v1/executions/')) body = { output: 'private personal answer' };
    if (path === '/v1/deployments') body = { deployments: [{ deploymentId: 'deployed' }] };
    if (path.endsWith('/view')) body = { source: { deploymentId: 'deployed', incarnationId: 'incarnation', graphVersion: 'version' } };
    if (path === '/v1/personal/executions') body = { executionId: 'execution-A' };
    const index = pending.findIndex(item => item.path === path);
    if (index !== -1) {
      const held = pending.splice(index, 1)[0];
      return { ok: true, status: 200, json: () => { held.started.resolve(); return held.delivery.promise.then(() => body); } };
    }
    return { ok: true, status: 200, json: async () => body };
  });
  vi.stubGlobal('fetch', fetch);
  const host = document.createElement('div'); document.body.append(host);
  const callbacks = { onIdentity: vi.fn(), onMode: vi.fn(), onHistory: vi.fn(), onRun: vi.fn(), onCatalog: vi.fn() };
  mounted = mountPersonalConnections({ host, baseUrl: location.origin, tokenProvider: {}, ...callbacks });
  return { fetch, host, callbacks, setState(next) { state = next; },
    hold(path) {
      const held = { path, started: Promise.withResolvers(), delivery: Promise.withResolvers() };
      pending.push(held);
      return { started: held.started.promise, release: held.delivery.resolve, reject: held.delivery.reject };
    } };
}
const button = (host, key) => [...host.querySelectorAll('button')].find(node => node.textContent === uiText(key));
const field = (host, key) => host.querySelector(`[aria-label="${uiText(key)}"]`);
async function replaceIdentity(fixture, action) {
  if (action === 'select') {
    const identityCalls = fixture.callbacks.onIdentity.mock.calls.length;
    const account = field(fixture.host, 'personal.account');
    const option = document.createElement('option'); option.value = 'connection-B'; account.append(option);
    account.value = 'connection-B'; account.dispatchEvent(new Event('change'));
    await vi.waitFor(() => {
      expect(fixture.callbacks.onIdentity.mock.calls.length).toBeGreaterThan(identityCalls);
      expect(fixture.callbacks.onIdentity).toHaveBeenLastCalledWith('owner-A');
    });
    await vi.waitFor(() => expect(account.value).toBe('connection-B'));
  } else {
    button(fixture.host, 'personal.signOut').click();
    await vi.waitFor(() => expect(button(fixture.host, 'personal.signOut').disabled).toBe(false));
  }
}

describe('optional personal account controls', () => {
  it('uses only same-origin cookie requests and exposes account model choices without credentials', async () => {
    const { fetch, host, callbacks } = setup();
    await vi.waitFor(() => expect(callbacks.onCatalog).toHaveBeenCalledWith([{ slug: 'account-model', display_name: 'Account model' }]));
    expect(host.querySelector(`[aria-label="${uiText('personal.model')}"]`).value).toBe('account-model');
    expect(fetch.mock.calls.every(([url, options]) => new URL(url).origin === location.origin
      && options.credentials === 'same-origin' && !options.headers.Authorization)).toBe(true);
    expect(callbacks.onMode).toHaveBeenLastCalledWith('system', 'account-model', false);
  });
  it('keeps identity-only sign-in separate from an explicit first-time plan-use choice', async () => {
    const assign = vi.fn(); vi.stubGlobal('location', { origin: location.origin, assign });
    const fixture = setup({ state: 'signed-out', connections: [] }); await mounted.refresh();
    button(fixture.host, 'personal.signIn').click();
    await vi.waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    button(fixture.host, 'personal.reconnect').click();
    await vi.waitFor(() => expect(assign).toHaveBeenCalledTimes(2));
    const starts = fixture.fetch.mock.calls.filter(([url]) => new URL(url).pathname.endsWith('/sign-in'));
    expect(starts.map(([, options]) => JSON.parse(options.body))).toEqual([{}, { connection: '', enablePlanUse: true }]);
    expect(button(fixture.host, 'personal.reconnect').textContent).toContain('separate consent');
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
  it.each(['sign-out', 'select'])('discards a successful old execution body after completed %s', async action => {
    const fixture = setup(); await mounted.refresh();
    mounted.showExecution('execution-A');
    const held = fixture.hold('/v1/executions/execution-A');
    button(fixture.host, 'personal.readResult').click(); await held.started;
    const read = fixture.fetch.mock.calls.find(([url]) => new URL(url).pathname === '/v1/executions/execution-A');
    expect(read[1].headers['X-Ravenroot-Execution-Context']).toBe('personal');
    await replaceIdentity(fixture, action);
    expect(field(fixture.host, 'personal.executionId').value).toBe('');
    expect(field(fixture.host, 'personal.executionResult').textContent).toBe('');
    held.release();
    await vi.waitFor(() => expect(button(fixture.host, 'personal.readResult').disabled).toBe(false));
    expect(field(fixture.host, 'personal.executionId').value).toBe('');
    expect(field(fixture.host, 'personal.executionResult').textContent).toBe('');
  });
  it('renders a held successful read while its identity remains current', async () => {
    const fixture = setup(); await mounted.refresh(); mounted.showExecution('execution-A');
    const held = fixture.hold('/v1/executions/execution-A');
    button(fixture.host, 'personal.readResult').click(); await held.started;
    held.release();
    await vi.waitFor(() => expect(field(fixture.host, 'personal.executionResult').textContent).toContain('private personal answer'));
    expect(field(fixture.host, 'personal.executionId').value).toBe('execution-A');
  });
  it.each(['sign-out', 'select'])('retires private presentation at %s success before the following refresh finishes', async action => {
    const fixture = setup(); await mounted.refresh(); mounted.showExecution('execution-A');
    button(fixture.host, 'personal.readResult').click();
    await vi.waitFor(() => expect(fixture.host.textContent).toContain('private personal answer'));
    const refresh = fixture.hold('/v1/personal');
    const transition = replaceIdentity(fixture, action);
    await refresh.started;
    try {
      expect(field(fixture.host, 'personal.executionId').value).toBe('');
      expect(field(fixture.host, 'personal.executionResult').textContent).toBe('');
      expect(fixture.callbacks.onHistory).toHaveBeenLastCalledWith('system');
    } finally { refresh.release(); await transition; }
  });
  it('does not let an old state refresh restore signed-in metadata after sign-out', async () => {
    const fixture = setup({ ...signed(), assistantAuthorized: true }); await mounted.refresh();
    const held = fixture.hold('/v1/personal'); const refreshing = mounted.refresh(); await held.started;
    await replaceIdentity(fixture, 'sign-out');
    const identityCalls = fixture.callbacks.onIdentity.mock.calls.length;
    held.release(); await refreshing;
    expect(fixture.callbacks.onIdentity).toHaveBeenCalledTimes(identityCalls);
    expect(field(fixture.host, 'personal.account').options).toHaveLength(0);
    expect(fixture.callbacks.onMode).toHaveBeenLastCalledWith('system', '', false);
    expect(fixture.callbacks.onCatalog).toHaveBeenLastCalledWith([]);
  });
  it.each(['/v1/deployments/deployed/view', '/v1/personal/executions'])('retires a deployment operation held at %s', async path => {
    const fixture = setup(); await mounted.refresh();
    button(fixture.host, 'personal.loadDeployments').click();
    await vi.waitFor(() => expect(field(fixture.host, 'personal.deployment').value).toBe('deployed'));
    const held = fixture.hold(path); button(fixture.host, 'personal.runDeployment').click(); await held.started;
    await replaceIdentity(fixture, 'sign-out'); held.release();
    await vi.waitFor(() => expect(button(fixture.host, 'personal.runDeployment').disabled).toBe(false));
    expect(fixture.callbacks.onRun).not.toHaveBeenCalled();
    expect(fixture.callbacks.onHistory).toHaveBeenLastCalledWith('system');
    expect(field(fixture.host, 'personal.executionId').value).toBe('');
    if (path.endsWith('/view')) expect(fixture.fetch.mock.calls.some(([url]) => new URL(url).pathname === '/v1/personal/executions')).toBe(false);
  });
  it('does not restore an old returning code after disconnect', async () => {
    const fixture = setup(); await mounted.refresh();
    const held = fixture.hold('/v1/personal/registration'); button(fixture.host, 'personal.createCode').click(); await held.started;
    button(fixture.host, 'personal.disconnect').click();
    await vi.waitFor(() => expect(button(fixture.host, 'personal.disconnect').disabled).toBe(false));
    held.release(); await vi.waitFor(() => expect(button(fixture.host, 'personal.createCode').disabled).toBe(false));
    expect(field(fixture.host, 'personal.registrationCode').value).toBe('');
  });
  it('does not restore an old model catalog after sign-out', async () => {
    const fixture = setup(); await mounted.refresh();
    const held = fixture.hold('/v1/personal/models'); const refreshing = mounted.refresh(); await held.started;
    await replaceIdentity(fixture, 'sign-out'); held.release(); await refreshing;
    expect(fixture.callbacks.onCatalog).toHaveBeenLastCalledWith([]);
    expect(field(fixture.host, 'personal.model').options).toHaveLength(0);
    expect(fixture.callbacks.onMode).toHaveBeenLastCalledWith('system', '', false);
  });
  it('does not refresh personal authority from a retired authorization response', async () => {
    const fixture = setup(); await mounted.refresh();
    const held = fixture.hold('/v1/personal/authorize'); button(fixture.host, 'personal.authorize').click(); await held.started;
    await replaceIdentity(fixture, 'sign-out');
    const requests = fixture.fetch.mock.calls.length;
    held.release(); await vi.waitFor(() => expect(button(fixture.host, 'personal.authorize').disabled).toBe(false));
    expect(fixture.fetch).toHaveBeenCalledTimes(requests);
    expect(fixture.callbacks.onMode).toHaveBeenLastCalledWith('system', '', false);
  });
  it('ignores an old read failure and disposed completions', async () => {
    const fixture = setup(); await mounted.refresh(); mounted.showExecution('execution-A');
    const held = fixture.hold('/v1/executions/execution-A'); button(fixture.host, 'personal.readResult').click(); await held.started;
    await replaceIdentity(fixture, 'sign-out');
    const status = fixture.host.querySelector('[role=status]').textContent;
    held.reject(new Error('private old error'));
    await vi.waitFor(() => expect(button(fixture.host, 'personal.readResult').disabled).toBe(false));
    expect(fixture.host.querySelector('[role=status]').textContent).toBe(status);
    const registration = fixture.hold('/v1/personal/registration'); button(fixture.host, 'personal.createCode').click(); await registration.started;
    const code = field(fixture.host, 'personal.registrationCode');
    const create = button(fixture.host, 'personal.createCode'); mounted.destroy(); registration.release();
    await vi.waitFor(() => expect(create.disabled).toBe(false)); expect(code.value).toBe('');
    expect(fixture.host.children).toHaveLength(0);
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
